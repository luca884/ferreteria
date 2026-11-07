import { HttpErrorResponse } from '@angular/common/http';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { CompraResponse, CompraResumen } from './compra.models';
import { Compras } from './compras';
import { ComprasService } from './compras.service';

registerLocaleData(localeEsAr);

describe('Compras', () => {
  let fixture: ComponentFixture<Compras>;
  let api: { listar: ReturnType<typeof vi.fn>; obtener: ReturnType<typeof vi.fn> };
  let proveedores: { listar: ReturnType<typeof vi.fn> };

  const resumen = (id: number, cambios: Partial<CompraResumen> = {}): CompraResumen => ({
    id,
    fecha: '2026-10-20',
    tipo: 'FA',
    comprobante: `Factura A 00003-0000000${id}`,
    proveedorId: 4,
    proveedorNombre: 'Bulonera Sur',
    moneda: 'ARS',
    neto: 2200,
    renglones: 2,
    ...cambios,
  });

  const pagina = (items: CompraResumen[], totalPages = 1): PageResponse<CompraResumen> => ({
    items,
    page: 0,
    size: 20,
    totalElements: items.length,
    totalPages,
  });

  const detalle = (id: number): CompraResponse => ({
    id,
    tipo: 'FA',
    puntoVenta: 3,
    numero: id,
    comprobante: `Factura A 00003-0000000${id}`,
    fecha: '2026-10-20',
    proveedorId: 4,
    proveedorNombre: 'Bulonera Sur',
    moneda: 'ARS',
    neto: 2200,
    registrada: '2026-10-21T10:15:00',
    recargos: [],
    renglones: [
      {
        renglon: 1,
        articuloId: 1,
        codigoInterno: 'TOR-1',
        descripcion: 'Tornillo 1/4',
        unidad: 'UNIDAD',
        cantidad: 20,
        costoUnitario: 110,
        subtotal: 2200,
        costoAnterior: 100,
        precioAnterior: 157.3,
        precioNuevo: 173.03,
      },
    ],
  });

  async function crear(items: CompraResumen[] = [resumen(2), resumen(1)], rol = 'DUENO', id: string | null = null, falla: number | null = null) {
    api = {
      listar: vi.fn(() => of(pagina(items))),
      obtener: vi.fn((id: number) => (falla ? throwError(() => new HttpErrorResponse({ status: falla })) : of(detalle(id)))),
    };
    proveedores = { listar: vi.fn(() => of([{ id: 4, nombre: 'Bulonera Sur', cuit: null, activo: true }])) };
    const sesion = signal({ userId: 7, nombre: 'Ana', email: 'ana@x.com', rol, ferreteriaId: 3, ferreteriaNombre: 'Tornillo' });
    await TestBed.configureTestingModule({
      imports: [Compras],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(id === null ? {} : { id }) } } },
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ComprasService, useValue: api },
        { provide: ProveedoresService, useValue: proveedores },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly(), esDueno: () => rol === 'DUENO' } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Compras);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;

  async function escribir(selector: string, valor: string) {
    const input = el().querySelector(selector) as HTMLInputElement | HTMLSelectElement;
    input.value = valor;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
    await refrescar();
  }

  it('al Vendedor le avisa que es del Dueño y no pide nada', async () => {
    await crear([], 'VENDEDOR');

    expect(el().textContent).toContain('Solo el Dueño puede ver las compras.');
    expect(api.listar).not.toHaveBeenCalled();
  });

  it('lista las compras con fecha, comprobante, proveedor, renglones y neto, y ofrece cargar una nueva', async () => {
    await crear();

    expect(api.listar).toHaveBeenCalledWith({ page: 0 });
    const filas = [...el().querySelectorAll('table.compras tbody tr')];
    expect(filas).toHaveLength(2);
    expect(filas[0].textContent).toContain('20/10/2026');
    expect(filas[0].textContent).toContain('Factura A 00003-00000002');
    expect(filas[0].textContent).toContain('Bulonera Sur');
    expect(filas[0].textContent).toContain('2.200,00');
    expect(el().querySelector('a[href="/compras/nueva"]')?.textContent).toContain('Nueva compra');
    // Botón de la barra, como «+ Nuevo» de Proveedores o Artículos (arreglos-compras, S3).
    expect([...el().querySelector('a[href="/compras/nueva"]')!.classList]).toEqual(
      expect.arrayContaining(['btn', 'accion-barra', 'primario']),
    );
  });

  it('filtra por proveedor y fechas, y limpiar vuelve a todas', async () => {
    await crear();
    await escribir('select[name=proveedor]', '4');
    await escribir('input[name=desde]', '2026-10-01');

    (el().querySelector('form.filtros') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await refrescar();
    expect(api.listar).toHaveBeenLastCalledWith({ page: 0, proveedorId: 4, desde: '2026-10-01' });

    [...el().querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Limpiar')!.click();
    await refrescar();
    expect(api.listar).toHaveBeenLastCalledWith({ page: 0 });
  });

  it('el paginador va arriba de la tabla: no hay que bajar hasta el final para pasar de página', async () => {
    await crear();
    api.listar.mockReturnValue(of(pagina([resumen(2), resumen(1)], 2)));
    (el().querySelector('form.filtros') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await refrescar();

    const paginador = el().querySelector('nav.paginas')!;
    const tabla = el().querySelector('table.compras')!;
    expect(paginador.compareDocumentPosition(tabla) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('elegir una compra muestra sus renglones con el costo y el precio de antes y de después', async () => {
    await crear();

    (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
    await refrescar();

    expect(api.obtener).toHaveBeenCalledWith(2);
    const detalleTexto = el().querySelector('app-dialogo-detalle [role=dialog]')!.textContent!;
    expect(detalleTexto).toContain('Factura A 00003-00000002');
    expect(detalleTexto).toContain('Tornillo 1/4');
    expect(detalleTexto).toContain('100,00');
    expect(detalleTexto).toContain('110,00');
    expect(detalleTexto).toContain('157,30');
    expect(detalleTexto).toContain('173,03');
  });

  it('sin compras avisa y si la carga falla ofrece reintentar', async () => {
    await crear([]);
    expect(el().textContent).toContain('Todavía no hay compras.');

    api.listar.mockReturnValueOnce(throwError(() => new Error('red')));
    (el().querySelector('form.filtros') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await refrescar();
    expect(el().querySelector('[role=alert]')?.textContent).toContain('No se pudieron cargar las compras.');
  });

  it('el detalle muestra los recargos de la compra con su porcentaje', async () => {
    await crear();
    api.obtener.mockReturnValue(of({ ...detalle(2), recargos: [{ nombre: 'Embalaje y carga', porcentaje: 4.44 }] }));

    (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
    await refrescar();

    expect(el().querySelector('app-dialogo-detalle [role=dialog] .recargos')!.textContent).toContain('Embalaje y carga 4,44 %');
  });

  it('con el parámetro id en la ruta abre el detalle de esa compra', async () => {
    await crear([resumen(2)], 'DUENO', '9');

    expect(api.obtener).toHaveBeenCalledWith(9);
    expect(el().querySelector('app-dialogo-detalle [role=dialog]')!.textContent).toContain('Factura A 00003-00000009');
  });

  it('con un id desconocido o de otra ferretería (404) dice que no se encontró la compra (QA 05/11)', async () => {
    await crear([resumen(2)], 'DUENO', '77', 404);

    expect(api.obtener).toHaveBeenCalledWith(77);
    expect(el().querySelector('app-dialogo-detalle [role=dialog] [role=alert]')!.textContent).toContain('No se encontró la compra.');
  });

  it('con otro error dice que no se pudo cargar la compra', async () => {
    await crear([resumen(2)], 'DUENO', '77', 500);

    expect(el().querySelector('app-dialogo-detalle [role=dialog] [role=alert]')!.textContent).toContain('No se pudo cargar la compra.');
  });

  describe('el detalle en un pop-up (detalle-en-popup)', () => {
    const popup = () => el().querySelector<HTMLElement>('app-dialogo-detalle [role="dialog"]');
    const escape = () =>
      (document.activeElement ?? document.body).dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );

    function espiarNavegacion() {
      return vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    }

    it('elegir una compra la abre en un pop-up encima de la lista, titulado con el comprobante y solo con Cerrar al pie', async () => {
      await crear();

      (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
      await refrescar();

      const pop = popup()!;
      expect(pop.getAttribute('aria-modal')).toBe('true');
      expect(pop.closest('.dialogo-fondo')).not.toBeNull();
      expect(pop.querySelector('.dialogo-detalle-cabecera h2')!.textContent!.trim()).toBe('Factura A 00003-00000002');
      expect(pop.querySelector('.dialogo-detalle-cuerpo table')).not.toBeNull();
      expect([...pop.querySelectorAll('.dialogo-detalle-pie button')].map((b) => b.textContent!.trim())).toEqual(['Cerrar']);
      expect(el().querySelectorAll('table.compras tbody tr')).toHaveLength(2);
      expect(document.activeElement).toBe(pop.querySelector('button[aria-label="Cerrar"]'));
    });

    it('mientras carga, el pop-up ya se titula con el comprobante de la fila', async () => {
      await crear();
      api.obtener.mockReturnValue(new Subject<CompraResponse>());

      (el().querySelectorAll('table.compras tbody tr button')[1] as HTMLButtonElement).click();
      await refrescar();

      expect(popup()!.querySelector('h2')!.textContent!.trim()).toBe('Factura A 00003-00000001');
      expect(popup()!.querySelector('.dialogo-detalle-cuerpo')!.textContent!.trim()).toBe('Cargando…');
    });

    it('abierta desde la lista, Esc y la ✕ la cierran sin cambiar de página', async () => {
      await crear();
      const navegar = espiarNavegacion();
      (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
      await refrescar();

      escape();
      await refrescar();
      expect(popup()).toBeNull();

      (el().querySelector('table.compras tbody tr button') as HTMLButtonElement).click();
      await refrescar();
      popup()!.querySelector<HTMLButtonElement>('button[aria-label="Cerrar"]')!.click();
      await refrescar();
      expect(popup()).toBeNull();
      expect(navegar).not.toHaveBeenCalled();
    });

    it('abierta por `/compras/:id` (la ficha del artículo), cerrarla vuelve a `/compras`', async () => {
      await crear([resumen(2)], 'DUENO', '9');
      const navegar = espiarNavegacion();
      expect(popup()).not.toBeNull();

      escape();
      await refrescar();

      expect(popup()).toBeNull();
      expect(navegar).toHaveBeenCalledWith('/compras');
    });
  });
});
