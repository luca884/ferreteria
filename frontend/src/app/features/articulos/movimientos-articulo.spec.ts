import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { Articulo, MovimientoStock } from './articulo.models';
import { ArticulosService } from './articulos.service';
import { MovimientosArticulo } from './movimientos-articulo';

describe('MovimientosArticulo', () => {
  const tornillo = {
    id: 7,
    codigoInterno: 'TOR-001',
    descripcion: 'Tornillo 1/4',
    unidad: 'UNIDAD',
    stockActual: 11,
  } as Articulo;

  const movimientos: MovimientoStock[] = [
    { id: 4, fecha: '2026-10-23T10:15:00', tipo: 'AJUSTE', cantidad: -1, saldo: 11, comprobante: null, usuario: 'Ana', motivo: 'Rotura' },
    { id: 3, fecha: '2026-10-23T09:00:00', tipo: 'COMPRA', cantidad: 5, saldo: 12, comprobante: 'Factura A 00003-00001234 · Bulonera Sur', usuario: 'Ana', motivo: null },
    { id: 2, fecha: '2026-10-22T18:30:00', tipo: 'ANULACION_REMITO', cantidad: 1, saldo: 7, comprobante: 'Remito N.º 5', usuario: 'Beto', motivo: null },
    { id: 1, fecha: '2026-10-22T18:00:00', tipo: 'VENTA', cantidad: -2.5, saldo: 6, comprobante: 'Venta N.º 12', usuario: null, motivo: null },
  ];

  const pagina = (items: MovimientoStock[], page = 0, totalPages = 1): PageResponse<MovimientoStock> => ({
    items,
    page,
    size: 20,
    totalElements: items.length,
    totalPages,
  });

  let fixture: ComponentFixture<MovimientosArticulo>;
  let servicio: { obtener: ReturnType<typeof vi.fn>; movimientos: ReturnType<typeof vi.fn> };

  async function crear(
    dueno = true,
    listar = vi.fn(() => of(pagina(movimientos))),
    obtener: ReturnType<typeof vi.fn> = vi.fn(() => of(tornillo)),
  ) {
    servicio = { obtener, movimientos: listar };
    await TestBed.configureTestingModule({
      imports: [MovimientosArticulo],
      providers: [
        provideRouter([]),
        { provide: ArticulosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '7' }) } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MovimientosArticulo);
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
  const celdas = (fila: number) => Array.from(filas()[fila].querySelectorAll('td')).map((c) => c.textContent?.trim());

  async function fecha(nombre: 'desde' | 'hasta', valor: string) {
    const input = el().querySelector<HTMLInputElement>(`input[name="${nombre}"]`)!;
    input.value = valor;
    input.dispatchEvent(new Event('input'));
    await refrescar();
  }

  it('el título va en la barra y dice de qué artículo son, con su stock actual', async () => {
    await crear();

    expect(texto('app-barra-acciones h1')).toBe('Movimientos de stock');
    expect(texto('.articulo')).toBe('TOR-001 · Tornillo 1/4 — Stock actual: 11 unidades');
    expect(servicio.obtener).toHaveBeenCalledWith(7);
    expect(servicio.movimientos).toHaveBeenCalledWith(7, { page: 0 });
  });

  it('lista cada movimiento con fecha, tipo, comprobante, cantidad con signo, saldo, usuario y motivo', async () => {
    await crear();

    expect(filas()).toHaveLength(4);
    expect(celdas(0)).toEqual(['23/10/2026 10:15', 'Ajuste', '—', '-1 unidad', '11 unidades', 'Ana', 'Rotura']);
    expect(celdas(1)).toEqual(['23/10/2026 09:00', 'Compra', 'Factura A 00003-00001234 · Bulonera Sur', '+5 unidades', '12 unidades', 'Ana', '—']);
    expect(celdas(2)[1]).toBe('Anulación de remito');
    expect(celdas(3)).toEqual(['22/10/2026 18:00', 'Venta', 'Venta N.º 12', '-2,5 unidades', '6 unidades', '—', '—']);
  });

  it('la cantidad y el saldo llevan la unidad del artículo: «+20 kilos», «18,5 kilos» (#36)', async () => {
    const clavo = { ...tornillo, descripcion: 'Clavo punta París 2"', unidad: 'KILO', stockActual: 18.5 } as Articulo;
    const deKilos: MovimientoStock[] = [
      { id: 2, fecha: '2026-11-08T12:10:00', tipo: 'VENTA', cantidad: -1.5, saldo: 18.5, comprobante: 'Venta N.º 29', usuario: 'Ana', motivo: null },
      { id: 1, fecha: '2026-11-08T12:00:00', tipo: 'COMPRA', cantidad: 20, saldo: 20, comprobante: 'Factura A 00007-00009101', usuario: 'Ana', motivo: null },
    ];
    await crear(true, vi.fn(() => of(pagina(deKilos))), vi.fn(() => of(clavo)));

    expect(celdas(0).slice(3, 5)).toEqual(['-1,5 kilos', '18,5 kilos']);
    expect(celdas(1).slice(3, 5)).toEqual(['+20 kilos', '20 kilos']);
  });

  it('si el artículo no carga, la cantidad y el saldo van sin unidad, como antes', async () => {
    await crear(true, undefined, vi.fn(() => throwError(() => new Error('red'))));

    expect(celdas(0).slice(3, 5)).toEqual(['-1', '11']);
    expect(celdas(1).slice(3, 5)).toEqual(['+5', '12']);
  });

  it('la nota de crédito y la anulación interna (CU-08) se nombran «Nota de crédito», con el comprobante del servidor', async () => {
    await crear(true, vi.fn(() => of(pagina([
      { id: 6, fecha: '2026-10-24T11:00:00', tipo: 'NOTA_CREDITO', cantidad: 2, saldo: 13, comprobante: 'Nota de crédito N.º 14', usuario: 'Ana', motivo: null },
      { id: 5, fecha: '2026-10-24T10:00:00', tipo: 'NOTA_CREDITO', cantidad: 1, saldo: 11, comprobante: 'Anulación N.º 13', usuario: 'Ana', motivo: null },
    ]))));

    expect(celdas(0)).toEqual(['24/10/2026 11:00', 'Nota de crédito', 'Nota de crédito N.º 14', '+2 unidades', '13 unidades', 'Ana', '—']);
    expect(celdas(1).slice(1, 3)).toEqual(['Nota de crédito', 'Anulación N.º 13']);
  });

  it('marca las salidas y las entradas para leerlas de un vistazo', async () => {
    await crear();

    expect(filas()[0].querySelector('td.cantidad')!.classList).toContain('salida');
    expect(filas()[1].querySelector('td.cantidad')!.classList).toContain('entrada');
  });

  it('con «Hasta» antes de «Desde» avisa, no busca y enfoca «Hasta»; corregido, busca (bugs-relevamiento-celular, S4)', async () => {
    await crear();
    servicio.movimientos.mockClear();
    await fecha('desde', '2026-10-22');
    await fecha('hasta', '2026-10-21');

    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await refrescar();

    expect(servicio.movimientos).not.toHaveBeenCalled();
    expect(texto('[role="alert"]')).toBe('La fecha «Hasta» no puede ser anterior a «Desde».');
    expect(document.activeElement).toBe(el().querySelector('input[name="hasta"]'));

    await fecha('hasta', '2026-10-22');
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await refrescar();

    expect(servicio.movimientos).toHaveBeenLastCalledWith(7, { page: 0, desde: '2026-10-22', hasta: '2026-10-22' });
    expect(el().textContent).not.toContain('no puede ser anterior');
  });

  it('filtra por Desde y Hasta y vuelve a la primera página; Limpiar saca los filtros', async () => {
    await crear();
    await fecha('desde', '2026-10-01');
    await fecha('hasta', '2026-10-22');

    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await refrescar();
    expect(servicio.movimientos).toHaveBeenLastCalledWith(7, { page: 0, desde: '2026-10-01', hasta: '2026-10-22' });

    el().querySelector<HTMLButtonElement>('button.limpiar')!.click();
    await refrescar();
    expect(servicio.movimientos).toHaveBeenLastCalledWith(7, { page: 0 });
  });

  it('pagina con Anterior y Siguiente', async () => {
    await crear(true, vi.fn(() => of(pagina(movimientos, 0, 3))));

    expect(texto('.paginas span')).toBe('Página 1 de 3');
    el().querySelectorAll<HTMLButtonElement>('.paginas button')[1].click();
    await refrescar();

    expect(servicio.movimientos).toHaveBeenLastCalledWith(7, { page: 1 });
  });

  it('el paginador va arriba de la tabla: no hay que bajar hasta el final para pasar de página', async () => {
    await crear(true, vi.fn(() => of(pagina(movimientos, 0, 3))));

    const paginador = el().querySelector('nav.paginas')!;
    const tabla = el().querySelector('table.movimientos')!;
    expect(paginador.compareDocumentPosition(tabla) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('sin movimientos lo dice, con o sin filtros', async () => {
    await crear(true, vi.fn(() => of(pagina([]))));
    expect(texto('.estado-vacio')).toBe('Este artículo todavía no tiene movimientos de stock.');

    await fecha('desde', '2026-10-01');
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await refrescar();
    expect(texto('.estado-vacio')).toBe('No hay movimientos entre esas fechas.');
  });

  it('si no carga lo avisa y Reintentar vuelve a pedir', async () => {
    const listar = vi
      .fn()
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })))
      .mockReturnValue(of(pagina(movimientos)));
    await crear(true, listar);

    expect(texto('[role="alert"] span')).toBe('No se pudieron cargar los movimientos.');
    el().querySelector<HTMLButtonElement>('button.reintentar')!.click();
    await refrescar();

    expect(filas()).toHaveLength(4);
  });

  it('un artículo de otra ferretería o inexistente (404) dice que no existe, sin Reintentar', async () => {
    await crear(true, vi.fn(() => throwError(() => new HttpErrorResponse({ status: 404 }))));

    expect(texto('[role="alert"] span')).toBe('El artículo no existe.');
    expect(el().querySelector('button.reintentar')).toBeNull();
  });

  it('al Vendedor le avisa que es del Dueño y no pide nada', async () => {
    await crear(false);

    expect(texto('.estado-vacio')).toBe('Los movimientos de stock son solo del Dueño.');
    expect(servicio.movimientos).not.toHaveBeenCalled();
  });

  it('tiene un enlace para volver a la ficha del artículo', async () => {
    await crear();

    expect(el().querySelector('a.volver')?.getAttribute('href')).toBe('/articulos/7');
  });
});
