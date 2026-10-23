import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { ArticuloMostrador, BusquedaMostrador } from '../mostrador/mostrador.models';
import { MostradorService } from '../mostrador/mostrador.service';
import { barras, codigoParaEtiqueta } from './codigo-barras';
import { Etiquetas } from './etiquetas';

describe('Etiquetas', () => {
  const articulo = (id: number, extra: Partial<ArticuloMostrador> = {}): ArticuloMostrador => ({
    id,
    codigoInterno: `TOR-00${id}`,
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: `Tornillo ${id}`,
    precioFinal: 100,
    unidad: 'UNIDAD',
    stockActual: 5,
    stockMinimo: 1,
    ubicacion: null,
    activo: true,
    ...extra,
  });
  const tornillo = articulo(1, { codigoBarras: '4006381333931', descripcion: 'Tornillo hexagonal 1/4' });
  const tuerca = articulo(2, { descripcion: 'Tuerca 1/4' });
  const dadoDeBaja = articulo(3, { activo: false, descripcion: 'Tornillo viejo' });

  let fixture: ComponentFixture<Etiquetas>;
  let buscar: ReturnType<typeof vi.fn>;

  async function crear(dueno = true, respuesta: (q: string) => Observable<BusquedaMostrador> = () => of(lista())) {
    buscar = vi.fn(respuesta);
    await TestBed.configureTestingModule({
      imports: [Etiquetas],
      providers: [
        provideRouter([]),
        { provide: MostradorService, useValue: { buscar } },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Etiquetas);
    await refrescar();
  }

  const lista = (...items: ArticuloMostrador[]): BusquedaMostrador => ({
    tipo: items.length ? 'LISTA' : 'SIN_RESULTADOS',
    items,
    hayMas: false,
  });
  const exacto = (item: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [item], hayMas: false });

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = (selector: string) => el().querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  const buscador = () => el().querySelector<HTMLInputElement>('input[name="q"]')!;
  const etiquetas = () => Array.from(el().querySelectorAll('.etiquetas-imprimible .rotulo'));
  const filasLista = () => Array.from(el().querySelectorAll('.lista tbody tr'));
  const imprimir = () => el().querySelector<HTMLButtonElement>('button.imprimir')!;

  async function buscarTexto(q: string) {
    buscador().value = q;
    buscador().dispatchEvent(new Event('input'));
    buscador().form!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  async function agregarResultado(i: number) {
    el().querySelectorAll<HTMLButtonElement>('.resultados button.agregar')[i].click();
    await refrescar();
  }

  async function copias(fila: number, valor: string) {
    const input = filasLista()[fila].querySelector<HTMLInputElement>('input.copias')!;
    input.value = valor;
    input.dispatchEvent(new Event('change'));
    await refrescar();
  }

  afterEach(() => vi.restoreAllMocks());

  it('el título va en la barra; sin artículos no hay hoja e Imprimir está deshabilitado', async () => {
    await crear();

    expect(texto('app-barra-acciones h1')).toBe('Etiquetas');
    expect(imprimir().disabled).toBe(true);
    expect(el().querySelector('.etiquetas-imprimible')).toBeNull();
    expect(buscar).not.toHaveBeenCalled();
  });

  it('el Vendedor ve el aviso y no se pide nada al servidor', async () => {
    await crear(false);

    expect(texto('.estado-vacio')).toBe('Las etiquetas son solo del Dueño.');
    expect(el().querySelector('input[name="q"]')).toBeNull();
    expect(buscar).not.toHaveBeenCalled();
  });

  it('un código exacto (o escaneado) se suma directo a la lista y limpia el buscador', async () => {
    await crear(true, () => of(exacto(tornillo)));

    await buscarTexto(' 4006381333931 ');

    expect(buscar).toHaveBeenCalledWith('4006381333931');
    expect(filasLista()).toHaveLength(1);
    expect(texto('.lista tbody tr .descripcion')).toBe('Tornillo hexagonal 1/4');
    expect(buscador().value).toBe('');
    expect(el().querySelector('.resultados')).toBeNull();
  });

  it('una búsqueda con varios resultados los muestra para elegir; sumar el mismo dos veces suma una copia', async () => {
    await crear(true, () => of(lista(tornillo, tuerca)));

    await buscarTexto('tornillo');
    expect(Array.from(el().querySelectorAll('.resultados li .descripcion')).map((d) => d.textContent?.trim())).toEqual([
      'Tornillo hexagonal 1/4',
      'Tuerca 1/4',
    ]);

    await agregarResultado(1);
    await agregarResultado(1);

    expect(filasLista()).toHaveLength(1);
    expect(filasLista()[0].querySelector<HTMLInputElement>('input.copias')!.value).toBe('2');
    expect(etiquetas()).toHaveLength(2);
  });

  it('un artículo dado de baja no se puede sumar', async () => {
    await crear(true, () => of(lista(dadoDeBaja)));

    await buscarTexto('viejo');

    const boton = el().querySelector<HTMLButtonElement>('.resultados button.agregar')!;
    expect(boton.disabled).toBe(true);
    expect(texto('.resultados li .baja')).toBe('Dado de baja');
  });

  it('un código exacto dado de baja no se suma y avisa', async () => {
    await crear(true, () => of(exacto(dadoDeBaja)));

    await buscarTexto('TOR-003');

    expect(filasLista()).toHaveLength(0);
    expect(texto('.error')).toBe('«Tornillo viejo» está dado de baja.');
  });

  it('sin resultados lo dice', async () => {
    await crear(true, () => of(lista()));

    await buscarTexto('nada');

    expect(texto('.resultados-vacio')).toBe('No se encontraron artículos para «nada».');
  });

  it('si la búsqueda falla lo avisa', async () => {
    await crear(true, () => throwError(() => new Error('caído')));

    await buscarTexto('tornillo');

    expect(texto('.error')).toBe('No se pudo buscar el artículo.');
  });

  it('las copias definen cuántas etiquetas salen; un valor inválido vuelve a 1 y quitar saca el artículo', async () => {
    await crear(true, () => of(lista(tornillo, tuerca)));
    await buscarTexto('t');
    await agregarResultado(0);
    await agregarResultado(1);

    await copias(0, '3');
    expect(etiquetas()).toHaveLength(4);

    await copias(1, '0');
    expect(filasLista()[1].querySelector<HTMLInputElement>('input.copias')!.value).toBe('1');
    expect(etiquetas()).toHaveLength(4);

    filasLista()[0].querySelector<HTMLButtonElement>('button.quitar')!.click();
    await refrescar();
    expect(filasLista()).toHaveLength(1);
    expect(etiquetas()).toHaveLength(1);
    expect(texto('.total')).toBe('1 etiqueta');
  });

  it('cada etiqueta lleva las barras, el código y la descripción, sin precio', async () => {
    await crear(true, () => of(lista(tornillo, tuerca)));
    await buscarTexto('t');
    await agregarResultado(0);
    await agregarResultado(1);

    const [ean, code128] = etiquetas();
    const codigoEan = codigoParaEtiqueta(tornillo)!;
    expect(ean.querySelectorAll('svg rect')).toHaveLength(barras(codigoEan.modulos).length);
    expect(ean.querySelector('.codigo')!.textContent!.trim()).toBe('4006381333931');
    expect(ean.querySelector('.descripcion')!.textContent!.trim()).toBe('Tornillo hexagonal 1/4');

    const codigoInterno = codigoParaEtiqueta(tuerca)!;
    expect(code128.querySelectorAll('svg rect')).toHaveLength(barras(codigoInterno.modulos).length);
    expect(code128.querySelector('.codigo')!.textContent!.trim()).toBe('TOR-002');
    expect(el().querySelector('.etiquetas-imprimible')!.textContent).not.toMatch(/\$|100/);
    // `.etiqueta` es la pastilla global («Desactivado»): con ese nombre heredaba nowrap y fondo gris.
    expect(el().querySelector('.etiquetas-imprimible .etiqueta')).toBeNull();
  });

  it('en la lista dice qué código va en las barras', async () => {
    await crear(true, () => of(lista(tornillo, tuerca)));
    await buscarTexto('t');
    await agregarResultado(0);
    await agregarResultado(1);

    expect(filasLista().map((f) => f.querySelector('.barras')!.textContent!.replace(/\s+/g, ' ').trim())).toEqual([
      '4006381333931 (EAN-13)',
      'TOR-002 (Code128)',
    ]);
  });

  it('un código que no se puede dibujar sale en la etiqueta sin barras y la lista lo avisa', async () => {
    await crear(true, () => of(lista(articulo(4, { codigoInterno: 'CAÑO-1' }))));
    await buscarTexto('caño');
    await agregarResultado(0);

    expect(texto('.lista tbody tr .barras')).toBe('Sin barras: el código tiene caracteres que no se pueden dibujar');
    expect(etiquetas()[0].querySelector('svg')).toBeNull();
    expect(etiquetas()[0].querySelector('.codigo')!.textContent!.trim()).toBe('CAÑO-1');
  });

  it('se eligen 4 o 5 columnas para la hoja', async () => {
    await crear(true, () => of(exacto(tornillo)));
    await buscarTexto('TOR-001');

    const hoja = () => el().querySelector<HTMLElement>('.etiquetas-imprimible')!;
    expect(hoja().dataset['columnas']).toBe('4');

    const select = el().querySelector<HTMLSelectElement>('select[name="columnas"]')!;
    select.value = '5';
    select.dispatchEvent(new Event('change'));
    await refrescar();

    expect(hoja().dataset['columnas']).toBe('5');
  });

  it('Imprimir abre la impresión del navegador', async () => {
    await crear(true, () => of(exacto(tornillo)));
    await buscarTexto('TOR-001');
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined);

    expect(imprimir().disabled).toBe(false);
    imprimir().click();

    expect(print).toHaveBeenCalled();
  });
});
