import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { Articulo } from './articulo.models';
import { Articulos } from './articulos';
import { ArticulosService } from './articulos.service';

registerLocaleData(localeEsAr);

describe('Articulos', () => {
  const articulo = (id: number, cambios: Partial<Articulo> = {}): Articulo => ({
    id,
    codigoInterno: `COD-${id}`,
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: `Artículo ${id}`,
    rubroId: null,
    proveedorId: null,
    costo: 100,
    margenPorcentaje: 35,
    alicuotaIva: 'IVA_21',
    precioFinal: 170,
    unidad: 'UNIDAD',
    stockActual: 5,
    stockMinimo: 0,
    ubicacion: null,
    activo: true,
    ...cambios,
  });

  const pagina = (
    items: Articulo[],
    page = 0,
    totalPages = 1,
    totalElements = items.length,
  ): PageResponse<Articulo> => ({ items, page, size: 20, totalElements, totalPages });

  let fixture: ComponentFixture<Articulos>;
  let esDueno: ReturnType<typeof signal<boolean>>;
  let servicio: {
    buscar: ReturnType<typeof vi.fn>;
    desactivar: ReturnType<typeof vi.fn>;
  };

  const errorHttp = (status: number, message: string) =>
    new HttpErrorResponse({ status, error: { status, message } });

  async function crearComponente(dueno: boolean, primera = pagina([articulo(1), articulo(2)])) {
    esDueno = signal(dueno);
    servicio = {
      buscar: vi.fn(() => of(primera)),
      desactivar: vi.fn(() => of(undefined)),
    };
    await TestBed.configureTestingModule({
      imports: [Articulos],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ArticulosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Articulos);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const filas = () => Array.from(el().querySelectorAll('tbody tr'));
  const texto = (selector: string, fila = 0) =>
    (filas()[fila].querySelector(selector)?.textContent ?? '').trim();

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function buscarTexto(valor: string) {
    const input = el().querySelector<HTMLInputElement>('#q')!;
    input.value = valor;
    input.dispatchEvent(new Event('input'));
    el().querySelector('form.busqueda')!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  async function clickEn(selector: string, indice = 0) {
    el().querySelectorAll<HTMLButtonElement>(selector)[indice].click();
    await refrescar();
  }

  it('carga la primera página al iniciar, sin búsqueda ni inactivos', async () => {
    await crearComponente(true);

    expect(servicio.buscar).toHaveBeenCalledTimes(1);
    expect(servicio.buscar).toHaveBeenCalledWith('', 0, 20, false);
    expect(filas()).toHaveLength(2);
  });

  it('muestra código, descripción, costo, margen, IVA, precio final y stock con su unidad', async () => {
    await crearComponente(
      false,
      pagina([
        articulo(1, {
          codigoInterno: 'TOR-001',
          descripcion: 'Tornillo hexagonal 1/4',
          costo: 100,
          margenPorcentaje: 35,
          alicuotaIva: 'IVA_10_5',
          precioFinal: 170,
          unidad: 'METRO',
          stockActual: 12.5,
        }),
      ]),
    );

    expect(texto('.codigo')).toBe('TOR-001');
    expect(texto('.descripcion')).toBe('Tornillo hexagonal 1/4');
    expect(texto('.costo')).toBe('100,00');
    expect(texto('.margen')).toBe('35 %');
    expect(texto('.iva')).toBe('10,5 %');
    expect(texto('.precio')).toBe('170,00');
    expect(texto('.stock')).toBe('12,5 Metro');
  });

  it('indica que hereda el margen cuando el artículo no tiene uno propio', async () => {
    await crearComponente(true, pagina([articulo(1, { margenPorcentaje: null })]));

    expect(texto('.margen')).toBe('Heredado');
  });

  it('el Vendedor ve costo y margen', async () => {
    await crearComponente(false);

    expect(el().textContent).toContain('Costo');
    expect(el().textContent).toContain('Margen');
    expect(texto('.costo')).toBe('100,00');
    expect(texto('.margen')).toBe('35 %');
  });

  it('avisa cuando no hay artículos', async () => {
    await crearComponente(true, pagina([], 0, 0, 0));

    expect(el().querySelector('table')).toBeNull();
    expect(el().textContent).toContain('Todavía no hay artículos.');
  });

  it('avisa cuando la búsqueda no encuentra nada', async () => {
    await crearComponente(true);
    servicio.buscar.mockReturnValue(of(pagina([], 0, 0, 0)));

    await buscarTexto('zzz');

    expect(el().textContent).toContain('No hay artículos que coincidan con «zzz».');
  });

  it('busca con Enter sin espacios sobrantes y vuelve a la primera página', async () => {
    await crearComponente(true, pagina([articulo(1)], 0, 3, 45));
    servicio.buscar.mockReturnValue(of(pagina([articulo(3)], 1, 3, 45)));
    await clickEn('button.siguiente');
    expect(servicio.buscar).toHaveBeenLastCalledWith('', 1, 20, false);

    servicio.buscar.mockReturnValue(of(pagina([articulo(9)], 0, 1, 1)));
    await buscarTexto('  torni ');

    expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 0, 20, false);
    expect(texto('.codigo')).toBe('COD-9');
  });

  it('pagina hacia adelante y hacia atrás conservando la búsqueda', async () => {
    await crearComponente(true, pagina([articulo(1)], 0, 3, 45));
    servicio.buscar.mockReturnValue(of(pagina([articulo(5)], 0, 3, 45)));
    await buscarTexto('torni');
    servicio.buscar.mockReturnValue(of(pagina([articulo(21)], 1, 3, 45)));

    await clickEn('button.siguiente');
    expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 1, 20, false);
    expect(texto('.codigo')).toBe('COD-21');

    servicio.buscar.mockReturnValue(of(pagina([articulo(5)], 0, 3, 45)));
    await clickEn('button.anterior');
    expect(servicio.buscar).toHaveBeenLastCalledWith('torni', 0, 20, false);
    expect(texto('.codigo')).toBe('COD-5');
  });

  it('muestra en qué página está y deshabilita Anterior y Siguiente en los extremos', async () => {
    await crearComponente(true, pagina([articulo(1)], 0, 3, 45));
    const anterior = () => el().querySelector<HTMLButtonElement>('button.anterior')!;
    const siguiente = () => el().querySelector<HTMLButtonElement>('button.siguiente')!;

    expect(el().querySelector('.paginacion')?.textContent).toContain('Página 1 de 3');
    expect(el().querySelector('.paginacion')?.textContent).toContain('45 artículos');
    expect(anterior().disabled).toBe(true);
    expect(siguiente().disabled).toBe(false);

    servicio.buscar.mockReturnValue(of(pagina([articulo(41)], 2, 3, 45)));
    await clickEn('button.siguiente');
    servicio.buscar.mockReturnValue(of(pagina([articulo(41)], 2, 3, 45)));

    expect(el().querySelector('.paginacion')?.textContent).toContain('Página 3 de 3');
    expect(anterior().disabled).toBe(false);
    expect(siguiente().disabled).toBe(true);
  });

  it('no muestra la paginación cuando no hay artículos', async () => {
    await crearComponente(true, pagina([], 0, 0, 0));

    expect(el().querySelector('.paginacion')).toBeNull();
  });

  it('incluye los dados de baja al tildar la opción y los marca', async () => {
    await crearComponente(true);
    servicio.buscar.mockReturnValue(
      of(pagina([articulo(1), articulo(2, { activo: false })], 0, 1, 2)),
    );

    const check = el().querySelector<HTMLInputElement>('#inactivos')!;
    check.checked = true;
    check.dispatchEvent(new Event('change'));
    await refrescar();

    expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, true);
    expect(texto('.estado', 0)).toBe('Activo');
    expect(texto('.estado', 1)).toBe('De baja');
    expect(filas()[1].querySelector('button.desactivar')).toBeNull();
    expect(filas()[0].querySelector('button.desactivar')).not.toBeNull();
  });

  it('el Dueño ve el botón Nuevo y la acción Dar de baja', async () => {
    await crearComponente(true);

    const nuevo = el().querySelector<HTMLAnchorElement>('a.nuevo');
    expect(nuevo).not.toBeNull();
    expect(nuevo!.textContent).toContain('Nuevo artículo');
    expect(nuevo!.getAttribute('href')).toBe('/articulos/nuevo');
    expect(el().querySelectorAll('button.desactivar')).toHaveLength(2);
    expect(el().textContent).toContain('Dar de baja');
  });

  it('el Dueño ve Editar en cada fila, con el enlace al formulario del artículo', async () => {
    await crearComponente(true, pagina([articulo(7), articulo(8, { activo: false })]));

    const editar = Array.from(el().querySelectorAll<HTMLAnchorElement>('a.editar'));
    expect(editar).toHaveLength(2);
    expect(editar[0].textContent).toContain('Editar');
    expect(editar[0].getAttribute('href')).toBe('/articulos/7');
    expect(editar[1].getAttribute('href')).toBe('/articulos/8');
  });

  it('el Vendedor no ve Editar', async () => {
    await crearComponente(false);

    expect(el().querySelector('a.editar')).toBeNull();
    expect(el().textContent).not.toContain('Editar');
  });

  it('el Vendedor no ve Nuevo ni Dar de baja', async () => {
    await crearComponente(false);

    expect(el().querySelector('a.nuevo')).toBeNull();
    expect(el().querySelector('button.desactivar')).toBeNull();
    expect(el().textContent).not.toContain('Dar de baja');
    expect(el().textContent).not.toContain('Acciones');
  });

  it('da de baja con confirmación y recarga la página actual', async () => {
    await crearComponente(true);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    servicio.buscar.mockReturnValue(of(pagina([articulo(2)], 0, 1, 1)));

    await clickEn('button.desactivar', 0);

    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('Artículo 1'));
    expect(servicio.desactivar).toHaveBeenCalledWith(1);
    expect(servicio.buscar).toHaveBeenLastCalledWith('', 0, 20, false);
    expect(filas()).toHaveLength(1);
    expect(texto('.codigo')).toBe('COD-2');
  });

  it('no da de baja si se cancela la confirmación', async () => {
    await crearComponente(true);
    vi.spyOn(window, 'confirm').mockReturnValue(false);

    await clickEn('button.desactivar', 0);

    expect(servicio.desactivar).not.toHaveBeenCalled();
    expect(servicio.buscar).toHaveBeenCalledTimes(1);
  });

  it('si la baja deja vacía la última página, vuelve a la anterior', async () => {
    await crearComponente(true, pagina([articulo(1)], 0, 2, 21));
    servicio.buscar.mockReturnValue(of(pagina([articulo(21)], 1, 2, 21)));
    await clickEn('button.siguiente');
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    servicio.buscar.mockReset();
    servicio.buscar
      .mockReturnValueOnce(of(pagina([], 1, 1, 20)))
      .mockReturnValueOnce(of(pagina([articulo(1)], 0, 1, 20)));

    await clickEn('button.desactivar', 0);

    expect(servicio.buscar).toHaveBeenNthCalledWith(1, '', 1, 20, false);
    expect(servicio.buscar).toHaveBeenNthCalledWith(2, '', 0, 20, false);
    expect(texto('.codigo')).toBe('COD-1');
  });

  it('muestra el mensaje del backend si falla la baja', async () => {
    await crearComponente(true);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    servicio.desactivar.mockReturnValue(throwError(() => errorHttp(404, 'Artículo no encontrado')));

    await clickEn('button.desactivar', 0);

    expect(el().querySelector('.error')?.textContent).toContain('Artículo no encontrado');
    expect(filas()).toHaveLength(2);
  });

  it('usa un mensaje genérico si la baja falla sin mensaje del backend', async () => {
    await crearComponente(true);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    servicio.desactivar.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    await clickEn('button.desactivar', 0);

    expect(el().querySelector('.error')?.textContent).toContain(
      'No se pudo dar de baja el artículo.',
    );
  });

  it('muestra el mensaje del backend si falla la carga', async () => {
    esDueno = signal(true);
    servicio = {
      buscar: vi.fn(() => throwError(() => errorHttp(500, 'Error interno'))),
      desactivar: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [Articulos],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ArticulosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Articulos);
    await refrescar();

    expect(el().querySelector('.error')?.textContent).toContain('Error interno');
    expect(el().querySelector('table')).toBeNull();
  });

  it('usa un mensaje genérico si la carga falla sin mensaje del backend', async () => {
    esDueno = signal(false);
    servicio = {
      buscar: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 0 }))),
      desactivar: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [Articulos],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ArticulosService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Articulos);
    await refrescar();

    expect(el().querySelector('.error')?.textContent).toContain(
      'No se pudieron cargar los artículos.',
    );
  });
});
