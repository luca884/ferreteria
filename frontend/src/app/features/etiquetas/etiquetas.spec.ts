import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Sesion } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { Articulo } from '../articulos/articulo.models';
import { ArticulosService } from '../articulos/articulos.service';
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
  let obtener: ReturnType<typeof vi.fn>;

  const sesion = (userId = 7): Sesion => ({
    userId,
    nombre: 'Dueño',
    email: 'dueno@tornillo.demo',
    rol: 'DUENO',
    ferreteriaId: 1,
    ferreteriaNombre: 'El Tornillo',
  });
  /** La ficha del artículo como la devuelve el servidor (lo que la pantalla usa de ella). */
  const ficha = (a: ArticuloMostrador): Articulo => ({ ...a, oferta: null }) as unknown as Articulo;
  const noExiste = () => throwError(() => new HttpErrorResponse({ status: 404 }));

  async function crear(
    dueno = true,
    respuesta: (q: string) => Observable<BusquedaMostrador> = () => of(lista()),
    opciones: { userId?: number; obtener?: (id: number) => Observable<Articulo> } = {},
  ) {
    buscar = vi.fn(respuesta);
    obtener = vi.fn(opciones.obtener ?? ((id: number) => of(ficha(articulo(id)))));
    await TestBed.configureTestingModule({
      imports: [Etiquetas],
      providers: [
        provideRouter([]),
        { provide: MostradorService, useValue: { buscar } },
        { provide: ArticulosService, useValue: { obtener } },
        { provide: AuthService, useValue: { esDueno: signal(dueno), sesion: signal(sesion(opciones.userId)) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Etiquetas);
    await refrescar();
  }

  /** Sale de la pantalla y vuelve a entrar (otra instancia del componente, lo guardado queda). */
  async function volver(opciones: { userId?: number; obtener?: (id: number) => Observable<Articulo> } = {}) {
    fixture.destroy();
    TestBed.resetTestingModule();
    await crear(true, () => of(lista()), opciones);
  }

  const guardado = (clave = 'etiquetas:1:7') => {
    const raw = localStorage.getItem(clave);
    return raw ? JSON.parse(raw).items : null;
  };
  const guardar = (items: Array<{ id: number; copias: number }>, clave = 'etiquetas:1:7') =>
    localStorage.setItem(clave, JSON.stringify({ v: 1, items }));

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

  const vaciar = () => el().querySelector<HTMLButtonElement>('button.vaciar')!;

  beforeEach(() => localStorage.clear());
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

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

  it('tras buscar por descripción, y al sumar un resultado, el texto queda seleccionado para escribir otra búsqueda encima', async () => {
    await crear(true, () => of(lista(tornillo, tuerca)));
    document.body.appendChild(fixture.nativeElement);
    const seleccionado = () =>
      document.activeElement === buscador() &&
      buscador().selectionStart === 0 &&
      buscador().selectionEnd === buscador().value.length;

    try {
      await buscarTexto('tornillo');
      expect(buscador().value).toBe('tornillo');
      expect(seleccionado()).toBe(true);

      await agregarResultado(0);
      expect(seleccionado()).toBe(true);
      expect(el().querySelector('.resultados')).not.toBeNull();
    } finally {
      fixture.nativeElement.remove();
    }
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

  describe('la lista se guarda (#24)', () => {
    it('lo que se suma queda guardado y vuelve al entrar otra vez, con los datos actuales del artículo', async () => {
      await crear(true, () => of(exacto(tornillo)));
      await buscarTexto('4006381333931');
      await copias(0, '3');
      expect(guardado()).toEqual([{ id: 1, copias: 3 }]);

      await volver({ obtener: () => of(ficha({ ...tornillo, descripcion: 'Tornillo hexagonal 1/4 zincado' })) });

      expect(obtener).toHaveBeenCalledWith(1);
      expect(filasLista()).toHaveLength(1);
      expect(texto('.lista tbody tr .descripcion')).toBe('Tornillo hexagonal 1/4 zincado');
      expect(filasLista()[0].querySelector<HTMLInputElement>('input.copias')!.value).toBe('3');
      expect(etiquetas()).toHaveLength(3);
      expect(etiquetas()[0].querySelector('.codigo')!.textContent!.trim()).toBe('4006381333931');
    });

    it('quitar un artículo también queda guardado', async () => {
      await crear(true, () => of(lista(tornillo, tuerca)));
      await buscarTexto('t');
      await agregarResultado(0);
      await agregarResultado(1);

      filasLista()[0].querySelector<HTMLButtonElement>('button.quitar')!.click();
      await refrescar();

      expect(guardado()).toEqual([{ id: 2, copias: 1 }]);
    });

    it('otro usuario no ve la lista guardada', async () => {
      guardar([{ id: 1, copias: 2 }]);

      await crear(true, () => of(lista()), { userId: 8 });

      expect(obtener).not.toHaveBeenCalled();
      expect(filasLista()).toHaveLength(0);
      expect(guardado()).toEqual([{ id: 1, copias: 2 }]);
    });

    it('un artículo dado de baja o que ya no existe sale de la lista y se avisa', async () => {
      guardar([
        { id: 1, copias: 2 },
        { id: 3, copias: 1 },
        { id: 9, copias: 4 },
      ]);

      await crear(true, () => of(lista()), {
        obtener: (id) => (id === 9 ? noExiste() : of(ficha(id === 1 ? tornillo : dadoDeBaja))),
      });

      expect(filasLista()).toHaveLength(1);
      expect(texto('.lista tbody tr .descripcion')).toBe('Tornillo hexagonal 1/4');
      expect(texto('.aviso-lista')).toBe(
        'Salió de la lista por estar dado de baja: «Tornillo viejo». Salió de la lista 1 artículo que ya no existe.',
      );
      expect(guardado()).toEqual([{ id: 1, copias: 2 }]);
    });

    it('si no se pudo cargar la lista guardada lo avisa, no la pisa y se puede reintentar', async () => {
      guardar([{ id: 1, copias: 2 }]);
      let falla = true;
      await crear(true, () => of(exacto(tuerca)), {
        obtener: () => (falla ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(ficha(tornillo))),
      });

      expect(texto('.error-lista')).toBe('No se pudo cargar la lista guardada. Reintentar');
      expect(filasLista()).toHaveLength(0);

      await buscarTexto('TOR-002');
      expect(filasLista()).toHaveLength(1);
      expect(guardado()).toEqual([{ id: 1, copias: 2 }]);

      falla = false;
      el().querySelector<HTMLButtonElement>('.error-lista button')!.click();
      await refrescar();

      expect(el().querySelector('.error-lista')).toBeNull();
      expect(Array.from(el().querySelectorAll('.lista tbody tr .descripcion')).map((d) => d.textContent?.trim())).toEqual([
        'Tornillo hexagonal 1/4',
        'Tuerca 1/4',
      ]);
      expect(guardado()).toEqual([
        { id: 1, copias: 2 },
        { id: 2, copias: 1 },
      ]);
    });

    it('«Vaciar» saca todo y borra lo guardado; sin artículos está deshabilitado', async () => {
      await crear(true, () => of(lista(tornillo, tuerca)));
      expect(vaciar().disabled).toBe(true);
      await buscarTexto('t');
      await agregarResultado(0);
      await agregarResultado(1);

      expect(vaciar().disabled).toBe(false);
      vaciar().click();
      await refrescar();

      expect(filasLista()).toHaveLength(0);
      expect(el().querySelector('.etiquetas-imprimible')).toBeNull();
      expect(localStorage.getItem('etiquetas:1:7')).toBeNull();
      expect(vaciar().disabled).toBe(true);
    });

    it('Imprimir no vacía la lista', async () => {
      await crear(true, () => of(exacto(tornillo)));
      await buscarTexto('TOR-001');
      vi.spyOn(window, 'print').mockImplementation(() => undefined);

      imprimir().click();
      await refrescar();

      expect(filasLista()).toHaveLength(1);
      expect(guardado()).toEqual([{ id: 1, copias: 1 }]);
    });
  });
});
