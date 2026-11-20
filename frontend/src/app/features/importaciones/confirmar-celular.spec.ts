import { HttpErrorResponse } from '@angular/common/http';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
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
  Importacion,
  Muestra,
  PropuestaDeMapeo,
  VistaPrevia,
} from './importacion.models';
import { Importaciones } from './importaciones';
import { ImportacionesService } from './importaciones.service';

registerLocaleData(localeEsAr);

/**
 * Confirmar, resultado y errores en el celular (movil-sectores T13d, S1, S2, S5, S6, S7). Riesgo alto (ítem 6): confirmar
 * escribe precios y crea artículos. Por eso el primer bloque fija con literales lo que se manda a `aplicar` y lo que pasa
 * cuando sale bien o mal, igual en celular y en tablet, y tiene que pasar antes y después del cambio de pantalla; el
 * segundo bloque es sólo de estructura del celular (pie fijo, conteos arriba, resultado en pantalla completa y la salida de
 * cada error).
 */
describe('Confirmar la importación en el celular (T13d)', () => {
  type Modo = 'celular' | 'tablet';
  const modos: Modo[] = ['celular', 'tablet'];
  const NINGUNO: Ajustes = { a1: null, a2: null, a3: null };

  function cambio(fila: number, anterior: number, nuevo: number, parcial: Partial<CambioPlaneado> = {}): CambioPlaneado {
    return {
      fila,
      hoja: 0,
      articuloId: fila,
      codigoProveedor: `C-${fila}`,
      descripcion: `Artículo ${fila}`,
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

  const alta = (fila: number): AltaPlaneada => ({
    fila,
    hoja: 0,
    codigo: `N-${fila}`,
    descripcion: `Nuevo ${fila}`,
    precioLista: 20,
    costo: 18,
    moneda: 'ARS',
    alicuotaIva: 'IVA_21',
    ajustes: NINGUNO,
    unidad: 'UNIDAD',
    codigoBarras: null,
    rubroId: null,
    precioNuevo: 30,
  });
  const igual = (fila: number): FilaSinCambio => ({ id: fila, hoja: 0, fila, codigo: `S-${fila}`, descripcion: `Igual ${fila}` });
  const conError = (fila: number, mensaje: string): AvisoDeFila => ({ hoja: 0, fila, codigo: '7733', mensaje, descripcion: null });

  const MENSAJE_DOLARES =
    'El costo está en dólares, pero la lista no dice la moneda. Mapeá la columna de moneda o elegí la moneda de todas las filas.';
  const MENSAJE_409 = 'La lista cambió desde la vista previa. No se aplicó nada: volvé a generar la vista previa.';

  /** Dos suben (la fila 4 con variación fuerte), una baja, un nuevo, uno sin cambio y uno con error. */
  const VISTA: VistaPrevia = {
    conteos: { suben: 2, bajan: 1, nuevos: 1, sinCambio: 1, noEstan: 2, conError: 1, cambianMoneda: 0, corrigen: 0, salteadas: 1 },
    cambios: [cambio(2, 100, 120), cambio(3, 100, 80), cambio(4, 100, 300, { variacionFuerte: true })],
    nuevos: [alta(5)],
    sinCambio: [igual(6)],
    errores: [conError(7, 'El costo no es un número.')],
    advertencias: [],
    noEstan: [
      { id: 91, codigoInterno: 'ART-091', codigoProveedor: 'P-91', descripcion: 'Perno ausente' },
      { id: 92, codigoInterno: 'ART-092', codigoProveedor: null, descripcion: 'Clavo ausente' },
    ],
    cambiosDeMoneda: [],
    correcciones: [],
  };

  const IMPORTACION: Importacion = {
    id: 7,
    proveedorId: 1,
    proveedorNombre: 'Sinteplast',
    usuarioId: 3,
    archivo: 'lista.xlsx',
    fecha: '2026-11-08T10:00:00',
    conteos: { suben: 2, bajan: 1, nuevos: 1, sinCambio: 1, noEstan: 2, conError: 1, cambianMoneda: 0, corrigen: 0, salteadas: 1 },
    estado: 'APLICADA',
  };

  const sinteplast: Proveedor = { id: 1, nombre: 'Sinteplast', cuit: null, activo: true };
  const SIN: PropuestaDeMapeo = {
    hoja: null, filaDesde: null, columnaCodigo: null, columnaDescripcion: null, columnaCosto: null, columnaMoneda: null,
    columnaSinIva: null, columnaConIva: null, columnaIva: null, columnaAjuste1: null, columnaAjuste2: null, columnaAjuste3: null,
    columnaCodigoBarras: null, columnaUnidad: null, columnaRubro: null, hojas: [],
  };
  const MUESTRA: Muestra = {
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
  const TODAS = [2, 3, 4, 5, 6].map((fila) => ({ hoja: 0, fila }));

  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const errorHttp = (status: number, message: string) => new HttpErrorResponse({ status, error: { status, message } });

  function stubPantalla(modo: Modo) {
    vi.stubGlobal('matchMedia', (c: string) => ({
      matches: c.includes(modo === 'celular' ? '639.98px' : '1023.98px'),
      media: c,
    }));
  }
  afterEach(() => vi.unstubAllGlobals());

  let fixture: ComponentFixture<Importaciones>;
  let servicio: Record<'muestra' | 'vistaPrevia' | 'aplicar', ReturnType<typeof vi.fn>>;
  let proveedores: { listar: ReturnType<typeof vi.fn> };

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
  const confirmarBoton = () => boton('Confirmar importación') ?? boton('Importando…')!;

  /** Arma la pantalla en `modo`. Sin `conVista`, queda en el paso 2 (con la muestra leída) sin pedir la vista previa. */
  async function crear(modo: Modo, opciones: { vista?: VistaPrevia | null; archivo?: File } = {}) {
    stubPantalla(modo);
    const vista = opciones.vista === undefined ? VISTA : opciones.vista;
    servicio = {
      muestra: vi.fn(() => of(MUESTRA)),
      vistaPrevia: vi.fn(() => of(vista ?? VISTA)),
      aplicar: vi.fn(() => new Subject<Importacion>()),
    };
    proveedores = { listar: vi.fn(() => of([sinteplast])) };
    await TestBed.configureTestingModule({
      imports: [Importaciones],
      providers: [
        { provide: ImportacionesService, useValue: servicio },
        { provide: ProveedoresService, useValue: proveedores },
        { provide: RubrosService, useValue: { listar: vi.fn(() => of([])) } },
        { provide: AuthService, useValue: { esDueno: signal(true) } },
        { provide: LOCALE_ID, useValue: 'es-AR' },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Importaciones);
    await refrescar();
    await elegirProveedor();
    await elegirArchivo(opciones.archivo ?? new File(['x'], 'lista.xlsx'));
    if (vista !== null) {
      boton('Ver vista previa')!.click();
      await refrescar();
    }
  }

  async function elegirProveedor() {
    const proveedor = q<HTMLSelectElement>('#proveedor')!;
    proveedor.value = '1';
    proveedor.dispatchEvent(new Event('change'));
    await refrescar();
  }

  async function elegirArchivo(archivo: File) {
    const input = q<HTMLInputElement>('#archivo')!;
    Object.defineProperty(input, 'files', { value: [archivo], configurable: true });
    input.dispatchEvent(new Event('change'));
    await refrescar();
  }

  /**
   * En el celular el paso 4 es una pantalla aparte a la que se entra con «Continuar con N filas»; en la tablet no existe ese
   * botón y la confirmación sigue en el paso 3. Así los mismos tests corren en los dos modos y en la base.
   */
  async function irAConfirmar() {
    const continuar = qa<HTMLButtonElement>('button').find((b) => normal(b.textContent).startsWith('Continuar con '));
    if (continuar) {
      continuar.click();
      await refrescar();
    }
  }

  async function confirmar() {
    await irAConfirmar();
    confirmarBoton().click();
    await refrescar();
  }

  /** La casilla «Crear los artículos nuevos» vive en el paso de confirmar. */
  async function alternarCrearNuevos() {
    await irAConfirmar();
    q<HTMLInputElement>('#crearNuevos')!.click();
    await refrescar();
  }

  async function tocar(elemento: HTMLElement) {
    elemento.click();
    await refrescar();
  }

  const desmarcarTodas = () => tocar(boton('Desmarcar todas')!);

  // =========================================================================================== la plata: pasa en la base

  describe.each(modos)('lo que se manda a aplicar, en %s', (modo) => {
    it('todas las filas: el pedido es el mapeo, crearNuevos, los conteos del archivo y las cinco filas', async () => {
      await crear(modo);

      await confirmar();

      expect(servicio.aplicar).toHaveBeenCalledTimes(1);
      expect(servicio.aplicar).toHaveBeenCalledWith(expect.any(File), {
        ...PAYLOAD,
        crearNuevos: true,
        conteosEsperados: CONTEOS_TODOS,
        filasElegidas: TODAS,
      });
      expect((servicio.aplicar.mock.calls[0][0] as File).name).toBe('lista.xlsx');
    });

    it('un subconjunto: destildar la baja (fila 3) y la que no cambia (fila 6) las saca de filasElegidas y de los conteos', async () => {
      await crear(modo);

      await tocar(casilla(3));
      await tocar(casilla(6));
      await confirmar();

      expect(servicio.aplicar).toHaveBeenCalledTimes(1);
      expect(servicio.aplicar).toHaveBeenCalledWith(expect.any(File), {
        ...PAYLOAD,
        crearNuevos: true,
        conteosEsperados: { ...CONTEOS_TODOS, bajan: 0, sinCambio: 0 },
        filasElegidas: [
          { hoja: 0, fila: 2 },
          { hoja: 0, fila: 4 },
          { hoja: 0, fila: 5 },
        ],
      });
    });

    it('con crearNuevos apagado: viaja crearNuevos false y la fila nueva sigue entre las elegidas, con su conteo', async () => {
      await crear(modo);

      await alternarCrearNuevos();
      await confirmar();

      expect(servicio.aplicar).toHaveBeenCalledTimes(1);
      expect(servicio.aplicar).toHaveBeenCalledWith(expect.any(File), {
        ...PAYLOAD,
        crearNuevos: false,
        conteosEsperados: CONTEOS_TODOS,
        filasElegidas: TODAS,
      });
    });

    it('con crearNuevos apagado y prendido de nuevo vuelve a mandar crearNuevos true', async () => {
      await crear(modo);

      await alternarCrearNuevos();
      await alternarCrearNuevos();
      await confirmar();

      expect(servicio.aplicar.mock.calls[0][1].crearNuevos).toBe(true);
    });

    it('el mapeo, la lista y el rubro que se ven son los que se mandan: bonificación 5 e IVA incluido', async () => {
      await crear(modo);
      // Cambiar un dato de la lista deja sin efecto la vista previa: se vuelve a pedir y se confirma esa.
      const bonificacion = q<HTMLInputElement>('#bonificacion1')!;
      bonificacion.value = '5';
      bonificacion.dispatchEvent(new Event('input'));
      await refrescar();
      const iva = q<HTMLInputElement>('#listaIncluyeIva')!;
      iva.click();
      await refrescar();
      boton('Ver vista previa')!.click();
      await refrescar();

      await confirmar();

      expect(servicio.aplicar).toHaveBeenCalledTimes(1);
      expect(servicio.aplicar).toHaveBeenCalledWith(expect.any(File), {
        ...PAYLOAD,
        bonificacion1: 5,
        listaIncluyeIva: true,
        crearNuevos: true,
        conteosEsperados: CONTEOS_TODOS,
        filasElegidas: TODAS,
      });
    });
  });

  describe.each(modos)('sin filas para aplicar, en %s', (modo) => {
    it('«Desmarcar todas» deja «Confirmar importación» avisando «Elegí al menos una fila.» y sin pedido', async () => {
      await crear(modo);
      await desmarcarTodas();
      await irAConfirmar();

      const b = confirmarBoton();
      expect(b.getAttribute('aria-disabled')).toBe('true');
      expect(b.disabled).toBe(false);
      expect(b.getAttribute('aria-describedby')).toBe('sin-filas-elegidas');
      expect(normal(q('#sin-filas-elegidas')?.textContent)).toBe('Elegí al menos una fila.');

      await confirmar();

      expect(servicio.aplicar).not.toHaveBeenCalled();
      expect(qa('.motivo-accion').map((p) => normal(p.textContent))).toContain('Elegí al menos una fila.');
      expect(q('.motivo-accion[role="status"]')).not.toBeNull();
      expect(q('.resumen')).toBeNull();
      expect(q('.vista-previa')).not.toBeNull();
    });

    it('sólo la fila nueva elegida y crearNuevos apagado: el motivo es el aviso de la casilla y no hay pedido', async () => {
      await crear(modo);
      await desmarcarTodas();
      await tocar(casilla(5));
      await alternarCrearNuevos();

      const aviso = 'Elegiste 1 artículo nuevo. Con la casilla apagada no se crea. Tildala para crearlo.';
      expect(normal(q('#nuevos-sin-crear')?.textContent)).toBe(aviso);
      expect(confirmarBoton().getAttribute('aria-describedby')).toBe('nuevos-sin-crear');
      expect(confirmarBoton().getAttribute('aria-disabled')).toBe('true');
      expect(q('#sin-filas-elegidas')).toBeNull();

      await confirmar();

      expect(servicio.aplicar).not.toHaveBeenCalled();
      expect(qa('.motivo-accion').map((p) => normal(p.textContent))).toContain(aviso);
    });

    it('con crearNuevos apagado y la nueva más una fila que sí se aplica, se confirma y el aviso sigue a la vista', async () => {
      await crear(modo);
      await desmarcarTodas();
      await tocar(casilla(5));
      await tocar(casilla(2));
      await alternarCrearNuevos();

      expect(confirmarBoton().getAttribute('aria-disabled')).toBeNull();
      await confirmar();

      expect(servicio.aplicar).toHaveBeenCalledTimes(1);
      expect(servicio.aplicar).toHaveBeenCalledWith(expect.any(File), {
        ...PAYLOAD,
        crearNuevos: false,
        conteosEsperados: { ...CONTEOS_TODOS, suben: 1, bajan: 0, sinCambio: 0 },
        filasElegidas: [
          { hoja: 0, fila: 2 },
          { hoja: 0, fila: 5 },
        ],
      });
    });
  });

  describe.each(modos)('un toque doble en «Confirmar importación», en %s', (modo) => {
    it('manda un solo pedido: mientras corre el botón dice «Importando…» y está apagado', async () => {
      await crear(modo);

      await confirmar();
      expect(servicio.aplicar).toHaveBeenCalledTimes(1);
      const b = confirmarBoton();
      expect(normal(b.textContent)).toBe('Importando…');
      expect(b.disabled).toBe(true);

      await confirmar();
      await confirmar();

      expect(servicio.aplicar).toHaveBeenCalledTimes(1);
      expect(q('.vista-previa')).not.toBeNull();
      expect(q('.resumen')).toBeNull();
    });

    it('con «Importando…» el archivo no se puede cambiar', async () => {
      await crear(modo);

      await confirmar();

      expect(qa<HTMLInputElement>('#archivo')[0].disabled).toBe(true);
    });
  });

  describe.each(modos)('cuando la importación se rechaza, en %s', (modo) => {
    it('un error cualquiera deja la pantalla, la selección y los conteos como estaban y muestra el mensaje', async () => {
      await crear(modo);
      await tocar(casilla(3));
      servicio.aplicar.mockReturnValue(throwError(() => errorHttp(500, 'No se pudo aplicar.')));

      await confirmar();

      expect(normal(q('[role="alert"]')?.textContent)).toBe('No se pudo aplicar.');
      expect(q('.vista-previa')).not.toBeNull();
      expect(q('.resumen')).toBeNull();
      expect(casilla(3).checked).toBe(false);
      expect(casilla(2).checked).toBe(true);
      expect(confirmarBoton().disabled).toBe(false);
      expect(normal(confirmarBoton().textContent)).toBe('Confirmar importación');
      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);

      // Reintentar manda exactamente lo mismo.
      servicio.aplicar.mockReturnValue(of(IMPORTACION));
      await confirmar();
      expect(servicio.aplicar).toHaveBeenCalledTimes(2);
      expect(servicio.aplicar.mock.calls[1][1]).toEqual(servicio.aplicar.mock.calls[0][1]);
      expect(servicio.aplicar.mock.calls[1][1].filasElegidas).toEqual([
        { hoja: 0, fila: 2 },
        { hoja: 0, fila: 4 },
        { hoja: 0, fila: 5 },
        { hoja: 0, fila: 6 },
      ]);
    });

    it('el rechazo sin mensaje dice «No se pudo aplicar la importación.»', async () => {
      await crear(modo);
      servicio.aplicar.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

      await confirmar();

      expect(normal(q('[role="alert"]')?.textContent)).toBe('No se pudo aplicar la importación.');
      expect(q('.vista-previa')).not.toBeNull();
    });

    it('un 400 con errores por campo los muestra en una lista y no aplica nada más', async () => {
      await crear(modo);
      servicio.aplicar.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: { status: 400, message: 'Hay datos inválidos.', errors: { filasElegidas: 'Elegí al menos una fila.' } },
            }),
        ),
      );

      await confirmar();

      expect(qa('ul.errores-campos li').map((li) => normal(li.textContent))).toEqual(['Elegí al menos una fila.']);
      expect(servicio.aplicar).toHaveBeenCalledTimes(1);
      expect(q('.vista-previa')).not.toBeNull();
    });

    it('un 409 conserva lo destildado: pide la vista previa de nuevo y el reintento manda la misma selección', async () => {
      await crear(modo);
      await tocar(casilla(3));
      await tocar(casilla(6));
      servicio.aplicar.mockReturnValueOnce(throwError(() => errorHttp(409, MENSAJE_409)));

      await confirmar();

      expect(normal(q('[role="alert"]')?.textContent)).toBe(MENSAJE_409);
      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(2);
      expect(servicio.vistaPrevia).toHaveBeenLastCalledWith(expect.any(File), PAYLOAD);
      expect(q('.vista-previa')).not.toBeNull();
      expect(q('.regenerar')).toBeNull();
      expect(casilla(3).checked).toBe(false);
      expect(casilla(6).checked).toBe(false);
      expect(casilla(2).checked).toBe(true);

      servicio.aplicar.mockReturnValue(of(IMPORTACION));
      await confirmar();

      expect(servicio.aplicar).toHaveBeenCalledTimes(2);
      expect(servicio.aplicar.mock.calls[1][1]).toEqual({
        ...PAYLOAD,
        crearNuevos: true,
        conteosEsperados: { ...CONTEOS_TODOS, bajan: 0, sinCambio: 0 },
        filasElegidas: [
          { hoja: 0, fila: 2 },
          { hoja: 0, fila: 4 },
          { hoja: 0, fila: 5 },
        ],
      });
    });

    it('un 409 cuando la vista previa nueva ya no trae una fila elegida avisa cuántas se perdieron', async () => {
      await crear(modo);
      servicio.aplicar.mockReturnValueOnce(throwError(() => errorHttp(409, MENSAJE_409)));
      servicio.vistaPrevia.mockReturnValue(of({ ...VISTA, cambios: VISTA.cambios.filter((c) => c.fila !== 3) }));

      await confirmar();

      expect(normal(q('.aviso-perdidas')?.textContent)).toBe('1 fila que habías elegido ya no está en la lista nueva.');
    });

    it('un 409 y la vista previa nueva que falla deja «Volver a generar la vista previa», que conserva la selección', async () => {
      await crear(modo);
      await tocar(casilla(3));
      servicio.aplicar.mockReturnValueOnce(throwError(() => errorHttp(409, MENSAJE_409)));
      servicio.vistaPrevia.mockReturnValue(throwError(() => errorHttp(500, 'Falló')));

      await confirmar();

      const regenerar = q<HTMLButtonElement>('button.regenerar')!;
      expect(normal(regenerar.textContent)).toBe('Volver a generar la vista previa');
      expect(q('.vista-previa')).toBeNull();

      servicio.vistaPrevia.mockReturnValue(of(VISTA));
      await tocar(regenerar);

      expect(q('button.regenerar')).toBeNull();
      expect(q('.vista-previa')).not.toBeNull();
      expect(casilla(3).checked).toBe(false);
      expect(casilla(2).checked).toBe(true);
    });
  });

  describe.each(modos)('la vista previa se invalida, en %s', (modo) => {
    it('cambiar un dato de la lista saca la vista previa y «Confirmar importación»: no hay nada que aplicar', async () => {
      await crear(modo);
      await irAConfirmar();
      expect(confirmarBoton()).toBeDefined();

      const bonificacion = q<HTMLInputElement>('#bonificacion1')!;
      bonificacion.value = '7';
      bonificacion.dispatchEvent(new Event('input'));
      await refrescar();

      expect(q('.vista-previa')).toBeNull();
      expect(boton('Confirmar importación')).toBeUndefined();
      expect(servicio.aplicar).not.toHaveBeenCalled();
    });

    it('elegir otro archivo saca la vista previa y vuelve a leer la lista', async () => {
      await crear(modo);

      await elegirArchivo(new File(['y'], 'otra.xlsx'));

      expect(q('.vista-previa')).toBeNull();
      expect(boton('Confirmar importación')).toBeUndefined();
      expect(servicio.muestra).toHaveBeenCalledTimes(2);
      expect((servicio.muestra.mock.calls[1][0] as File).name).toBe('otra.xlsx');
    });
  });

  describe.each(modos)('el resultado, en %s', (modo) => {
    async function aplicada(importacion: Importacion = IMPORTACION) {
      await crear(modo);
      servicio.aplicar.mockReturnValue(of(importacion));
      await confirmar();
    }

    it('«Importación aplicada» dice el archivo, que los precios están actualizados y cuántos nuevos se crearon', async () => {
      await aplicada();

      const resumen = q('.resumen')!;
      expect(normal(resumen.querySelector('h2')?.textContent)).toBe('Importación aplicada');
      expect(normal(resumen.querySelector('.ayuda')?.textContent)).toBe(
        'Archivo lista.xlsx. Los precios ya están actualizados. Se creó 1 artículo nuevo.',
      );
      expect(resumen.getAttribute('role')).toBe('status');
      expect(q('.vista-previa')).toBeNull();
      expect(q('.nuevos-no-creados')).toBeNull();
    });

    it('muestra los nueve conteos que devolvió el servidor', async () => {
      await aplicada();

      expect(qa('.resumen .tarjetas .tarjeta').map((t) => normal(t.textContent))).toEqual([
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
    });

    it('con crearNuevos apagado avisa cuántas nuevas no se crearon', async () => {
      await crear(modo);
      await alternarCrearNuevos();
      servicio.aplicar.mockReturnValue(of({ ...IMPORTACION, conteos: { ...IMPORTACION.conteos, nuevos: 0 } }));

      await confirmar();

      expect(normal(q('.nuevos-no-creados')?.textContent)).toBe(
        '1 artículo nuevo no se creó porque «Crear los artículos nuevos» estaba apagada.',
      );
    });

    it('recarga los proveedores una vez para traer el mapeo guardado', async () => {
      await crear(modo);
      servicio.aplicar.mockReturnValue(of(IMPORTACION));
      proveedores.listar.mockClear();

      await confirmar();

      expect(proveedores.listar).toHaveBeenCalledTimes(1);
    });

    it('«Importar otra lista» es el único botón del resultado y vuelve al paso 1 sin proveedor ni archivo', async () => {
      await aplicada();

      expect(qa<HTMLButtonElement>('.resumen button').map((b) => normal(b.textContent))).toEqual(['Importar otra lista']);
      await tocar(boton('Importar otra lista')!);

      expect(q('.resumen')).toBeNull();
      expect(q<HTMLSelectElement>('#proveedor')!.value).toBe('');
      expect(normal(q('.nombre-archivo')?.textContent)).toBe('Ningún archivo elegido');
      expect(q('.vista-previa')).toBeNull();
      expect(servicio.aplicar).toHaveBeenCalledTimes(1);
    });

    it('un resultado sin cambios de precio no dice que los precios se actualizaron', async () => {
      await aplicada({ ...IMPORTACION, conteos: { ...IMPORTACION.conteos, suben: 0, bajan: 0, nuevos: 3 } });

      expect(normal(q('.resumen .ayuda')?.textContent)).toBe('Archivo lista.xlsx. Se crearon 3 artículos nuevos.');
    });
  });

  describe.each(modos)('los errores y su salida, en %s', (modo) => {
    const grande = () => {
      const archivo = new File(['x'], 'enorme.xlsx');
      Object.defineProperty(archivo, 'size', { value: 25 * 1024 * 1024 + 1 });
      return archivo;
    };

    it('un archivo de más de 25 MB se rechaza antes de subirlo, con el texto de siempre y sin archivo elegido', async () => {
      await crear(modo, { vista: null });
      servicio.muestra.mockClear();

      await elegirArchivo(grande());

      expect(normal(q('[role="alert"]')?.textContent)).toBe('El archivo supera el máximo de 25 MB.');
      expect(servicio.muestra).not.toHaveBeenCalled();
      expect(normal(q('.nombre-archivo')?.textContent)).toBe('Ningún archivo elegido');
    });

    it('un archivo que no es planilla ni PDF se rechaza con el texto de siempre', async () => {
      await crear(modo, { vista: null });
      servicio.muestra.mockClear();

      await elegirArchivo(new File(['x'], 'foto.png'));

      expect(normal(q('[role="alert"]')?.textContent)).toBe(
        'El archivo tiene que ser una planilla de Excel (.xlsx o .xls), un CSV o un PDF.',
      );
      expect(servicio.muestra).not.toHaveBeenCalled();
    });

    it.each([
      'El PDF no tiene texto para leer. Pedile al proveedor la lista en Excel o en un PDF que no sea escaneado.',
      'La lista es demasiado grande para leerla. Dividila en archivos más chicos.',
    ])('«%s» al leer la lista: queda el mensaje del servidor, sin columnas, y se puede elegir otro archivo', async (mensaje) => {
      await crear(modo, { vista: null, archivo: new File(['x'], 'lista.pdf') });
      servicio.muestra.mockReturnValue(throwError(() => errorHttp(400, mensaje)));

      await elegirArchivo(new File(['x'], 'malo.pdf'));

      expect(normal(q('[role="alert"]')?.textContent)).toBe(mensaje);
      expect(q('app-mapeo-columnas')).toBeNull();
      expect(boton('Ver vista previa')).toBeUndefined();

      servicio.muestra.mockReturnValue(of(MUESTRA));
      await elegirArchivo(new File(['x'], 'bueno.xlsx'));

      expect(q('[role="alert"]')).toBeNull();
      expect(q('app-mapeo-columnas')).not.toBeNull();
      expect((servicio.muestra.mock.calls.at(-1)![0] as File).name).toBe('bueno.xlsx');
    });

    it('una lectura que falla sin mensaje dice «No se pudo leer la lista.»', async () => {
      await crear(modo, { vista: null });
      servicio.muestra.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

      await elegirArchivo(new File(['x'], 'malo.xlsx'));

      expect(normal(q('[role="alert"]')?.textContent)).toBe('No se pudo leer la lista.');
    });

    it('la fila con «dólares sin moneda» dice el texto del servidor, no se puede elegir y la vista previa sigue', async () => {
      const vista: VistaPrevia = {
        ...VISTA,
        conteos: { ...VISTA.conteos, conError: 1 },
        errores: [conError(7, MENSAJE_DOLARES)],
      };
      await crear(modo, { vista });

      expect(normal(q('.vista-previa')?.textContent)).toContain(MENSAJE_DOLARES);
      expect(casilla(7)).toBeUndefined();
      expect(q('.vista-previa')).not.toBeNull();
    });

    it('si falta el proveedor al pedir la vista previa dice «Elegí el proveedor.» y no la pide', async () => {
      await crear(modo, { vista: null });
      const proveedor = q<HTMLSelectElement>('#proveedor')!;
      proveedor.value = '';
      proveedor.dispatchEvent(new Event('change'));
      await refrescar();
      // Elegir el proveedor de nuevo reinicia el mapeo; se saca otra vez para dejar el pedido sin proveedor.
      await elegirArchivo(new File(['x'], 'lista.xlsx'));
      proveedor.value = '';
      proveedor.dispatchEvent(new Event('change'));
      await refrescar();

      boton('Ver vista previa')!.click();
      await refrescar();

      expect(normal(q('[role="alert"]')?.textContent)).toBe('Elegí el proveedor.');
      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
    });
  });

  // ====================================================================== estructura del celular (rojo antes del código)

  const pasos = () => qa('.pasos-celular li').map((li) => li.getAttribute('aria-current'));
  const pie = (clase: string) => el().querySelector<HTMLElement>(`:scope > .ficha-pie.en-pagina.pie-importar.${clase}`);
  const textosDe = (selector: string) => qa(selector).map((e) => normal(e.textContent));
  const cuenta = (n: number) => `Continuar con ${n} ${n === 1 ? 'fila' : 'filas'}`;

  describe('en el celular: revisar termina en «Continuar»', () => {
    it('hay un pie fijo con un solo botón, «Continuar con 5 filas», y el paso 3 está marcado', async () => {
      await crear('celular');

      const p = pie('pie-revisar')!;
      expect(p).not.toBeNull();
      expect(Array.from(p.querySelectorAll('button')).map((b) => normal(b.textContent))).toEqual([cuenta(5)]);
      expect(pasos()).toEqual([null, null, 'step', null]);
      expect(pie('pie-confirmar')).toBeNull();
    });

    it('el número es el de las filas elegidas: 4 al destildar una, 1 en singular y 0 sigue habilitado', async () => {
      await crear('celular');

      await tocar(casilla(3));
      expect(textosDe('.pie-revisar button')).toEqual([cuenta(4)]);

      await desmarcarTodas();
      expect(textosDe('.pie-revisar button')).toEqual([cuenta(0)]);
      const continuar = q<HTMLButtonElement>('.pie-revisar button')!;
      expect(continuar.disabled).toBe(false);
      expect(continuar.getAttribute('aria-disabled')).toBeNull();

      await tocar(casilla(2));
      expect(textosDe('.pie-revisar button')).toEqual([cuenta(1)]);
    });

    it('en el paso 3 ya no están «Confirmar importación» ni «Crear los artículos nuevos»: son del paso 4', async () => {
      await crear('celular');

      expect(boton('Confirmar importación')).toBeUndefined();
      expect(q('#crearNuevos')).toBeNull();
      expect(q('.confirmar-celular')).toBeNull();
      expect(q('.vista-previa.confirmando')).toBeNull();
    });

    it('«Continuar» no pide nada al servidor', async () => {
      await crear('celular');

      await tocar(boton(cuenta(5))!);

      expect(servicio.aplicar).not.toHaveBeenCalled();
      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
    });

    it('sin vista previa no hay pie de revisar ni de confirmar', async () => {
      await crear('celular', { vista: null });

      expect(pie('pie-revisar')).toBeNull();
      expect(pie('pie-confirmar')).toBeNull();
    });
  });

  describe('en la tablet: nada de eso aparece', () => {
    it('«Confirmar importación» y la casilla siguen en el paso 3, sin pies, sin «Continuar» y sin pantalla de confirmar', async () => {
      await crear('tablet');

      expect(q('.vista-previa button.confirmar')).not.toBeNull();
      expect(q('.vista-previa #crearNuevos')).not.toBeNull();
      expect(q('.ficha-pie')).toBeNull();
      expect(qa<HTMLButtonElement>('button').filter((b) => normal(b.textContent).startsWith('Continuar'))).toHaveLength(0);
      expect(q('.confirmar-celular')).toBeNull();
      expect(q('.vista-previa.confirmando')).toBeNull();
      expect(q('.conteos-confirmar')).toBeNull();
      expect(q('.pasos-celular')).toBeNull();
    });
  });

  describe('en el celular: la pantalla de confirmar', () => {
    const continuar = () => qa<HTMLButtonElement>('.pie-revisar button')[0];

    it('reemplaza al pie de revisar: «Volver» y «Confirmar importación», y el paso 4 está marcado', async () => {
      await crear('celular');
      await tocar(continuar());

      expect(pie('pie-revisar')).toBeNull();
      const p = pie('pie-confirmar')!;
      expect(p).not.toBeNull();
      expect(Array.from(p.querySelectorAll('button')).map((b) => normal(b.textContent))).toEqual(['Volver', 'Confirmar importación']);
      expect(pasos()).toEqual([null, null, null, 'step']);
      expect(q('.vista-previa.confirmando')).not.toBeNull();
      expect(normal(q('.confirmar-celular h2')?.textContent)).toBe('4. Confirmar');
    });

    it('hay un solo «Confirmar importación», en el pie, con las mismas clases y la misma guarda de siempre', async () => {
      await crear('celular');
      await tocar(continuar());

      const botones = qa<HTMLButtonElement>('button.confirmar');
      expect(botones).toHaveLength(1);
      expect(botones[0].closest('.pie-confirmar')).not.toBeNull();
      expect(botones[0].classList.contains('primario')).toBe(true);
      expect(botones[0].type).toBe('button');
      expect(q('.vista-previa button.confirmar')).toBeNull();
      expect(qa('button').filter((b) => normal(b.textContent) === 'Confirmar importación')).toHaveLength(1);
    });

    it('arriba van los conteos de lo elegido: sólo los tipos que se aplican y tienen filas', async () => {
      await crear('celular');
      await tocar(continuar());

      const conteos = () => qa('.conteos-confirmar .conteo-confirmar').map((c) => [normal(c.querySelector('span')?.textContent), normal(c.querySelector('b')?.textContent)]);
      expect(conteos()).toEqual([['Suben', '2'], ['Bajan', '1'], ['Nuevos', '1'], ['Sin cambio', '1']]);
    });

    it('los conteos siguen lo destildado en la revisión: sin la baja ni la que no cambia quedan Suben 2 y Nuevos 1', async () => {
      await crear('celular');
      await tocar(casilla(3));
      await tocar(casilla(6));
      await tocar(continuar());

      expect(qa('.conteos-confirmar .conteo-confirmar').map((c) => [normal(c.querySelector('span')?.textContent), normal(c.querySelector('b')?.textContent)])).toEqual([
        ['Suben', '2'],
        ['Nuevos', '1'],
      ]);
      expect(textosDe('.datos-confirmar dd')).toEqual(['lista.xlsx', 'Sinteplast', '3']);
    });

    it('resume el archivo, el proveedor y las filas elegidas', async () => {
      await crear('celular');
      await tocar(continuar());

      expect(textosDe('.datos-confirmar dt')).toEqual(['Archivo', 'Proveedor', 'Filas elegidas']);
      expect(textosDe('.datos-confirmar dd')).toEqual(['lista.xlsx', 'Sinteplast', '5']);
    });

    it('la única decisión es «Crear los artículos nuevos»: una casilla tildada, con el texto de siempre, entre los conteos y el resumen', async () => {
      await crear('celular');
      await tocar(continuar());

      const casillas = qa<HTMLInputElement>('.confirmar-celular input');
      expect(casillas).toHaveLength(1);
      expect(casillas[0].id).toBe('crearNuevos');
      expect(casillas[0].type).toBe('checkbox');
      expect(casillas[0].checked).toBe(true);
      const fila = casillas[0].closest('label.casilla')!;
      expect(normal(fila.textContent)).toBe('Crear los artículos nuevos');
      expect(qa('.confirmar-celular select, .confirmar-celular textarea')).toHaveLength(0);
      const antes = (a: Element, b: Element) => Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
      expect(antes(q('.conteos-confirmar')!, fila)).toBe(true);
      expect(antes(fila, q('.datos-confirmar')!)).toBe(true);
    });

    it('apagar la casilla muestra el aviso de siempre, que la casilla y el botón citan, y los conteos no cambian', async () => {
      await crear('celular');
      await tocar(continuar());

      await tocar(q<HTMLInputElement>('#crearNuevos')!);

      expect(normal(q('#nuevos-sin-crear')?.textContent)).toBe('Elegiste 1 artículo nuevo. Con la casilla apagada no se crea. Tildala para crearlo.');
      expect(q('#crearNuevos')!.getAttribute('aria-describedby')).toBe('nuevos-sin-crear');
      expect(qa('.conteos-confirmar .conteo-confirmar')).toHaveLength(4);
    });

    it('«Confirmar importación» del pie manda el mismo pedido que en la tablet', async () => {
      await crear('celular');
      await tocar(continuar());

      await tocar(q<HTMLButtonElement>('.pie-confirmar button.confirmar')!);

      expect(servicio.aplicar).toHaveBeenCalledTimes(1);
      expect(servicio.aplicar).toHaveBeenCalledWith(expect.any(File), {
        ...PAYLOAD,
        crearNuevos: true,
        conteosEsperados: CONTEOS_TODOS,
        filasElegidas: TODAS,
      });
    });

    it('sin filas elegidas «Continuar» sigue andando y el pie apaga «Confirmar» explicando por qué, sin pedido', async () => {
      await crear('celular');
      await desmarcarTodas();
      await tocar(continuar());

      const confirmarEnPie = q<HTMLButtonElement>('.pie-confirmar button.confirmar')!;
      expect(confirmarEnPie.getAttribute('aria-disabled')).toBe('true');
      expect(normal(q('.confirmar-celular #sin-filas-elegidas')?.textContent)).toBe('Elegí al menos una fila.');
      expect(textosDe('.conteos-confirmar .conteo-confirmar')).toEqual([]);

      await tocar(confirmarEnPie);

      expect(servicio.aplicar).not.toHaveBeenCalled();
      expect(normal(q('.pie-confirmar .motivo-accion')?.textContent)).toBe('Elegí al menos una fila.');
      expect(q('.pie-confirmar .motivo-accion')!.getAttribute('role')).toBe('status');
    });

    it('mientras corre dice «Importando…», queda apagado y «Volver» también', async () => {
      await crear('celular');
      await tocar(continuar());

      await tocar(q<HTMLButtonElement>('.pie-confirmar button.confirmar')!);

      const botones = qa<HTMLButtonElement>('.pie-confirmar button');
      expect(botones.map((b) => normal(b.textContent))).toEqual(['Volver', 'Importando…']);
      expect(botones.map((b) => b.disabled)).toEqual([true, true]);
    });

    it('«Volver» vuelve a la revisión sin pedir nada y con la selección como estaba', async () => {
      await crear('celular');
      await tocar(casilla(3));
      await tocar(continuar());

      await tocar(boton('Volver')!);

      expect(pie('pie-confirmar')).toBeNull();
      expect(textosDe('.pie-revisar button')).toEqual([cuenta(4)]);
      expect(q('.vista-previa.confirmando')).toBeNull();
      expect(q('.confirmar-celular')).toBeNull();
      expect(pasos()).toEqual([null, null, 'step', null]);
      expect(casilla(3).checked).toBe(false);
      expect(casilla(2).checked).toBe(true);
      expect(servicio.aplicar).not.toHaveBeenCalled();
      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
    });

    it('lo que se apaga en el paso 4 sigue apagado al volver y al entrar de nuevo', async () => {
      await crear('celular');
      await tocar(continuar());
      await tocar(q<HTMLInputElement>('#crearNuevos')!);

      await tocar(boton('Volver')!);
      await tocar(continuar());

      expect(q<HTMLInputElement>('#crearNuevos')!.checked).toBe(false);
    });

    it('al entrar el foco queda en la pantalla y no en un campo; al volver, en la revisión', async () => {
      await crear('celular');
      await tocar(continuar());

      expect(document.activeElement).toBe(q('.confirmar-celular'));
      expect(document.activeElement?.matches('input, select, textarea')).toBeFalsy();

      await tocar(boton('Volver')!);

      expect(document.activeElement).toBe(q('.vista-previa'));
    });

    it('un rechazo deja la pantalla de confirmar, con el mensaje y «Confirmar» habilitado para reintentar', async () => {
      await crear('celular');
      await tocar(continuar());
      servicio.aplicar.mockReturnValue(throwError(() => errorHttp(500, 'No se pudo aplicar.')));

      await tocar(q<HTMLButtonElement>('.pie-confirmar button.confirmar')!);

      expect(normal(q('[role="alert"]')?.textContent)).toBe('No se pudo aplicar.');
      expect(pie('pie-confirmar')).not.toBeNull();
      expect(q<HTMLButtonElement>('.pie-confirmar button.confirmar')!.disabled).toBe(false);
      expect(textosDe('.pie-confirmar button')).toEqual(['Volver', 'Confirmar importación']);
    });

    it('un 409 vuelve a la revisión con la vista previa nueva y el aviso, no a la pantalla de confirmar', async () => {
      await crear('celular');
      await tocar(continuar());
      servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE_409)));

      await tocar(q<HTMLButtonElement>('.pie-confirmar button.confirmar')!);

      expect(normal(q('[role="alert"]')?.textContent)).toBe(MENSAJE_409);
      expect(pie('pie-confirmar')).toBeNull();
      expect(textosDe('.pie-revisar button')).toEqual([cuenta(5)]);
      expect(q('.vista-previa.confirmando')).toBeNull();
    });

    it('cambiar un dato de la lista saca la vista previa y los dos pies', async () => {
      await crear('celular');
      await tocar(continuar());

      const bonificacion = q<HTMLInputElement>('#bonificacion1')!;
      bonificacion.value = '7';
      bonificacion.dispatchEvent(new Event('input'));
      await refrescar();

      expect(pie('pie-confirmar')).toBeNull();
      expect(pie('pie-revisar')).toBeNull();
      expect(pasos()).toEqual([null, 'step', null, null]);
      expect(boton('Confirmar importación')).toBeUndefined();
    });

    it('una vista previa nueva (a mano) abre en la revisión, no en confirmar', async () => {
      await crear('celular');
      await tocar(continuar());
      servicio.aplicar.mockReturnValue(throwError(() => errorHttp(500, 'Falló')));
      await tocar(q<HTMLButtonElement>('.pie-confirmar button.confirmar')!);

      q<HTMLButtonElement>('button.generar')!.click();
      await refrescar();

      expect(pie('pie-confirmar')).toBeNull();
      expect(pie('pie-revisar')).not.toBeNull();
    });
  });

  describe('en el celular: el resultado es una pantalla completa', () => {
    async function aplicada() {
      await crear('celular');
      servicio.aplicar.mockReturnValue(of(IMPORTACION));
      await confirmar();
    }

    it('«Importación aplicada» es la sección de resultado a pantalla completa, sin barra de pasos ni pies de los pasos', async () => {
      await aplicada();

      const seccion = q('section.resumen.resumen-celular')!;
      expect(seccion).not.toBeNull();
      expect(normal(seccion.querySelector('h2')?.textContent)).toBe('Importación aplicada');
      expect(q('.pasos-celular')).toBeNull();
      expect(q('.pie-importar')).toBeNull();
      expect(q('.vista-previa')).toBeNull();
    });

    it('la acción que ya existe, «Importar otra lista», queda en un pie fijo dentro de la pantalla; no hay otras', async () => {
      await aplicada();

      const p = q('section.resumen > .ficha-pie')!;
      expect(Array.from(p.querySelectorAll('button')).map((b) => normal(b.textContent))).toEqual(['Importar otra lista']);
      expect(qa('.resumen button')).toHaveLength(1);
      expect(qa('.resumen a')).toHaveLength(0);
      expect(boton('Ver artículos')).toBeUndefined();
    });

    it('lleva los mismos conteos y las mismas frases que en la tablet', async () => {
      await aplicada();

      expect(normal(q('.resumen .ayuda')?.textContent)).toBe('Archivo lista.xlsx. Los precios ya están actualizados. Se creó 1 artículo nuevo.');
      expect(textosDe('.resumen .tarjetas .tarjeta')).toHaveLength(9);
    });

    it('«Importar otra lista» vuelve al paso 1: barra de pasos con el 1 marcado y «Elegir archivo…» en el pie', async () => {
      await aplicada();

      await tocar(q<HTMLButtonElement>('.resumen .ficha-pie button')!);

      expect(q('.resumen')).toBeNull();
      expect(pasos()).toEqual(['step', null, null, null]);
      expect(textosDe(':scope > .pie-importar button')).toEqual(['Elegir archivo…']);
    });

    it('en la tablet el resultado es el de siempre: sin pantalla completa ni pie', async () => {
      await crear('tablet');
      servicio.aplicar.mockReturnValue(of(IMPORTACION));
      await confirmar();

      expect(q('.resumen')).not.toBeNull();
      expect(q('.resumen-celular')).toBeNull();
      expect(q('.resumen .ficha-pie')).toBeNull();
      expect(qa('.resumen > button').map((b) => normal(b.textContent))).toEqual(['Importar otra lista']);
    });
  });

  describe('en el celular: cada error tiene su salida al lado', () => {
    const grande = () => {
      const archivo = new File(['x'], 'enorme.xlsx');
      Object.defineProperty(archivo, 'size', { value: 25 * 1024 * 1024 + 1 });
      return archivo;
    };
    const pieDelArchivo = () => el().querySelector<HTMLButtonElement>(':scope > .pie-importar button')!;

    it.each([
      ['un archivo de más de 25 MB', () => elegirArchivo(grande())],
      ['un archivo que no es una planilla', () => elegirArchivo(new File(['x'], 'foto.png'))],
      [
        'un PDF sin texto para leer',
        async () => {
          servicio.muestra.mockReturnValue(throwError(() => errorHttp(400, 'El PDF no tiene texto para leer. Pedile al proveedor la lista en Excel o en un PDF que no sea escaneado.')));
          await elegirArchivo(new File(['x'], 'malo.pdf'));
        },
      ],
      [
        'una lista demasiado grande para leerla',
        async () => {
          servicio.muestra.mockReturnValue(throwError(() => errorHttp(400, 'La lista es demasiado grande para leerla. Dividila en archivos más chicos.')));
          await elegirArchivo(new File(['x'], 'enorme.csv'));
        },
      ],
    ] as [string, () => Promise<void>][])('%s: el pie ofrece «Elegir otro archivo» y abre el mismo selector', async (_nombre, provocar) => {
      await crear('celular', { vista: null });
      await provocar();

      expect(q('[role="alert"]')).not.toBeNull();
      expect(textosDe(':scope > .pie-importar button')).toEqual(['Elegir otro archivo']);
      const abrir = vi.spyOn(q<HTMLInputElement>('#archivo')!, 'click');
      pieDelArchivo().click();
      expect(abrir).toHaveBeenCalledTimes(1);
    });

    it('elegir otro archivo y leerlo bien saca el aviso y deja el pie del paso 2', async () => {
      await crear('celular', { vista: null });
      await elegirArchivo(grande());
      servicio.muestra.mockReturnValue(of(MUESTRA));

      await elegirArchivo(new File(['x'], 'bueno.xlsx'));

      expect(q('[role="alert"]')).toBeNull();
      expect(textosDe(':scope > .pie-importar button')).toEqual(['Ver vista previa']);
    });

    it('un error que no es del archivo (no se cargaron los proveedores) deja «Elegir archivo…»', async () => {
      stubPantalla('celular');
      servicio = { muestra: vi.fn(), vistaPrevia: vi.fn(), aplicar: vi.fn() };
      await TestBed.configureTestingModule({
        imports: [Importaciones],
        providers: [
          { provide: ImportacionesService, useValue: servicio },
          { provide: ProveedoresService, useValue: { listar: vi.fn(() => throwError(() => errorHttp(500, 'Falló'))) } },
          { provide: RubrosService, useValue: { listar: vi.fn(() => of([])) } },
          { provide: AuthService, useValue: { esDueno: signal(true) } },
          { provide: LOCALE_ID, useValue: 'es-AR' },
        ],
      }).compileComponents();
      fixture = TestBed.createComponent(Importaciones);
      await refrescar();

      expect(normal(q('[role="alert"]')?.textContent)).toBe('No se pudieron cargar los proveedores.');
      expect(textosDe(':scope > .pie-importar button')).toEqual(['Elegir archivo…']);
    });

    it('dólares sin moneda: una tarjeta con el texto del servidor y «Ir a Más ajustes», que abre «Más ajustes» y enfoca la moneda', async () => {
      const vista: VistaPrevia = { ...VISTA, errores: [conError(7, MENSAJE_DOLARES)] };
      await crear('celular', { vista });

      const tarjeta = q('.aviso-dolares')!;
      expect(normal(tarjeta.querySelector('p')?.textContent)).toBe(MENSAJE_DOLARES);
      const detalles = q<HTMLDetailsElement>('details.mas-ajustes')!;
      expect(detalles.open).toBe(false);

      await tocar(Array.from(tarjeta.querySelectorAll('button')).find((b) => normal(b.textContent) === 'Ir a Más ajustes')!);

      expect(detalles.open).toBe(true);
      expect(document.activeElement?.id).toBe('monedaFija');
      expect(servicio.aplicar).not.toHaveBeenCalled();
      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
    });

    it('sin el error de dólares no hay tarjeta ni salida a «Más ajustes»', async () => {
      await crear('celular');

      expect(q('.aviso-dolares')).toBeNull();
      expect(boton('Ir a Más ajustes')).toBeUndefined();
    });

    it('otro error de fila (no el de dólares) no lleva la salida a «Más ajustes»', async () => {
      await crear('celular', { vista: { ...VISTA, errores: [conError(7, 'El costo no es un número.')] } });

      expect(q('.aviso-dolares')).toBeNull();
    });

    it('la lista cambió: el botón «Volver a generar la vista previa» es el que ya existe y va pegado al aviso', async () => {
      await crear('celular');
      servicio.aplicar.mockReturnValueOnce(throwError(() => errorHttp(409, MENSAJE_409)));
      servicio.vistaPrevia.mockReturnValue(throwError(() => errorHttp(500, 'Falló')));
      await confirmar();

      const regenerar = q<HTMLButtonElement>('button.regenerar')!;
      expect(normal(regenerar.textContent)).toBe('Volver a generar la vista previa');
      expect(regenerar.previousElementSibling).toBe(q('[role="alert"]'));
    });

    it('en la tablet un error de archivo no suma pie ni cambia de texto, y no hay tarjeta de dólares', async () => {
      await crear('tablet', { vista: { ...VISTA, errores: [conError(7, MENSAJE_DOLARES)] } });

      expect(q('.ficha-pie')).toBeNull();
      expect(q('.aviso-dolares')).toBeNull();
      expect(boton('Ir a Más ajustes')).toBeUndefined();
    });
  });
});
