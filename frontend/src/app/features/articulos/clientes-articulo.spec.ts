import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Articulo, ClienteDelArticulo, ClientesDelArticulo } from './articulo.models';
import { ArticulosService } from './articulos.service';
import { ClientesArticulo } from './clientes-articulo';

describe('ClientesArticulo', () => {
  const cable = {
    id: 7,
    codigoInterno: 'CAB-001',
    descripcion: 'Cable 2,5 mm',
    unidad: 'METRO',
    stockActual: 100,
  } as Articulo;

  const clientes: ClienteDelArticulo[] = [
    { clienteId: 3, razonSocial: 'Carla Díaz', activo: false, compras: 1, cantidad: 12.5, ultimaCompra: '2026-04-01' },
    { clienteId: 1, razonSocial: 'Ana Ferrari', activo: true, compras: 2, cantidad: 6, ultimaCompra: '2026-03-01' },
  ];

  const pagina = (
    items: ClienteDelArticulo[],
    ventasSinCliente = 0,
    page = 0,
    totalPages = 1,
  ): ClientesDelArticulo => ({ items, page, size: 20, totalElements: items.length, totalPages, ventasSinCliente });

  let fixture: ComponentFixture<ClientesArticulo>;
  let servicio: { obtener: ReturnType<typeof vi.fn>; clientes: ReturnType<typeof vi.fn> };

  async function crear(dueno = true, listar = vi.fn(() => of(pagina(clientes, 2)))) {
    servicio = { obtener: vi.fn(() => of(cable)), clientes: listar };
    await TestBed.configureTestingModule({
      imports: [ClientesArticulo],
      providers: [
        provideRouter([]),
        { provide: ArticulosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '7' }) } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ClientesArticulo);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) => el().querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  const filas = () => Array.from(el().querySelectorAll('tbody tr'));
  const celdas = (fila: number) =>
    Array.from(filas()[fila].querySelectorAll('td')).map((c) => c.textContent?.replace(/\s+/g, ' ').trim());

  it('el título va en la barra y dice de qué artículo son', async () => {
    await crear();

    expect(texto('app-barra-acciones h1')).toBe('Clientes que lo compraron');
    expect(texto('.articulo')).toBe('CAB-001 · Cable 2,5 mm');
    expect(servicio.obtener).toHaveBeenCalledWith(7);
    expect(servicio.clientes).toHaveBeenCalledWith(7, 0);
    expect(el().querySelector('a.volver')!.getAttribute('href')).toBe('/articulos/7');
  });

  it('lista cada cliente con compras, cantidad con la unidad del artículo y última compra; marca los desactivados', async () => {
    await crear();

    expect(filas()).toHaveLength(2);
    expect(celdas(0)).toEqual(['Carla Díaz Desactivado', '1', '12,5 metros', '01/04/2026']);
    expect(celdas(1)).toEqual(['Ana Ferrari', '2', '6 metros', '01/03/2026']);
  });

  it('al pie dice cuántas ventas sin cliente tuvo el artículo', async () => {
    await crear();
    expect(texto('.sin-cliente')).toBe('Además hubo 2 ventas sin cliente (consumidor final).');
  });

  it('con una sola venta sin cliente lo dice en singular, y sin ninguna no dice nada', async () => {
    await crear(true, vi.fn(() => of(pagina(clientes, 1))));
    expect(texto('.sin-cliente')).toBe('Además hubo 1 venta sin cliente (consumidor final).');

    TestBed.resetTestingModule();
    await crear(true, vi.fn(() => of(pagina(clientes, 0))));
    expect(el().querySelector('.sin-cliente')).toBeNull();
  });

  it('sin clientes lo dice, y avisa si igual hubo ventas sin cliente', async () => {
    await crear(true, vi.fn(() => of(pagina([], 3))));

    expect(texto('.estado-vacio')).toBe('Ningún cliente compró este artículo todavía.');
    expect(texto('.sin-cliente')).toBe('Además hubo 3 ventas sin cliente (consumidor final).');
  });

  it('pagina con Anterior y Siguiente', async () => {
    await crear(true, vi.fn(() => of(pagina(clientes, 0, 0, 3))));

    expect(texto('.paginas span')).toBe('Página 1 de 3');
    el().querySelectorAll<HTMLButtonElement>('.paginas button')[1].click();
    await refrescar();

    expect(servicio.clientes).toHaveBeenLastCalledWith(7, 1);
  });

  it('si no carga lo avisa y Reintentar vuelve a pedir', async () => {
    const listar = vi
      .fn()
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })))
      .mockReturnValue(of(pagina(clientes)));
    await crear(true, listar);

    expect(texto('[role="alert"] span')).toBe('No se pudieron cargar los clientes.');
    el().querySelector<HTMLButtonElement>('button.reintentar')!.click();
    await refrescar();

    expect(listar).toHaveBeenCalledTimes(2);
    expect(filas()).toHaveLength(2);
  });

  it('un artículo de otra ferretería o inexistente (404) dice que no existe, sin Reintentar', async () => {
    await crear(true, vi.fn(() => throwError(() => new HttpErrorResponse({ status: 404 }))));

    expect(texto('[role="alert"] span')).toBe('El artículo no existe.');
    expect(el().querySelector('button.reintentar')).toBeNull();
  });

  it('el Vendedor ve el aviso y no se pide nada al servidor', async () => {
    await crear(false);

    expect(texto('.estado-vacio')).toBe('Los clientes que compraron el artículo son solo del Dueño.');
    expect(servicio.obtener).not.toHaveBeenCalled();
    expect(servicio.clientes).not.toHaveBeenCalled();
  });
});
