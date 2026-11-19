import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { RubrosService } from '../rubros/rubros.service';
import {
  AltaPlaneada,
  Ajustes,
  AvisoDeFila,
  CambioPlaneado,
  FilaSinCambio,
  Muestra,
  PropuestaDeMapeo,
  VistaPrevia,
} from './importacion.models';
import { Importaciones } from './importaciones';
import { ImportacionesService } from './importaciones.service';
import { FilaDeVista, Seleccion, seleccionInicial, unirFilas } from './seleccion-filas';
import { VistaPreviaTabla } from './vista-previa-tabla';

registerLocaleData(localeEsAr);

/**
 * Revisar cambios en el celular (movil-sectores T13c, S1, S2, S5, S6, S7): sólo cambia cómo se ve la vista previa. Las
 * tarjetas de conteo y el filtro «Tipo» son una sola fila de conteos que filtra; la tabla de 11 columnas es una tarjeta por
 * fila; tocar una tarjeta abre una hoja inferior con el detalle y «Incluir esta fila». La selección, los conteos, los
 * pedidos y los textos son los de siempre: los literales corren en celular y en tablet. En la tablet no aparece nada nuevo.
 */
describe('Revisar cambios en el celular (T13c)', () => {
  type Modo = 'celular' | 'tablet';
  const modos: Modo[] = ['celular', 'tablet'];

  const NINGUNO: Ajustes = { a1: null, a2: null, a3: null };

  function cambio(hoja: number, fila: number, anterior: number, nuevo: number, parcial: Partial<CambioPlaneado> = {}): CambioPlaneado {
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

  function alta(hoja: number, fila: number): AltaPlaneada {
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
    };
  }

  const igual = (hoja: number, fila: number): FilaSinCambio => ({
    id: hoja * 100_000 + fila,
    hoja,
    fila,
    codigo: `S-${hoja}-${fila}`,
    descripcion: `Igual ${hoja}/${fila}`,
  });

  const conError = (hoja: number, fila: number, mensaje: string): AvisoDeFila => ({
    hoja,
    fila,
    codigo: '7733',
    mensaje,
    descripcion: null,
  });

  function vistaPrevia(parcial: Partial<VistaPrevia>): VistaPrevia {
    return {
      conteos: { suben: 0, bajan: 0, nuevos: 0, sinCambio: 0, noEstan: 0, conError: 0, cambianMoneda: 0, corrigen: 0, salteadas: 0 },
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

  /** Dos suben (la fila 4 con variación fuerte), una baja, un nuevo, uno sin cambio y uno con error. */
  const VISTA: VistaPrevia = vistaPrevia({
    conteos: { suben: 2, bajan: 1, nuevos: 1, sinCambio: 1, noEstan: 2, conError: 1, cambianMoneda: 0, corrigen: 0, salteadas: 1 },
    cambios: [
      cambio(0, 2, 100, 120),
      cambio(0, 3, 100, 80),
      cambio(0, 4, 100, 300, { variacionFuerte: true }),
    ],
    nuevos: [alta(0, 5)],
    sinCambio: [igual(0, 6)],
    errores: [conError(0, 7, 'El costo no es un número.')],
    noEstan: [
      { id: 91, codigoInterno: 'ART-091', codigoProveedor: 'P-91', descripcion: 'Perno ausente' },
      { id: 92, codigoInterno: 'ART-092', codigoProveedor: null, descripcion: 'Clavo ausente' },
    ],
  });

  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();

  function stubPantalla(modo: Modo) {
    vi.stubGlobal('matchMedia', (c: string) => ({
      matches: c.includes(modo === 'celular' ? '639.98px' : '1023.98px'),
      media: c,
    }));
  }

  afterEach(() => vi.unstubAllGlobals());

  // =================================================================================== la tabla de la vista previa

  describe('VistaPreviaTabla', () => {
    let fixture: ComponentFixture<VistaPreviaTabla>;
    let emitidas: Seleccion[];
    let filas: FilaDeVista[];

    async function crear(modo: Modo, opts: { filas?: FilaDeVista[]; hojas?: string[]; seleccion?: Seleccion } = {}) {
      stubPantalla(modo);
      filas = opts.filas ?? unirFilas(VISTA);
      await TestBed.configureTestingModule({
        imports: [VistaPreviaTabla],
        providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
      }).compileComponents();
      fixture = TestBed.createComponent(VistaPreviaTabla);
      fixture.componentRef.setInput('filas', filas);
      fixture.componentRef.setInput('hojas', opts.hojas ?? ['Lista']);
      fixture.componentRef.setInput('seleccion', opts.seleccion ?? seleccionInicial(filas));
      emitidas = [];
      // Como el padre: lo que se emite vuelve como la selección vigente.
      fixture.componentInstance.seleccionCambia.subscribe((s) => {
        emitidas.push(s);
        fixture.componentRef.setInput('seleccion', s);
      });
      await asentar();
    }

    async function asentar() {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    }

    const el = () => fixture.nativeElement as HTMLElement;
    const q = <T extends Element = HTMLElement>(s: string) => el().querySelector<T>(s);
    const qa = <T extends Element = HTMLElement>(s: string) => Array.from(el().querySelectorAll<T>(s));
    const boton = (t: string) => qa<HTMLButtonElement>('button').find((b) => normal(b.textContent) === t);
    const casillas = () => qa<HTMLInputElement>('input[type="checkbox"][aria-label^="Elegir la fila"]');
    const casilla = (fila: number) =>
      casillas().find((c) => new RegExp(`^Elegir la fila ${fila}( |$)`).test(c.getAttribute('aria-label')!))!;
    const claves = (s: Seleccion) => [...s].sort();
    const todas = ['0:2', '0:3', '0:4', '0:5', '0:6'];

    /** Elige un tipo como lo hace la persona: el select «Tipo» en la tablet, la fila de conteos en el celular. */
    async function filtrarPorTipo(modo: Modo, nombre: string) {
      if (modo === 'tablet') {
        const select = q<HTMLSelectElement>('#filtroTipo')!;
        const opcion = qa<HTMLOptionElement>('#filtroTipo option').find((o) => normal(o.textContent).startsWith(nombre))!;
        select.value = opcion.value;
        select.dispatchEvent(new Event('change'));
      } else {
        qa<HTMLButtonElement>('.conteos-celular button').find((b) => normal(b.querySelector('span')?.textContent) === nombre)!.click();
      }
      await asentar();
    }

    /** Los conteos por tipo, sin importar con qué control se ven. */
    function conteosPorTipo(modo: Modo): Record<string, number> {
      const lista =
        modo === 'tablet'
          ? qa<HTMLOptionElement>('#filtroTipo option')
              .slice(1)
              .map((o) => /^(.+) \((\d+)\)$/.exec(normal(o.textContent))!)
              .map((m) => [m[1], Number(m[2])] as const)
          : qa<HTMLButtonElement>('.conteos-celular button').map(
              (b) => [normal(b.querySelector('span')?.textContent), Number(normal(b.querySelector('b')?.textContent))] as const,
            );
      const cinco = ['Suben', 'Bajan', 'Nuevos', 'Sin cambio', 'Con error'];
      return Object.fromEntries(lista.filter(([nombre]) => cinco.includes(nombre)));
    }

    // ------------------------------------------------------------------------- lo que no cambia (pasa en la base)

    describe.each(modos)('la selección y los textos de siempre, en %s', (modo) => {
      it('cada fila elegible tiene su casilla con el nombre de siempre y la de error no tiene', async () => {
        await crear(modo);

        expect(casillas().map((c) => c.getAttribute('aria-label'))).toEqual([
          'Elegir la fila 2',
          'Elegir la fila 3',
          'Elegir la fila 4',
          'Elegir la fila 5',
          'Elegir la fila 6',
        ]);
        expect(casillas().every((c) => c.checked)).toBe(true);
      });

      it('con varias hojas la casilla dice también la hoja', async () => {
        await crear(modo, { hojas: ['Lista', 'Ofertas'] });

        expect(casilla(2).getAttribute('aria-label')).toBe('Elegir la fila 2 de Lista');
      });

      it('destildar y tildar una fila emite la selección sin ella y con ella, una vez cada vez', async () => {
        await crear(modo);

        casilla(3).click();
        await asentar();
        expect(emitidas.map(claves)).toEqual([['0:2', '0:4', '0:5', '0:6']]);
        expect(casilla(3).checked).toBe(false);

        casilla(3).click();
        await asentar();
        expect(emitidas.map(claves)).toEqual([['0:2', '0:4', '0:5', '0:6'], todas]);
      });

      it('«Marcar todas» y «Desmarcar todas» emiten todas las elegibles y ninguna', async () => {
        await crear(modo, { seleccion: new Set(['0:2']) });

        boton('Marcar todas')!.click();
        await asentar();
        boton('Desmarcar todas')!.click();
        await asentar();

        expect(emitidas.map(claves)).toEqual([todas, []]);
      });

      it('«N de M filas elegidas» cuenta lo elegido entre lo elegible', async () => {
        await crear(modo, { seleccion: new Set(['0:2', '0:3']) });

        expect(normal(q('.cuenta-elegidas')?.textContent)).toBe('2 de 5 filas elegidas');
        expect(q('.cuenta-elegidas')?.getAttribute('aria-live')).toBe('polite');
      });

      it('los conteos por tipo son los del archivo entero: 2, 1, 1, 1 y 1', async () => {
        await crear(modo);

        expect(conteosPorTipo(modo)).toEqual({ Suben: 2, Bajan: 1, Nuevos: 1, 'Sin cambio': 1, 'Con error': 1 });
      });

      it('filtrar por tipo deja sólo esas filas y suma «Marcar las filas del filtro» y «Desmarcar las filas del filtro»', async () => {
        await crear(modo);
        expect(boton('Marcar las filas del filtro')).toBeUndefined();

        await filtrarPorTipo(modo, 'Suben');

        expect(casillas().map((c) => c.getAttribute('aria-label'))).toEqual(['Elegir la fila 2', 'Elegir la fila 4']);
        expect(boton('Marcar las filas del filtro')).toBeDefined();
        boton('Desmarcar las filas del filtro')!.click();
        await asentar();
        expect(emitidas.map(claves)).toEqual([['0:3', '0:5', '0:6']]);
      });

      it('«Marcar las filas del filtro» marca sólo las del tipo y deja el resto como estaba', async () => {
        await crear(modo, { seleccion: new Set(['0:3']) });

        await filtrarPorTipo(modo, 'Suben');
        boton('Marcar las filas del filtro')!.click();
        await asentar();

        expect(emitidas.map(claves)).toEqual([['0:2', '0:3', '0:4']]);
      });

      it('el paginado dice en qué página está y filtrar vuelve a la primera', async () => {
        await crear(modo);

        expect(normal(q('.paginado-texto')?.textContent)).toBe('Página 1 de 1 · filas 1 a 6 de 6');
        await filtrarPorTipo(modo, 'Bajan');
        expect(normal(q('.paginado-texto')?.textContent)).toBe('Página 1 de 1 · filas 1 a 1 de 1');
      });

      it('con varias hojas el filtro «Hoja» sigue siendo un select con «Todas las hojas»', async () => {
        await crear(modo, { hojas: ['Lista', 'Ofertas'] });

        const opciones = qa<HTMLOptionElement>('#filtroHoja option').map((o) => normal(o.textContent));
        expect(opciones).toEqual(['Todas las hojas', 'Lista', 'Ofertas']);
        expect(normal(q('label[for="filtroHoja"]')?.textContent)).toBe('Hoja');
      });

      it('una fila de otra hoja no se ve al filtrar por hoja, y los conteos no cambian', async () => {
        const dosHojas = unirFilas(
          vistaPrevia({ cambios: [cambio(0, 2, 100, 120), cambio(1, 2, 100, 120)] }),
        );
        await crear(modo, { filas: dosHojas, hojas: ['Lista', 'Ofertas'] });

        const select = q<HTMLSelectElement>('#filtroHoja')!;
        select.value = '1';
        select.dispatchEvent(new Event('change'));
        await asentar();

        expect(casillas().map((c) => c.getAttribute('aria-label'))).toEqual(['Elegir la fila 2 de Ofertas']);
        expect(normal(q('.paginado-texto')?.textContent)).toBe('Página 1 de 1 · filas 1 a 1 de 1');
      });
    });

    describe.each(modos)('los precios y el detalle, en %s', (modo) => {
      const todoElTexto = () => normal(el().textContent);

      it('cada fila dice su descripción, su código, su tipo y su precio nuevo y el anterior con los mismos importes', async () => {
        await crear(modo);

        const texto = todoElTexto();
        for (const esperado of ['Artículo 0/2', 'C-0-2', 'Sube', '$ 180,00', '$ 150,00', 'Artículo 0/3', 'Baja', '$ 120,00', 'Nuevo 0/5', 'Nuevo', '$ 30,00', 'Igual 0/6', 'Sin cambio']) {
          expect(texto, esperado).toContain(esperado);
        }
      });

      it('la variación fuerte se marca una sola vez, en la fila que la tiene', async () => {
        await crear(modo);

        expect(qa('.etiqueta-fuerte')).toHaveLength(1);
        expect(normal(q('.etiqueta-fuerte')?.textContent)).toBe('Variación fuerte');
      });

      it('un artículo inactivo lleva «Inactivo»', async () => {
        const inactivo = unirFilas(vistaPrevia({ cambios: [cambio(0, 2, 100, 120, { activo: false })] }));
        await crear(modo, { filas: inactivo });

        expect(todoElTexto()).toContain('Inactivo');
      });

      it('la fila con error dice su mensaje y no se puede elegir', async () => {
        await crear(modo);

        expect(todoElTexto()).toContain('El costo no es un número.');
        expect(todoElTexto()).toContain('Con error');
        expect(casillas()).toHaveLength(5);
      });

      it('el detalle de moneda, IVA y ajustes de una fila es el texto de hoy', async () => {
        const conMoneda = unirFilas(
          vistaPrevia({
            cambiosDeMoneda: [cambio(0, 2, 100, 120, { monedaAnterior: 'ARS', monedaNueva: 'USD' })],
          }),
        );
        await crear(modo, { filas: conMoneda });
        // El detalle en el celular está en la hoja de la fila; en la tablet, en la tabla.
        if (modo === 'celular') {
          q<HTMLButtonElement>('button.fila-celular-cuerpo')!.click();
          await asentar();
        }

        expect(todoElTexto()).toContain('Moneda: ARS → USD');
      });

      it('sin filas del tipo elegido dice «No hay filas de ese tipo en esta vista.»', async () => {
        await crear(modo, { filas: unirFilas(vistaPrevia({ cambios: [cambio(0, 2, 100, 120)] })) });

        await filtrarPorTipo(modo, 'Bajan');

        expect(normal(q('.sin-filas')?.textContent)).toBe('No hay filas de ese tipo en esta vista.');
        expect(q('.paginado')).toBeNull();
      });
    });

    // ------------------------------------------------------------------------------ en la tablet no cambia nada

    describe('en la tablet', () => {
      it('sigue la tabla de 11 columnas con el select «Tipo» y no hay fila de conteos, tarjetas ni hoja', async () => {
        await crear('tablet', { hojas: ['Lista', 'Ofertas'] });

        expect(qa('thead th').map((th) => normal(th.textContent))).toEqual([
          'Elegir', 'Hoja', 'Fila', 'Código', 'Descripción', 'Tipo', 'Costo anterior', 'Costo nuevo', 'Precio anterior', 'Precio nuevo', 'Detalle',
        ]);
        expect(q('select#filtroTipo')).not.toBeNull();
        expect(qa('#filtroTipo option').map((o) => normal(o.textContent))).toEqual([
          'Todos los tipos',
          'Suben (2)',
          'Bajan (1)',
          'Cambian de moneda (0)',
          'Corrigen IVA o ajustes (0)',
          'Nuevos (1)',
          'Sin cambio (1)',
          'Con error (1)',
        ]);
        expect(q('.conteos-celular')).toBeNull();
        expect(q('.filas-celular')).toBeNull();
        expect(q('app-hoja-inferior')).toBeNull();
      });

      it('tocar una fila de la tabla no abre ninguna hoja', async () => {
        await crear('tablet');

        q<HTMLElement>('tbody tr td.codigo')!.click();
        await asentar();

        expect(q('app-hoja-inferior')).toBeNull();
      });
    });

    // ------------------------------------------------------------------------------ lo propio del celular

    describe('la fila de conteos que es el filtro', () => {
      it('reemplaza al select «Tipo»: no está en el DOM y hay un grupo «Tipo de cambio»', async () => {
        await crear('celular');

        expect(q('#filtroTipo')).toBeNull();
        expect(q('label[for="filtroTipo"]')).toBeNull();
        const grupo = q('.conteos-celular')!;
        expect(grupo.getAttribute('role')).toBe('group');
        expect(grupo.getAttribute('aria-label')).toBe('Tipo de cambio');
      });

      it('tiene Suben, Bajan, Nuevos, Sin cambio y Con error, cada uno con su número y sin pulsar', async () => {
        await crear('celular');

        const botones = qa<HTMLButtonElement>('.conteos-celular button');
        expect(botones.map((b) => [normal(b.querySelector('b')?.textContent), normal(b.querySelector('span')?.textContent)])).toEqual([
          ['2', 'Suben'],
          ['1', 'Bajan'],
          ['1', 'Nuevos'],
          ['1', 'Sin cambio'],
          ['1', 'Con error'],
        ]);
        expect(botones.every((b) => b.type === 'button' && b.getAttribute('aria-pressed') === 'false')).toBe(true);
      });

      it('«Cambian de moneda» y «Corrigen IVA o ajustes» aparecen sólo cuando el archivo tiene filas de ese tipo', async () => {
        const con = unirFilas(
          vistaPrevia({
            cambios: [cambio(0, 2, 100, 120)],
            cambiosDeMoneda: [cambio(0, 3, 100, 120, { monedaNueva: 'USD' })],
            correcciones: [cambio(0, 4, 100, 100, { alicuotaNueva: 'IVA_10_5' })],
          }),
        );
        await crear('celular', { filas: con });

        expect(qa('.conteos-celular button span').map((s) => normal(s.textContent))).toEqual([
          'Suben', 'Bajan', 'Cambian de moneda', 'Corrigen IVA o ajustes', 'Nuevos', 'Sin cambio', 'Con error',
        ]);
      });

      it('tocar un conteo filtra, lo marca pulsado y deja sólo las tarjetas de ese tipo', async () => {
        await crear('celular');

        await filtrarPorTipo('celular', 'Suben');

        const pulsados = qa<HTMLButtonElement>('.conteos-celular button[aria-pressed="true"]');
        expect(pulsados.map((b) => normal(b.querySelector('span')?.textContent))).toEqual(['Suben']);
        expect(qa('li.fila-celular')).toHaveLength(2);
      });

      it('tocar el conteo pulsado saca el filtro y vuelven todas las filas', async () => {
        await crear('celular');
        await filtrarPorTipo('celular', 'Suben');

        await filtrarPorTipo('celular', 'Suben');

        expect(qa('.conteos-celular button[aria-pressed="true"]')).toHaveLength(0);
        expect(qa('li.fila-celular')).toHaveLength(6);
      });

      it('pasar de un conteo a otro cambia el filtro y «Con error» muestra la fila con error', async () => {
        await crear('celular');
        await filtrarPorTipo('celular', 'Suben');

        await filtrarPorTipo('celular', 'Con error');

        expect(qa('li.fila-celular')).toHaveLength(1);
        expect(normal(q('li.fila-celular')?.textContent)).toContain('El costo no es un número.');
      });

      it('el filtro de hoja y el de tipo se suman, como con los selects', async () => {
        const dosHojas = unirFilas(vistaPrevia({ cambios: [cambio(0, 2, 100, 120), cambio(1, 2, 100, 120), cambio(1, 3, 100, 80)] }));
        await crear('celular', { filas: dosHojas, hojas: ['Lista', 'Ofertas'] });

        const select = q<HTMLSelectElement>('#filtroHoja')!;
        select.value = '1';
        select.dispatchEvent(new Event('change'));
        await asentar();
        await filtrarPorTipo('celular', 'Suben');

        expect(qa('li.fila-celular')).toHaveLength(1);
      });
    });

    describe('una tarjeta por fila', () => {
      it('no hay tabla ni columnas: hay una lista con una tarjeta por fila de la página', async () => {
        await crear('celular');

        expect(q('table')).toBeNull();
        expect(q('.grilla-contenedor')).toBeNull();
        const lista = q('ul.filas-celular')!;
        expect(lista.getAttribute('aria-label')).toBe('Filas de la vista previa');
        expect(qa('li.fila-celular')).toHaveLength(6);
      });

      it('la tarjeta lleva la casilla, la descripción con su código, el tipo y el precio nuevo con el anterior', async () => {
        await crear('celular');

        const tarjeta = qa('li.fila-celular')[0];
        expect(tarjeta.querySelector('input[type="checkbox"]')?.getAttribute('aria-label')).toBe('Elegir la fila 2');
        expect(normal(tarjeta.querySelector('.fila-celular-descripcion')?.textContent)).toBe('Artículo 0/2');
        expect(normal(tarjeta.querySelector('.fila-celular-codigo')?.textContent)).toBe('C-0-2');
        expect(normal(tarjeta.querySelector('.etiqueta-suben')?.textContent)).toBe('Sube');
        expect(normal(tarjeta.querySelector('.fila-celular-precio')?.textContent)).toBe('$ 180,00');
        expect(normal(tarjeta.querySelector('.fila-celular-antes')?.textContent)).toBe('antes $ 150,00');
        expect(normal(tarjeta.querySelector('.fila-celular-antes s')?.textContent)).toBe('$ 150,00');
      });

      it('la variación fuerte va en la tarjeta de esa fila, al lado del tipo', async () => {
        await crear('celular');

        const fuerte = qa('li.fila-celular')[2];
        expect(normal(fuerte.querySelector('.etiqueta-fuerte')?.textContent)).toBe('Variación fuerte');
        expect(qa('li.fila-celular')[0].querySelector('.etiqueta-fuerte')).toBeNull();
      });

      it('una fila nueva muestra su precio nuevo y no tiene «antes»', async () => {
        await crear('celular');

        const nueva = qa('li.fila-celular')[3];
        expect(normal(nueva.querySelector('.fila-celular-precio')?.textContent)).toBe('$ 30,00');
        expect(nueva.querySelector('.fila-celular-antes')).toBeNull();
      });

      it('una fila con error muestra su mensaje en rojo con un ícono, sin casilla y sin abrir hoja', async () => {
        await crear('celular');

        const error = qa('li.fila-celular')[5];
        expect(error.classList.contains('con-error')).toBe(true);
        expect(normal(error.querySelector('.fila-celular-error')?.textContent)).toBe('El costo no es un número.');
        expect(error.querySelector('.fila-celular-error svg')?.getAttribute('aria-hidden')).toBe('true');
        expect(error.querySelector('input')).toBeNull();
        expect(error.querySelector('button')).toBeNull();
      });

      it('la tarjeta marca las filas elegidas y la casilla es la misma selección', async () => {
        await crear('celular', { seleccion: new Set(['0:2']) });

        const tarjetas = qa('li.fila-celular');
        expect(tarjetas[0].classList.contains('marcada')).toBe(true);
        expect(tarjetas[1].classList.contains('marcada')).toBe(false);
        casilla(3).click();
        await asentar();
        expect(emitidas.map(claves)).toEqual([['0:2', '0:3']]);
        expect(qa('li.fila-celular')[1].classList.contains('marcada')).toBe(true);
      });

      it('tocar la casilla no abre la hoja', async () => {
        await crear('celular');

        casilla(2).click();
        await asentar();

        expect(q('app-hoja-inferior')).toBeNull();
      });

      it('la lista sigue paginada de a 100: 250 filas son 100 tarjetas', async () => {
        const muchas = unirFilas(vistaPrevia({ cambios: Array.from({ length: 250 }, (_, i) => cambio(0, i + 2, 100, 120)) }));
        await crear('celular', { filas: muchas });

        expect(qa('li.fila-celular')).toHaveLength(100);
        expect(normal(q('.paginado-texto')?.textContent)).toBe('Página 1 de 3 · filas 1 a 100 de 250');
      });
    });

    describe('la hoja con el detalle de la fila', () => {
      const hoja = () => q('app-hoja-inferior');
      const cuerpoDe = (i: number) => qa<HTMLButtonElement>('li.fila-celular button.fila-celular-cuerpo')[i];
      const abrir = async (i: number) => {
        cuerpoDe(i).click();
        await asentar();
      };
      const incluir = () => q<HTMLInputElement>('app-hoja-inferior input[type="checkbox"]')!;

      it('tocar la tarjeta abre una hoja modal con la descripción de título y el código, la hoja y la fila', async () => {
        await crear('celular');

        await abrir(0);

        expect(hoja()).not.toBeNull();
        expect(q('app-hoja-inferior [role="dialog"]')?.getAttribute('aria-modal')).toBe('true');
        expect(normal(q('app-hoja-inferior .hoja-inferior-titulo')?.textContent)).toBe('Artículo 0/2');
        expect(normal(q('app-hoja-inferior .hoja-inferior-subtitulo')?.textContent)).toBe('Código C-0-2 · Hoja «Lista», fila 2');
      });

      it('muestra costo anterior y nuevo y precio anterior y nuevo con los importes de la tabla', async () => {
        await crear('celular');

        await abrir(0);

        const renglones = qa('app-hoja-inferior .detalle-fila-celular > div').map((d) => [
          normal(d.querySelector('dt')?.textContent),
          normal(d.querySelector('dd')?.textContent),
        ]);
        expect(renglones).toEqual([
          ['Costo anterior', '$ 100,00'],
          ['Costo nuevo', '$ 120,00'],
          ['Precio anterior', '$ 150,00'],
          ['Precio nuevo', '$ 180,00'],
        ]);
      });

      it('una fila nueva muestra «—» donde no hay dato, como la tabla', async () => {
        await crear('celular');

        await abrir(3);

        const renglones = qa('app-hoja-inferior .detalle-fila-celular > div').map((d) => normal(d.querySelector('dd')?.textContent));
        expect(renglones).toEqual(['—', '$ 18,00', '—', '$ 30,00']);
      });

      it('la variación fuerte se avisa dentro de la hoja', async () => {
        await crear('celular');

        await abrir(2);

        expect(normal(q('app-hoja-inferior .etiqueta-fuerte')?.textContent)).toBe('Variación fuerte');
      });

      it('lleva «Incluir esta fila» con la casilla en el estado de la fila', async () => {
        await crear('celular', { seleccion: new Set(['0:3']) });

        await abrir(0);
        expect(normal(q('app-hoja-inferior label.incluir-fila')?.textContent)).toBe('Incluir esta fila');
        expect(incluir().checked).toBe(false);
        q<HTMLElement>('app-hoja-inferior .dialogo-fondo')!.click();
        await asentar();

        await abrir(1);
        expect(incluir().checked).toBe(true);
      });

      it('«Incluir esta fila» cambia la misma selección que la casilla de la tarjeta, con el mismo resultado', async () => {
        await crear('celular');
        await abrir(1);

        incluir().click();
        await asentar();
        expect(emitidas.map(claves)).toEqual([['0:2', '0:4', '0:5', '0:6']]);
        expect(casilla(3).checked).toBe(false);

        incluir().click();
        await asentar();
        expect(emitidas.map(claves)).toEqual([['0:2', '0:4', '0:5', '0:6'], todas]);
        expect(casilla(3).checked).toBe(true);
      });

      it('una fila sin cambio también se abre y se puede incluir', async () => {
        await crear('celular');

        await abrir(4);

        expect(normal(q('app-hoja-inferior .hoja-inferior-titulo')?.textContent)).toBe('Igual 0/6');
        incluir().click();
        await asentar();
        expect(emitidas.map(claves)).toEqual([['0:2', '0:3', '0:4', '0:5']]);
      });

      it('el detalle de moneda, IVA y ajustes va dentro de la hoja con el texto de hoy', async () => {
        const rica = unirFilas(
          vistaPrevia({
            cambios: [cambio(0, 2, 100, 120, { alicuotaNueva: 'IVA_10_5', ajustesNuevos: { a1: -5, a2: null, a3: null } })],
          }),
        );
        await crear('celular', { filas: rica });

        await abrir(0);

        const lineas = qa('app-hoja-inferior .detalle-lineas > li').map((l) => normal(l.textContent));
        expect(lineas).toEqual(['IVA: 21 % → 10,5 %', 'Descuentos y recargos: ninguno → descuento 5 %']);
      });

      it('Esc la cierra sin cambiar la selección y el foco vuelve a la tarjeta que la abrió', async () => {
        await crear('celular');
        await abrir(1);

        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        await asentar();

        expect(hoja()).toBeNull();
        expect(emitidas).toEqual([]);
        expect(document.activeElement).toBe(cuerpoDe(1));
      });

      it('un toque en el velo la cierra y devuelve el foco', async () => {
        await crear('celular');
        await abrir(0);

        q<HTMLElement>('app-hoja-inferior .dialogo-fondo')!.click();
        await asentar();

        expect(hoja()).toBeNull();
        expect(document.activeElement).toBe(cuerpoDe(0));
      });

      it('«Cerrar» la cierra y devuelve el foco', async () => {
        await crear('celular');
        await abrir(2);

        qa<HTMLButtonElement>('app-hoja-inferior button').find((b) => normal(b.textContent) === 'Cerrar')!.click();
        await asentar();

        expect(hoja()).toBeNull();
        expect(document.activeElement).toBe(cuerpoDe(2));
      });

      it('al abrir no mueve el foco a un campo: queda en la hoja', async () => {
        await crear('celular');

        await abrir(0);

        expect(document.activeElement?.matches('input, select, textarea')).toBeFalsy();
        expect(hoja()?.contains(document.activeElement)).toBe(true);
      });

      it('la hoja atrapa el foco: Tab desde el último control vuelve al primero', async () => {
        await crear('celular');
        await abrir(0);

        const controles = qa<HTMLElement>('app-hoja-inferior input, app-hoja-inferior button');
        controles[controles.length - 1].focus();
        const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
        document.dispatchEvent(tab);

        expect(tab.defaultPrevented).toBe(true);
        expect(document.activeElement).toBe(controles[0]);
      });
    });
  });

  // ============================================================================ la pantalla Importar, con la vista previa

  describe('Importaciones con la vista previa', () => {
    const sinteplast: Proveedor = { id: 1, nombre: 'Sinteplast', cuit: null, activo: true };
    const SIN: PropuestaDeMapeo = {
      hoja: null, filaDesde: null, columnaCodigo: null, columnaDescripcion: null, columnaCosto: null, columnaMoneda: null,
      columnaSinIva: null, columnaConIva: null, columnaIva: null, columnaAjuste1: null, columnaAjuste2: null, columnaAjuste3: null,
      columnaCodigoBarras: null, columnaUnidad: null, columnaRubro: null, hojas: [],
    };
    const muestra: Muestra = {
      hojas: [
        {
          indice: 0,
          nombre: 'Lista',
          columnas: 3,
          filaEncabezado: 1,
          filas: [
            { numero: 1, celdas: ['Codigo', 'Descripcion', 'Precio'] },
            { numero: 2, celdas: ['A-1', 'Tornillo', '1.234,56'] },
          ],
        },
      ],
      propuesta: { ...SIN, hoja: 0, filaDesde: 2, columnaCodigo: 0, columnaDescripcion: 1, columnaCosto: 2, hojas: [0] },
    };
    const PAYLOAD = {
      proveedorId: 1,
      mapeo: { hoja: 0, filaDesde: 2, columnaCodigo: 0, columnaDescripcion: 1, columnaCosto: 2, hojas: [0] },
      listaIncluyeIva: false,
      bonificacion1: null,
      bonificacion2: null,
      bonificacion3: null,
      rubroId: null,
    };
    const CONTEOS_TODOS = { suben: 2, bajan: 1, cambianMoneda: 0, corrigen: 0, nuevos: 1, sinCambio: 1, noEstan: 2, conError: 1, salteadas: 1 };

    let fixture: ComponentFixture<Importaciones>;
    let servicio: Record<'muestra' | 'vistaPrevia' | 'aplicar', ReturnType<typeof vi.fn>>;

    async function refrescar() {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    }

    const el = () => fixture.nativeElement as HTMLElement;
    const q = <T extends Element = HTMLElement>(s: string) => el().querySelector<T>(s);
    const qa = <T extends Element = HTMLElement>(s: string) => Array.from(el().querySelectorAll<T>(s));
    const boton = (t: string) => qa<HTMLButtonElement>('button').find((b) => normal(b.textContent) === t);
    const casilla = (fila: number) =>
      qa<HTMLInputElement>('app-vista-previa-tabla input[type="checkbox"]').find((c) => c.getAttribute('aria-label') === `Elegir la fila ${fila}`)!;

    async function conVistaPrevia(modo: Modo, vista: VistaPrevia = VISTA) {
      stubPantalla(modo);
      servicio = { muestra: vi.fn(() => of(muestra)), vistaPrevia: vi.fn(() => of(vista)), aplicar: vi.fn(() => new Subject()) };
      await TestBed.configureTestingModule({
        imports: [Importaciones],
        providers: [
          { provide: ImportacionesService, useValue: servicio },
          { provide: ProveedoresService, useValue: { listar: vi.fn(() => of([sinteplast])) } },
          { provide: RubrosService, useValue: { listar: vi.fn(() => of([])) } },
          { provide: AuthService, useValue: { esDueno: signal(true) } },
          { provide: LOCALE_ID, useValue: 'es-AR' },
        ],
      }).compileComponents();
      fixture = TestBed.createComponent(Importaciones);
      await refrescar();
      const proveedor = q<HTMLSelectElement>('#proveedor')!;
      proveedor.value = '1';
      proveedor.dispatchEvent(new Event('change'));
      await refrescar();
      const archivo = new File(['x'], 'lista.xlsx');
      const input = q<HTMLInputElement>('#archivo')!;
      Object.defineProperty(input, 'files', { value: [archivo], configurable: true });
      input.dispatchEvent(new Event('change'));
      await refrescar();
      boton('Ver vista previa')!.click();
      await refrescar();
    }

    async function confirmar() {
      boton('Confirmar importación')!.click();
      await refrescar();
    }

    describe.each(modos)('los pedidos y los textos de siempre, en %s', (modo) => {
      it('la vista previa se pide una vez, con el mapeo reconocido', async () => {
        await conVistaPrevia(modo);

        expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
        expect(servicio.vistaPrevia).toHaveBeenCalledWith(expect.any(File), PAYLOAD);
        expect(servicio.aplicar).not.toHaveBeenCalled();
      });

      it('al abrir están todas las elegibles y «Confirmar importación» manda esas filas y esos conteos', async () => {
        await conVistaPrevia(modo);

        await confirmar();

        expect(servicio.aplicar).toHaveBeenCalledTimes(1);
        expect(servicio.aplicar).toHaveBeenCalledWith(expect.any(File), {
          ...PAYLOAD,
          crearNuevos: true,
          conteosEsperados: CONTEOS_TODOS,
          filasElegidas: [
            { hoja: 0, fila: 2 },
            { hoja: 0, fila: 3 },
            { hoja: 0, fila: 4 },
            { hoja: 0, fila: 5 },
            { hoja: 0, fila: 6 },
          ],
        });
      });

      it('destildar una fila (la de variación fuerte) saca esa fila y recalcula los conteos', async () => {
        await conVistaPrevia(modo);

        casilla(4).click();
        await refrescar();
        await confirmar();

        expect(servicio.aplicar).toHaveBeenCalledWith(expect.any(File), {
          ...PAYLOAD,
          crearNuevos: true,
          conteosEsperados: { ...CONTEOS_TODOS, suben: 1 },
          filasElegidas: [
            { hoja: 0, fila: 2 },
            { hoja: 0, fila: 3 },
            { hoja: 0, fila: 5 },
            { hoja: 0, fila: 6 },
          ],
        });
      });

      it('los textos de la vista previa son los de hoy: ayuda, salteadas y aviso de errores', async () => {
        await conVistaPrevia(modo);

        const paso = q('.vista-previa')!;
        expect(normal(paso.querySelector('h2')?.textContent)).toBe('3. Vista previa');
        expect(normal(paso.querySelector(':scope > p.ayuda')?.textContent)).toBe(
          'Todavía no se cambió nada. Revisá lo que va a pasar y elegí qué filas aplicar: al abrir están todas marcadas. Las tarjetas cuentan lo elegido.',
        );
        expect(normal(q('.salteadas')?.textContent)).toBe(
          'Se salteó 1 fila de título o de rubro: no se importan ni tienen detalle.',
        );
        expect(normal(q('.aviso-errores')?.textContent)).toBe(
          'Hay 1 fila con error: no se importa. Filtrá por el tipo «Con error» para verla.',
        );
      });

      it('«No están en la lista (N)» empieza plegado y se despliega con el mismo texto y las mismas filas', async () => {
        await conVistaPrevia(modo);
        const detalles = q<HTMLDetailsElement>('details.no-estan')!;

        expect(normal(detalles.querySelector('summary')?.textContent)).toBe('No están en la lista (2)');
        expect(detalles.open).toBe(false);
        expect(qa('details.no-estan tbody tr')).toHaveLength(0);
        detalles.open = true;
        detalles.dispatchEvent(new Event('toggle'));
        await refrescar();

        expect(qa('details.no-estan tbody tr').map((tr) => Array.from(tr.querySelectorAll('td')).map((td) => normal(td.textContent)))).toEqual([
          ['ART-091', 'P-91', 'Perno ausente'],
          ['ART-092', '—', 'Clavo ausente'],
        ]);
        expect(normal(q('details.no-estan .ayuda')?.textContent)).toBe(
          'Artículos activos del proveedor que no figuran en el archivo. No se tocan.',
        );
      });
    });

    describe('en la tablet', () => {
      it('siguen las nueve tarjetas de conteo de arriba, con lo elegido, y la tabla', async () => {
        await conVistaPrevia('tablet');

        expect(qa('.vista-previa .tarjetas .tarjeta').map((t) => normal(t.textContent))).toEqual([
          'Suben 2',
          'Bajan 1',
          'Cambian de moneda 0',
          'Corrigen IVA o ajustes 0',
          'Nuevos 1',
          'Sin cambio 1',
          'No están 2',
          'Con error 1',
          'Salteadas 1',
        ]);
        expect(q('app-vista-previa-tabla table')).not.toBeNull();
        expect(q('.conteos-celular')).toBeNull();
        expect(q('.pasos-celular')).toBeNull();
      });

      it('«No están» no es una tabla de tarjetas ni lleva etiquetas de datos', async () => {
        await conVistaPrevia('tablet');
        const detalles = q<HTMLDetailsElement>('details.no-estan')!;
        detalles.open = true;
        detalles.dispatchEvent(new Event('toggle'));
        await refrescar();

        expect(q('details.no-estan table.tabla-tarjetas')).toBeNull();
        expect(qa('details.no-estan [data-etiqueta]')).toHaveLength(0);
      });
    });

    describe('en el celular', () => {
      it('las nueve tarjetas de arriba no se dibujan: la fila de conteos es el filtro y el paso 3 está marcado', async () => {
        await conVistaPrevia('celular');

        expect(q('.vista-previa .tarjetas')).toBeNull();
        expect(qa('.vista-previa .conteos-celular button')).toHaveLength(5);
        expect(qa('.pasos-celular li').map((p) => p.getAttribute('aria-current'))).toEqual([null, null, 'step', null]);
      });

      it('destildar desde la hoja de detalle manda el mismo pedido que destildar la casilla', async () => {
        await conVistaPrevia('celular');

        qa<HTMLButtonElement>('li.fila-celular button.fila-celular-cuerpo')[2].click();
        await refrescar();
        q<HTMLInputElement>('app-hoja-inferior input[type="checkbox"]')!.click();
        await refrescar();
        q<HTMLElement>('app-hoja-inferior .dialogo-fondo')!.click();
        await refrescar();
        await confirmar();

        expect(servicio.aplicar).toHaveBeenCalledWith(expect.any(File), {
          ...PAYLOAD,
          crearNuevos: true,
          conteosEsperados: { ...CONTEOS_TODOS, suben: 1 },
          filasElegidas: [
            { hoja: 0, fila: 2 },
            { hoja: 0, fila: 3 },
            { hoja: 0, fila: 5 },
            { hoja: 0, fila: 6 },
          ],
        });
      });

      it('filtrar con los conteos no cambia lo que se confirma', async () => {
        await conVistaPrevia('celular');

        qa<HTMLButtonElement>('.conteos-celular button')[0].click();
        await refrescar();
        await confirmar();

        expect(servicio.aplicar.mock.calls[0][1].filasElegidas).toHaveLength(5);
        expect(servicio.aplicar.mock.calls[0][1].conteosEsperados).toEqual(CONTEOS_TODOS);
      });

      it('«No están en la lista» es una tabla de tarjetas: cada dato lleva su etiqueta', async () => {
        await conVistaPrevia('celular');
        const detalles = q<HTMLDetailsElement>('details.no-estan')!;
        detalles.open = true;
        detalles.dispatchEvent(new Event('toggle'));
        await refrescar();

        expect(q('details.no-estan table')?.classList.contains('tabla-tarjetas')).toBe(true);
        expect(qa('details.no-estan tbody tr:first-child td').map((td) => td.getAttribute('data-etiqueta'))).toEqual([
          'Código interno',
          'Código del proveedor',
          'Descripción',
        ]);
      });

      it('«No están en la lista» se puede volver a plegar', async () => {
        await conVistaPrevia('celular');
        const detalles = q<HTMLDetailsElement>('details.no-estan')!;
        detalles.open = true;
        detalles.dispatchEvent(new Event('toggle'));
        await refrescar();

        detalles.open = false;
        detalles.dispatchEvent(new Event('toggle'));
        await refrescar();

        expect(qa('details.no-estan tbody tr')).toHaveLength(0);
      });
    });
  });
});
