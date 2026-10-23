import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { Ventas } from './ventas';
import { VentaResumen } from './ventas.models';
import { VentasService } from './ventas.service';
import { resumenDePrueba, ventaDePrueba } from './ventas.testing';

registerLocaleData(localeEsAr);

describe('Ventas (consulta y reimpresión, G60)', () => {
  let fixture: ComponentFixture<Ventas>;
  let api: { listar: ReturnType<typeof vi.fn>; obtener: ReturnType<typeof vi.fn> };

  const pagina = (
    items: VentaResumen[],
    cambios: Partial<PageResponse<VentaResumen>> = {},
  ): PageResponse<VentaResumen> => ({
    items,
    page: 0,
    size: 20,
    totalElements: items.length,
    totalPages: 1,
    ...cambios,
  });

  async function crear(items: VentaResumen[] = [resumenDePrueba(5), resumenDePrueba(4)], rol = 'DUENO') {
    api = {
      listar: vi.fn(() => of(pagina(items))),
      obtener: vi.fn((id: number) => of(ventaDePrueba({ ventaId: id, numero: id / 10 }))),
    };
    const sesion = signal({ userId: 7, nombre: 'Ana', email: 'a@x.com', rol, ferreteriaId: 3, ferreteriaNombre: 'Tornillo' });
    await TestBed.configureTestingModule({
      imports: [Ventas],
      providers: [
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: VentasService, useValue: api },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly(), esDueno: () => rol === 'DUENO' } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Ventas);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const filas = () => Array.from(el().querySelectorAll<HTMLElement>('table.ventas tbody tr'));
  const celdas = (fila: HTMLElement) => Array.from(fila.querySelectorAll('td')).map((c) => normal(c.textContent));
  const detalle = () => el().querySelector<HTMLElement>('section.detalle');
  const boton = (texto: string, dentro: ParentNode = el()) =>
    Array.from(dentro.querySelectorAll<HTMLButtonElement>('button')).find((b) => normal(b.textContent) === texto);

  function campo(nombre: string, valor: string) {
    const input = el().querySelector<HTMLInputElement>(`[name="${nombre}"]`)!;
    input.value = valor;
    input.dispatchEvent(new Event('input'));
  }

  async function buscar() {
    el().querySelector<HTMLFormElement>('form.filtros')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await refrescar();
  }

  it('el Vendedor ve el aviso y no se pide nada', async () => {
    await crear(undefined, 'VENDEDOR');

    expect(normal(el().querySelector('[role="status"]')?.textContent)).toBe('La consulta de ventas es solo del dueño.');
    expect(api.listar).not.toHaveBeenCalled();
    expect(el().querySelector('form.filtros')).toBeNull();
  });

  it('lista las ventas con número, fecha y hora, cliente, usuario y total', async () => {
    await crear([resumenDePrueba(5), resumenDePrueba(4, { clienteNombre: undefined, facturable: false })]);

    expect(api.listar).toHaveBeenCalledWith({ page: 0 });
    expect(filas()).toHaveLength(2);
    expect(celdas(filas()[0])).toEqual(['5', '22/10/2026 16:05', 'Cliente 5', 'Ana Vendedora', '$ 3.000,00']);
    expect(celdas(filas()[1])).toEqual([
      '4 No facturable',
      '22/10/2026 16:05',
      'Consumidor Final',
      'Ana Vendedora',
      '$ 3.000,00',
    ]);
  });

  it('sin ventas lo dice', async () => {
    await crear([]);

    expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('Todavía no hay ventas.');
  });

  it('filtra por número, cliente y fechas; Limpiar vuelve a la lista completa', async () => {
    await crear();
    campo('numero', '15');
    campo('cliente', '  Pérez ');
    campo('desde', '2026-10-01');
    campo('hasta', '2026-10-22');
    api.listar.mockReturnValue(of(pagina([])));

    await buscar();

    expect(api.listar).toHaveBeenLastCalledWith({
      page: 0,
      numero: 15,
      cliente: 'Pérez',
      desde: '2026-10-01',
      hasta: '2026-10-22',
    });
    expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('No hay ventas con esos filtros.');

    boton('Limpiar')!.click();
    await refrescar();
    expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
  });

  it('un número que no es entero no se manda y avisa', async () => {
    await crear();
    campo('numero', '1,5');

    await buscar();

    expect(api.listar).toHaveBeenCalledTimes(1);
    expect(normal(el().querySelector('[role="alert"]')?.textContent)).toBe('El número tiene que ser un entero.');
  });

  it('pagina', async () => {
    await crear();
    api.listar.mockReturnValue(of(pagina([resumenDePrueba(5)], { totalPages: 3, page: 0 })));
    await buscar();

    boton('Siguiente')!.click();
    await refrescar();

    expect(api.listar).toHaveBeenLastCalledWith({ page: 1 });
  });

  it('si la lista no carga lo avisa y deja reintentar', async () => {
    await crear();
    api.listar.mockReturnValue(throwError(() => new Error('caída')));
    await buscar();

    expect(normal(el().querySelector('[role="alert"]')?.textContent)).toContain('No se pudieron cargar las ventas.');
    api.listar.mockReturnValue(of(pagina([resumenDePrueba(5)])));
    boton('Reintentar')!.click();
    await refrescar();
    expect(filas()).toHaveLength(1);
  });

  it('al elegir una venta muestra el detalle con renglones, totales, pagos y quién la cobró', async () => {
    await crear();

    filas()[0].click();
    await refrescar();

    expect(api.obtener).toHaveBeenCalledWith(50);
    const d = normal(detalle()!.textContent);
    expect(d).toContain('Venta N.º 5');
    expect(d).toContain('Martillo');
    expect(d).toContain('Subtotal $ 3.000,00');
    expect(d).toContain('Descuento (10 %) -$ 300,00');
    expect(d).toContain('Recargo $ 270,00');
    expect(d).toContain('Total $ 2.970,00');
    expect(d).toContain('Crédito 1 pago $ 2.970,00');
    expect(d).toContain('Ana Vendedora');
  });

  it('el detalle dice si salió de un presupuesto o de un remito', async () => {
    await crear();
    api.obtener.mockReturnValue(of(ventaDePrueba({ remitoId: 4 })));

    filas()[0].click();
    await refrescar();

    expect(normal(detalle()!.textContent)).toContain('Salió de un remito');
  });

  it('el detalle solo ofrece Imprimir: ni Anular ni Borrar (GDS-15)', async () => {
    await crear();
    filas()[0].click();
    await refrescar();

    const acciones = Array.from(detalle()!.querySelectorAll('button')).map((b) => normal(b.textContent));
    expect(acciones).toEqual(['Imprimir']);
  });

  it('Imprimir abre la venta imprimible y Cerrar la cierra', async () => {
    await crear();
    filas()[0].click();
    await refrescar();

    boton('Imprimir', detalle()!)!.click();
    await refrescar();
    expect(el().querySelector('app-venta-imprimible article.venta-imprimible')).not.toBeNull();

    boton('Cerrar')!.click();
    await refrescar();
    expect(el().querySelector('app-venta-imprimible')).toBeNull();
  });

  it('si el detalle no carga lo avisa', async () => {
    await crear();
    api.obtener.mockReturnValue(throwError(() => new Error('caída')));

    filas()[0].click();
    await refrescar();

    expect(normal(detalle()!.querySelector('[role="alert"]')?.textContent)).toBe('No se pudo cargar la venta.');
  });
});
