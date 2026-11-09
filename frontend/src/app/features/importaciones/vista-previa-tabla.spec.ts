import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AltaPlaneada, CambioPlaneado, FilaSinCambio, VistaPrevia, Ajustes } from './importacion.models';
import {
  FilaDeVista,
  Seleccion,
  claveDe,
  marcarFila,
  marcarNinguna,
  seleccionInicial,
  unirFilas,
} from './seleccion-filas';
import { FILAS_POR_PAGINA, VistaPreviaTabla } from './vista-previa-tabla';

registerLocaleData(localeEsAr);

const NINGUNO: Ajustes = { a1: null, a2: null, a3: null };

function cambio(hoja: number, fila: number, anterior = 100, nuevo = 120, parcial: Partial<CambioPlaneado> = {}): CambioPlaneado {
  return {
    fila,
    hoja,
    articuloId: hoja * 100_000 + fila,
    codigoProveedor: `C-${hoja}-${fila}`,
    descripcion: `Artículo ${hoja}/${fila}`,
    precioLista: nuevo,
    costoAnterior: anterior,
    costoNuevo: nuevo,
    precioAnterior: anterior * 1.5,
    precioNuevo: nuevo * 1.5,
    activo: true,
    variacionFuerte: false,
    monedaAnterior: 'ARS',
    monedaNueva: 'ARS',
    alicuotaAnterior: 'IVA_21',
    alicuotaNueva: 'IVA_21',
    ajustesAnteriores: NINGUNO,
    ajustesNuevos: NINGUNO,
    unidad: 'UNIDAD',
    ...parcial,
  };
}

function alta(hoja: number, fila: number, parcial: Partial<AltaPlaneada> = {}): AltaPlaneada {
  return {
    fila,
    hoja,
    codigo: `N-${hoja}-${fila}`,
    descripcion: `Nuevo ${hoja}/${fila}`,
    precioLista: 20,
    costo: 18,
    moneda: 'ARS',
    alicuotaIva: 'IVA_21',
    ajustes: NINGUNO,
    unidad: 'UNIDAD',
    codigoBarras: null,
    rubroId: null,
    precioNuevo: 30,
    ...parcial,
  };
}

function igual(hoja: number, fila: number): FilaSinCambio {
  return { id: hoja * 100_000 + fila, hoja, fila, codigo: `S-${hoja}-${fila}`, descripcion: `Igual ${hoja}/${fila}` };
}

function vistaPrevia(parcial: Partial<VistaPrevia>): VistaPrevia {
  return {
    conteos: {
      suben: 0,
      bajan: 0,
      nuevos: 0,
      sinCambio: 0,
      noEstan: 0,
      conError: 0,
      cambianMoneda: 0,
      corrigen: 0,
      salteadas: 0,
    },
    cambios: [],
    nuevos: [],
    errores: [],
    advertencias: [],
    noEstan: [],
    cambiosDeMoneda: [],
    correcciones: [],
    sinCambio: [],
    ...parcial,
  };
}

const COSTOS = ['Costo anterior', 'Costo nuevo', 'Precio anterior', 'Precio nuevo'];

describe('VistaPreviaTabla', () => {
  let fixture: ComponentFixture<VistaPreviaTabla>;
  let emitidas: Seleccion[];

  async function crear(filas: FilaDeVista[], hojas = ['Lista'], seleccion: Seleccion = seleccionInicial(filas)) {
    await TestBed.configureTestingModule({
      imports: [VistaPreviaTabla],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    }).compileComponents();
    fixture = TestBed.createComponent(VistaPreviaTabla);
    fixture.componentRef.setInput('filas', filas);
    fixture.componentRef.setInput('hojas', hojas);
    fixture.componentRef.setInput('seleccion', seleccion);
    emitidas = [];
    fixture.componentInstance.seleccionCambia.subscribe((s) => emitidas.push(s));
    await asentar();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends Element = HTMLElement>(selector: string) => el().querySelector<T>(selector)!;
  const texto = (e: Element | null | undefined) => e?.textContent?.replace(/\s+/g, ' ').trim();

  async function asentar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const filasDeTabla = () => Array.from(el().querySelectorAll('tbody tr'));
  const celdas = (tr: Element) => Array.from(tr.querySelectorAll('td')).map((td) => texto(td));
  const encabezados = () => Array.from(el().querySelectorAll('thead th')).map((th) => texto(th));
  /** El texto de la celda de la columna con ese encabezado (la columna «Hoja» solo existe con varias hojas). */
  const valor = (tr: Element, columna: string) => {
    const indice = encabezados().indexOf(columna);
    expect(indice, `columna ${columna}`).toBeGreaterThanOrEqual(0);
    return celdas(tr)[indice];
  };
  const valores = (tr: Element, columnas: string[]) => columnas.map((c) => valor(tr, c));
  const casillaDe = (tr: Element) => tr.querySelector<HTMLInputElement>('input[type="checkbox"]');
  const boton = (t: string) => Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.trim() === t)!;

  async function elegirFiltro(id: string, textoDeLaOpcion: string) {
    const select = q<HTMLSelectElement>(id);
    const opcion = Array.from(select.options).find((o) => texto(o)?.startsWith(textoDeLaOpcion));
    expect(opcion, `${id} → ${textoDeLaOpcion}`).toBeDefined();
    select.value = opcion!.value;
    select.dispatchEvent(new Event('change'));
    await asentar();
  }

  /** `n` filas que suben (par) o bajan (impar) en la hoja 0. */
  const muchasFilas = (n: number) =>
    unirFilas(
      vistaPrevia({
        cambios: Array.from({ length: n }, (_, i) =>
          cambio(0, i + 2, 100, i % 2 === 0 ? 120 : 80),
        ),
      }),
    );

  describe('paginado en el cliente', () => {
    it('muestra páginas de 100 filas y dice en cuál está', async () => {
      await crear(muchasFilas(4139));

      expect(FILAS_POR_PAGINA).toBe(100);
      expect(filasDeTabla()).toHaveLength(100);
      expect(texto(q('.paginado-texto'))).toBe('Página 1 de 42 · filas 1 a 100 de 4.139');
      expect(valor(filasDeTabla()[0], 'Fila')).toBe('2');
    });

    it('con «Siguiente» y «Anterior» recorre las páginas', async () => {
      await crear(muchasFilas(250));

      boton('Siguiente').click();
      await asentar();
      expect(texto(q('.paginado-texto'))).toBe('Página 2 de 3 · filas 101 a 200 de 250');
      expect(valor(filasDeTabla()[0], 'Fila')).toBe('102');

      boton('Anterior').click();
      await asentar();
      expect(texto(q('.paginado-texto'))).toBe('Página 1 de 3 · filas 1 a 100 de 250');
    });

    it('en los extremos los botones se deshabilitan', async () => {
      await crear(muchasFilas(250));
      expect((boton('Anterior') as HTMLButtonElement).disabled).toBe(true);
      expect((boton('Siguiente') as HTMLButtonElement).disabled).toBe(false);

      boton('Siguiente').click();
      await asentar();
      boton('Siguiente').click();
      await asentar();

      expect(texto(q('.paginado-texto'))).toBe('Página 3 de 3 · filas 201 a 250 de 250');
      expect((boton('Siguiente') as HTMLButtonElement).disabled).toBe(true);
      expect(filasDeTabla()).toHaveLength(50);
    });

    it('con 4.139 filas se llega a la última y se puede desmarcar', async () => {
      const filas = muchasFilas(4139);
      await crear(filas);
      for (let i = 0; i < 41; i++) {
        boton('Siguiente').click();
        await asentar();
      }

      const ultima = filasDeTabla().at(-1)!;
      expect(valor(ultima, 'Fila')).toBe('4140');
      expect(filasDeTabla()).toHaveLength(39);
      casillaDe(ultima)!.click();
      await asentar();

      expect(emitidas).toHaveLength(1);
      expect(emitidas[0].has(claveDe(0, 4140))).toBe(false);
      expect(emitidas[0].size).toBe(4138);
    });

    it('una lista de una sola página no muestra los botones de página', async () => {
      await crear(muchasFilas(5));

      expect(texto(q('.paginado-texto'))).toBe('Página 1 de 1 · filas 1 a 5 de 5');
      expect((boton('Siguiente') as HTMLButtonElement).disabled).toBe(true);
      expect((boton('Anterior') as HTMLButtonElement).disabled).toBe(true);
    });
  });

  describe('filas y casillas', () => {
    const mixtas = () =>
      unirFilas(
        vistaPrevia({
          cambios: [cambio(0, 2, 100, 120)],
          nuevos: [alta(0, 3)],
          sinCambio: [igual(0, 4)],
          errores: [{ hoja: 0, fila: 5, codigo: 'X-1', mensaje: 'Moneda no reconocida' }],
        }),
      );

    it('las filas con error no tienen casilla; las demás sí', async () => {
      await crear(mixtas());

      const filas = filasDeTabla();
      expect(filas).toHaveLength(4);
      expect(casillaDe(filas[0])).not.toBeNull();
      expect(casillaDe(filas[1])).not.toBeNull();
      expect(casillaDe(filas[2])).not.toBeNull();
      expect(casillaDe(filas[3])).toBeNull();
      expect(texto(filas[3])).toContain('Moneda no reconocida');
    });

    it('las casillas siguen la selección que recibe', async () => {
      const filas = mixtas();
      await crear(filas, ['Lista'], marcarFila(seleccionInicial(filas), filas[1], false));

      expect(filasDeTabla().slice(0, 3).map((tr) => casillaDe(tr)!.checked)).toEqual([true, false, true]);
    });

    it('marcar y desmarcar una fila emite la selección nueva', async () => {
      const filas = mixtas();
      await crear(filas);

      casillaDe(filasDeTabla()[1])!.click();
      await asentar();

      expect(emitidas).toHaveLength(1);
      expect([...emitidas[0]].sort()).toEqual(['0:2', '0:4']);
    });

    it('cada casilla dice a qué fila corresponde', async () => {
      await crear(mixtas());

      expect(casillaDe(filasDeTabla()[0])!.getAttribute('aria-label')).toBe('Elegir la fila 2');
    });

    it('muestra código, descripción y el tipo de cada fila', async () => {
      await crear(mixtas());

      const [sube, nuevo, igualFila, conError] = filasDeTabla();
      const columnas = ['Fila', 'Código', 'Descripción', 'Tipo'];
      expect(valores(sube, columnas)).toEqual(['2', 'C-0-2', 'Artículo 0/2', 'Sube']);
      expect(valores(nuevo, columnas)).toEqual(['3', 'N-0-3', 'Nuevo 0/3', 'Nuevo']);
      expect(valores(igualFila, columnas)).toEqual(['4', 'S-0-4', 'Igual 0/4', 'Sin cambio']);
      expect(valores(conError, columnas)).toEqual(['5', 'X-1', '', 'Con error']);
    });
  });

  describe('costos y precios', () => {
    it('un cambio muestra costo y precio anteriores y nuevos en pesos', async () => {
      await crear(unirFilas(vistaPrevia({ cambios: [cambio(0, 2, 1000, 1111.1)] })));

      const fila = filasDeTabla()[0];
      expect(valores(fila, COSTOS)).toEqual(['$ 1.000,00', '$ 1.111,10', '$ 1.500,00', '$ 1.666,65']);
    });

    it('un artículo por kilo, metro o litro dice por qué unidad son sus costos y precios (#36)', async () => {
      await crear(
        unirFilas(
          vistaPrevia({
            cambios: [cambio(0, 2, 1000, 1111.1, { unidad: 'KILO' }), cambio(0, 3, 1000, 1111.1, { unidad: 'METRO' })],
            nuevos: [alta(0, 4, { unidad: 'LITRO' })],
          }),
        ),
      );

      const [porKilo, porMetro, nuevo] = filasDeTabla();
      expect(valores(porKilo, COSTOS)).toEqual([
        '$ 1.000,00 / kg',
        '$ 1.111,10 / kg',
        '$ 1.500,00 / kg',
        '$ 1.666,65 / kg',
      ]);
      expect(valores(porMetro, COSTOS)).toEqual(['$ 1.000,00 / m', '$ 1.111,10 / m', '$ 1.500,00 / m', '$ 1.666,65 / m']);
      expect(valores(nuevo, COSTOS)).toEqual(['—', '$ 18,00 / l', '—', '$ 30,00 / l']);
    });

    it('el «/ kg» va aparte del importe, después de un punto de corte, para que la tabla entre a 1280 px', async () => {
      await crear(unirFilas(vistaPrevia({ cambios: [cambio(0, 2, 1000, 1111.1, { unidad: 'KILO' }), cambio(0, 3)] })));

      const [porKilo, porPieza] = filasDeTabla();
      const celda = (tr: Element, columna: string) => tr.querySelectorAll('td')[encabezados().indexOf(columna)];
      for (const columna of COSTOS) {
        expect(texto(celda(porKilo, columna).querySelector('.importe')), columna).toMatch(/^\$ \d/);
        expect(texto(celda(porKilo, columna).querySelector('wbr + .por-unidad')), columna).toBe('/ kg');
        expect(celda(porPieza, columna).querySelector('.por-unidad'), columna).toBeNull();
      }
    });

    it('«cambia de moneda» muestra ARS → USD con el costo en cada moneda y el precio en pesos', async () => {
      const filas = unirFilas(
        vistaPrevia({
          cambiosDeMoneda: [
            cambio(0, 2, 1000, 10, {
              monedaAnterior: 'ARS',
              monedaNueva: 'USD',
              precioAnterior: 1580,
              precioNuevo: 15730,
            }),
          ],
        }),
      );
      await crear(filas);

      const fila = filasDeTabla()[0];
      expect(valor(fila, 'Tipo')).toBe('Cambia de moneda');
      expect(valores(fila, COSTOS)).toEqual(['$ 1.000,00', 'US$ 10,00', '$ 1.580,00', '$ 15.730,00']);
      expect(valor(fila, 'Detalle')).toBe('Moneda: ARS → USD');
    });

    it('«corrige IVA o ajustes» muestra la alícuota anterior y la nueva, y los ajustes con palabras', async () => {
      const filas = unirFilas(
        vistaPrevia({
          correcciones: [
            cambio(0, 2, 10000, 10000, {
              alicuotaAnterior: 'IVA_21',
              alicuotaNueva: 'IVA_10_5',
              precioAnterior: 15730,
              precioNuevo: 14370,
              ajustesNuevos: { a1: -5, a2: 3, a3: null },
            }),
          ],
        }),
      );
      await crear(filas);

      const fila = filasDeTabla()[0];
      expect(valor(fila, 'Tipo')).toBe('Corrige IVA o ajustes');
      expect(valor(fila, 'Detalle')).toContain('IVA: 21 % → 10,5 %');
      expect(valor(fila, 'Detalle')).toContain('descuento 5 % y recargo 3 %');
      expect(valores(fila, ['Precio anterior', 'Precio nuevo'])).toEqual(['$ 15.730,00', '$ 14.370,00']);
    });

    it('un artículo nuevo muestra su costo en su moneda y el precio final', async () => {
      const filas = unirFilas(
        vistaPrevia({ nuevos: [alta(0, 3, { moneda: 'USD', costo: 10, precioNuevo: 15730 })] }),
      );
      await crear(filas);

      const fila = filasDeTabla()[0];
      expect(valores(fila, COSTOS)).toEqual(['—', 'US$ 10,00', '—', '$ 15.730,00']);
      expect(valor(fila, 'Detalle')).toBe('Moneda: USD');
    });

    it('marca la variación fuerte y los artículos inactivos', async () => {
      const filas = unirFilas(
        vistaPrevia({
          cambios: [cambio(0, 2, 100, 500, { variacionFuerte: true, activo: false }), cambio(0, 3, 100, 110)],
        }),
      );
      await crear(filas);

      const [fuerte, normal] = filasDeTabla();
      expect(fuerte.classList).toContain('fuerte');
      expect(texto(fuerte)).toContain('Variación fuerte');
      expect(texto(fuerte)).toContain('Inactivo');
      expect(normal.classList).not.toContain('fuerte');
      expect(texto(normal)).not.toContain('Inactivo');
    });
  });

  describe('filtros', () => {
    /** Hoja 0 con 3 filas, hoja 1 con 2: suben, bajan, un nuevo y un error. */
    const dosHojas = () =>
      unirFilas(
        vistaPrevia({
          cambios: [cambio(0, 2, 100, 120), cambio(0, 3, 100, 80), cambio(1, 2, 100, 130)],
          nuevos: [alta(0, 4), alta(1, 3)],
          errores: [{ hoja: 1, fila: 4, codigo: 'X-1', mensaje: 'Moneda no reconocida' }],
        }),
      );

    it('por hoja muestra solo las filas de esa hoja', async () => {
      await crear(dosHojas(), ['TORNILLOS', 'TUERCAS']);

      await elegirFiltro('#filtroHoja', 'TUERCAS');

      expect(filasDeTabla().map((tr) => valor(tr, 'Hoja'))).toEqual(['TUERCAS', 'TUERCAS', 'TUERCAS']);
      expect(texto(q('.paginado-texto'))).toBe('Página 1 de 1 · filas 1 a 3 de 3');
    });

    it('por tipo muestra solo ese tipo, en todas las hojas', async () => {
      await crear(dosHojas(), ['TORNILLOS', 'TUERCAS']);

      await elegirFiltro('#filtroTipo', 'Nuevos');

      expect(filasDeTabla().map((tr) => valores(tr, ['Hoja', 'Fila', 'Código', 'Descripción', 'Tipo']))).toEqual([
        ['TORNILLOS', '4', 'N-0-4', 'Nuevo 0/4', 'Nuevo'],
        ['TUERCAS', '3', 'N-1-3', 'Nuevo 1/3', 'Nuevo'],
      ]);
    });

    it('por hoja y tipo a la vez', async () => {
      await crear(dosHojas(), ['TORNILLOS', 'TUERCAS']);

      await elegirFiltro('#filtroHoja', 'TORNILLOS');
      await elegirFiltro('#filtroTipo', 'Suben');

      expect(filasDeTabla()).toHaveLength(1);
      expect(valor(filasDeTabla()[0], 'Código')).toBe('C-0-2');
    });

    it('las opciones de tipo llevan la cantidad del archivo entero, no la de la página ni la del filtro', async () => {
      await crear(muchasFilas(250));
      await elegirFiltro('#filtroTipo', 'Suben');

      const opciones = Array.from(q<HTMLSelectElement>('#filtroTipo').options).map((o) => texto(o));
      expect(opciones).toEqual([
        'Todos los tipos',
        'Suben (125)',
        'Bajan (125)',
        'Cambian de moneda (0)',
        'Corrigen IVA o ajustes (0)',
        'Nuevos (0)',
        'Sin cambio (0)',
        'Con error (0)',
      ]);
    });

    it('al cambiar el filtro vuelve a la primera página', async () => {
      await crear(muchasFilas(250));
      boton('Siguiente').click();
      await asentar();
      expect(texto(q('.paginado-texto'))).toContain('Página 2 de 3');

      await elegirFiltro('#filtroTipo', 'Bajan');

      expect(texto(q('.paginado-texto'))).toBe('Página 1 de 2 · filas 1 a 100 de 125');
    });

    it('con una sola hoja no ofrece el filtro por hoja, pero sí por tipo', async () => {
      await crear(muchasFilas(5), ['Lista']);

      expect(el().querySelector('#filtroHoja')).toBeNull();
      expect(el().querySelector('#filtroTipo')).not.toBeNull();
      expect(encabezados()).not.toContain('Hoja');
    });

    it('un filtro sin filas lo dice', async () => {
      await crear(muchasFilas(5));

      await elegirFiltro('#filtroTipo', 'Nuevos');

      expect(filasDeTabla()).toHaveLength(0);
      expect(texto(q('.sin-filas'))).toBe('No hay filas de ese tipo en esta vista.');
    });
  });

  describe('acciones sobre la selección', () => {
    const libro = () =>
      unirFilas(
        vistaPrevia({
          cambios: [cambio(0, 2, 100, 120), cambio(0, 3, 100, 80), cambio(1, 2, 100, 130), cambio(1, 3, 100, 70)],
          nuevos: [alta(1, 4)],
          errores: [{ hoja: 0, fila: 4, codigo: 'X-1', mensaje: 'Moneda no reconocida' }],
        }),
      );

    it('«Desmarcar todas» emite una selección vacía y «Marcar todas» emite todas las elegibles', async () => {
      const filas = libro();
      await crear(filas, ['A', 'B'], marcarNinguna());

      boton('Marcar todas').click();
      boton('Desmarcar todas').click();

      expect(emitidas[0].size).toBe(5);
      expect(emitidas[0].has('0:4')).toBe(false);
      expect(emitidas[1].size).toBe(0);
    });

    it('con una hoja filtrada, «Desmarcar las del filtro» desmarca esa hoja entera y no toca las otras', async () => {
      await crear(libro(), ['A', 'B']);
      await elegirFiltro('#filtroHoja', 'B');

      boton('Desmarcar las filas del filtro').click();

      expect([...emitidas[0]].sort()).toEqual(['0:2', '0:3']);
    });

    it('con un tipo filtrado, «Marcar las del filtro» marca el tipo entero', async () => {
      const filas = libro();
      await crear(filas, ['A', 'B'], marcarNinguna());
      await elegirFiltro('#filtroTipo', 'Bajan');

      boton('Marcar las filas del filtro').click();

      expect([...emitidas[0]].sort()).toEqual(['0:3', '1:3']);
    });

    it('sin filtro no ofrece las acciones del filtro', async () => {
      await crear(libro(), ['A', 'B']);

      expect(Array.from(el().querySelectorAll('button')).some((b) => b.textContent?.includes('del filtro'))).toBe(false);
    });

    it('cuenta las filas elegidas sobre las que se pueden elegir', async () => {
      const filas = libro();
      await crear(filas, ['A', 'B'], marcarFila(seleccionInicial(filas), filas[0], false));

      expect(texto(q('.cuenta-elegidas'))).toBe('4 de 5 filas elegidas');
      expect(q('.cuenta-elegidas').getAttribute('aria-live')).toBe('polite');
    });

    it('con 4.139 elegibles y 2.980 marcadas lo dice con separador de miles', async () => {
      const filas = muchasFilas(4139);
      const seleccion = new Set(filas.slice(0, 2980).map((f) => f.clave));
      await crear(filas, ['Lista'], seleccion);

      expect(texto(q('.cuenta-elegidas'))).toBe('2.980 de 4.139 filas elegidas');
    });
  });

  describe('accesibilidad', () => {
    it('la tabla tiene nombre y los filtros tienen etiqueta', async () => {
      await crear(muchasFilas(3), ['A', 'B']);

      expect(q('table').getAttribute('aria-label')).toBe('Filas de la vista previa');
      expect(q('label[for="filtroHoja"]').textContent?.trim()).toBe('Hoja');
      expect(q('label[for="filtroTipo"]').textContent?.trim()).toBe('Tipo');
    });

    it('el paginado es una navegación con nombre y anuncia el cambio de página', async () => {
      await crear(muchasFilas(3));

      expect(q('nav.paginado').getAttribute('aria-label')).toBe('Páginas de la vista previa');
      expect(q('.paginado-texto').getAttribute('aria-live')).toBe('polite');
    });
  });
});
