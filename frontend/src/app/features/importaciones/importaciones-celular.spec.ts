import { registerLocaleData } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { Rubro } from '../rubros/rubro.models';
import { RubrosService } from '../rubros/rubros.service';
import { Muestra, PropuestaDeMapeo, VistaPrevia } from './importacion.models';
import { Importaciones } from './importaciones';
import { ImportacionesService } from './importaciones.service';

registerLocaleData(localeEsAr);

/**
 * Importar lista de precios en el celular, paso 1 y lectura (movil-sectores T13a, S1, S2, S5, S6, S7): sólo cambia cómo
 * se ve. Los pedidos, los textos, los errores y el rol corren en celular y en tablet con los mismos literales (pasan en
 * la base); los de estructura miran lo propio del celular: la barra de cuatro pasos, el pie fijo con la acción del paso y
 * su motivo, y la lectura como tarjeta con barra de progreso. En la tablet no aparece nada de eso.
 */
describe('Importar lista de precios en el celular (T13a y T13b)', () => {
  type Modo = 'celular' | 'tablet';

  const sinteplast: Proveedor = { id: 1, nombre: 'Sinteplast', cuit: null, activo: true };
  const tersuave: Proveedor = { id: 2, nombre: 'Tersuave', cuit: null, activo: true };

  const SIN_PROPUESTA: PropuestaDeMapeo = {
    hoja: null,
    filaDesde: null,
    columnaCodigo: null,
    columnaDescripcion: null,
    columnaCosto: null,
    columnaMoneda: null,
    columnaSinIva: null,
    columnaConIva: null,
    columnaIva: null,
    columnaAjuste1: null,
    columnaAjuste2: null,
    columnaAjuste3: null,
    columnaCodigoBarras: null,
    columnaUnidad: null,
    columnaRubro: null,
    hojas: [],
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
    propuesta: { ...SIN_PROPUESTA, hoja: 0, filaDesde: 2, columnaCodigo: 0, columnaDescripcion: 1, columnaCosto: 2, hojas: [0] },
  };

  const pinturas: Rubro = { id: 1, nombre: 'Pinturas', margenPorcentaje: 30 };
  const muestraConHojas: Muestra = {
    hojas: [
      muestra.hojas[0],
      { indice: 1, nombre: 'Ofertas', columnas: 2, filaEncabezado: null, filas: [{ numero: 1, celdas: ['X-1', '10'] }] },
    ],
    propuesta: { ...muestra.propuesta!, hojas: [0] },
  };
  const muestraSinPropuesta: Muestra = { hojas: muestra.hojas, propuesta: SIN_PROPUESTA };
  const vistaVacia = {
    conteos: { suben: 0, bajan: 0, nuevos: 0, sinCambio: 0, noEstan: 0, conError: 0, cambianMoneda: 0, corrigen: 0, salteadas: 0 },
    cambios: [],
    nuevos: [],
    errores: [],
    advertencias: [],
    noEstan: [],
    cambiosDeMoneda: [],
    correcciones: [],
    sinCambio: [],
  } as VistaPrevia;

  let fixture: ComponentFixture<Importaciones>;
  let servicio: Record<'muestra' | 'vistaPrevia' | 'aplicar', ReturnType<typeof vi.fn>>;
  let proveedoresServicio: { listar: ReturnType<typeof vi.fn> };

  const errorHttp = (status: number, message: string) => new HttpErrorResponse({ status, error: { status, message } });

  async function crear(opciones: { modo?: Modo; dueno?: boolean } = {}) {
    const modo = opciones.modo ?? 'celular';
    const dueno = opciones.dueno ?? true;
    vi.stubGlobal('matchMedia', (c: string) => ({
      matches: c.includes(modo === 'celular' ? '639.98px' : '1023.98px'),
      media: c,
    }));
    servicio = { muestra: vi.fn(() => of(muestra)), vistaPrevia: vi.fn(), aplicar: vi.fn() };
    proveedoresServicio = { listar: vi.fn(() => of([sinteplast, tersuave])) };
    await TestBed.configureTestingModule({
      imports: [Importaciones],
      providers: [
        { provide: ImportacionesService, useValue: servicio },
        { provide: ProveedoresService, useValue: proveedoresServicio },
        { provide: RubrosService, useValue: { listar: vi.fn(() => of([pinturas])) } },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
        { provide: LOCALE_ID, useValue: 'es-AR' },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Importaciones);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => vi.unstubAllGlobals());

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends Element = HTMLElement>(selector: string) => el().querySelector<T>(selector);
  const qa = (selector: string) => Array.from(el().querySelectorAll<HTMLElement>(selector));
  const boton = (texto: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => normal(b.textContent) === texto);
  const pasos = () => qa('.pasos-celular li');
  const pie = () => q('.ficha-pie.en-pagina');
  const lista = (nombre = 'lista.xlsx') => new File(['x'], nombre);

  async function elegirArchivo(archivo: File) {
    const input = q<HTMLInputElement>('#archivo')!;
    Object.defineProperty(input, 'files', { value: [archivo], configurable: true });
    input.dispatchEvent(new Event('change'));
    await refrescar();
  }

  const modos: Modo[] = ['celular', 'tablet'];

  // ------------------------------------------------------------------------------ lo que no cambia (pasa en la base)

  describe.each(modos)('lo de siempre, en %s', (modo) => {
    it('el Dueño ve la ayuda, pide los proveedores una vez y los lista en el select', async () => {
      await crear({ modo });

      expect(normal(q('app-barra-acciones h1')?.textContent)).toBe('Importar lista de precios');
      expect(normal(q('p.ayuda')?.textContent)).toBe(
        'Subí la lista del proveedor (Excel, CSV o PDF), indicá en qué columnas están el código y el costo, y revisá los cambios antes de confirmar.',
      );
      expect(proveedoresServicio.listar).toHaveBeenCalledTimes(1);
      const opciones = Array.from(q<HTMLSelectElement>('#proveedor')!.options).map((o) => normal(o.textContent));
      expect(opciones).toEqual(['Elegí un proveedor', 'Sinteplast', 'Tersuave']);
      expect(servicio.muestra).not.toHaveBeenCalled();
    });

    it('el título del paso 1 y los textos del selector de archivo son los de hoy', async () => {
      await crear({ modo });

      expect(normal(q('.paso > h2')?.textContent)).toBe('1. Proveedor y archivo');
      expect(normal(q('app-selector-archivo .rotulo')?.textContent)).toBe('Archivo de la lista');
      expect(normal(q('app-selector-archivo .nombre-archivo')?.textContent)).toBe('Ningún archivo elegido');
      expect(normal(q('app-selector-archivo .ayuda')?.textContent)).toBe(
        'Excel (.xlsx, .xls), CSV o PDF, hasta 25 MB. También podés arrastrarlo hasta acá.',
      );
      expect(q('#archivo')?.getAttribute('accept')).toBe('.xlsx,.xls,.csv,.pdf');
    });

    it('elegir un archivo lo manda a leer, una sola vez y con ese archivo', async () => {
      await crear({ modo });
      const archivo = lista();

      await elegirArchivo(archivo);

      expect(servicio.muestra).toHaveBeenCalledTimes(1);
      expect(servicio.muestra).toHaveBeenCalledWith(archivo);
      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
      expect(servicio.aplicar).not.toHaveBeenCalled();
      expect(normal(q('.nombre-archivo')?.textContent)).toBe('lista.xlsx');
    });

    it('un archivo de más de 25 MB muestra el aviso y no se lee', async () => {
      await crear({ modo });
      const grande = lista();
      Object.defineProperty(grande, 'size', { value: 25 * 1024 * 1024 + 1 });

      await elegirArchivo(grande);

      expect(normal(q('[role="alert"]')?.textContent)).toBe('El archivo supera el máximo de 25 MB.');
      expect(servicio.muestra).not.toHaveBeenCalled();
      expect(normal(q('.nombre-archivo')?.textContent)).toBe('Ningún archivo elegido');
    });

    it('una extensión que no es de planilla muestra el aviso y no se lee', async () => {
      await crear({ modo });

      await elegirArchivo(lista('foto.png'));

      expect(normal(q('[role="alert"]')?.textContent)).toBe(
        'El archivo tiene que ser una planilla de Excel (.xlsx o .xls), un CSV o un PDF.',
      );
      expect(servicio.muestra).not.toHaveBeenCalled();
    });

    it('si no se puede leer, el error es el del servidor y el archivo queda elegido', async () => {
      await crear({ modo });
      servicio.muestra.mockReturnValue(throwError(() => errorHttp(422, 'La planilla está dañada.')));

      await elegirArchivo(lista());

      expect(normal(q('[role="alert"]')?.textContent)).toBe('La planilla está dañada.');
      expect(normal(q('.nombre-archivo')?.textContent)).toBe('lista.xlsx');
      expect(q('.leyendo')).toBeNull();
    });

    it('sin mensaje del servidor el error de lectura es «No se pudo leer la lista.»', async () => {
      await crear({ modo });
      servicio.muestra.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

      await elegirArchivo(lista());

      expect(normal(q('[role="alert"]')?.textContent)).toBe('No se pudo leer la lista.');
    });

    it('mientras lee, el aviso es un status que rota cada 3 segundos y se queda en el último', async () => {
      await crear({ modo });
      servicio.muestra.mockReturnValue(new Subject<Muestra>());
      vi.useFakeTimers();
      try {
        await elegirArchivo(lista());
        const aviso = () => normal(q('p.leyendo')?.textContent);

        expect(q('p.leyendo')?.getAttribute('role')).toBe('status');
        expect(aviso()).toBe('Leyendo la lista…');
        vi.advanceTimersByTime(3000);
        fixture.detectChanges();
        expect(aviso()).toBe('Escaneando filas y columnas…');
        vi.advanceTimersByTime(3000);
        fixture.detectChanges();
        expect(aviso()).toBe('Preparando la vista de la planilla…');
        vi.advanceTimersByTime(3000);
        fixture.detectChanges();
        expect(aviso()).toBe('Sigue leyendo: las listas grandes tardan un poco más…');
        vi.advanceTimersByTime(30000);
        fixture.detectChanges();
        expect(aviso()).toBe('Sigue leyendo: las listas grandes tardan un poco más…');
      } finally {
        vi.useRealTimers();
      }
    });

    it('mientras lee no se puede elegir otro archivo', async () => {
      await crear({ modo });
      servicio.muestra.mockReturnValue(new Subject<Muestra>());

      await elegirArchivo(lista());

      expect(q<HTMLButtonElement>('app-selector-archivo .elegir-archivo')?.disabled).toBe(true);
      expect(q<HTMLInputElement>('#archivo')?.disabled).toBe(true);
    });

    it('leída la lista, aparece el paso 2 con sus textos de hoy', async () => {
      await crear({ modo });

      await elegirArchivo(lista());

      expect(qa('.paso > h2').map((h) => normal(h.textContent))).toEqual([
        '1. Proveedor y archivo',
        '2. Hojas y columnas de la lista',
      ]);
      expect(q('.leyendo')).toBeNull();
    });

    it('el Vendedor ve el aviso y nada para importar', async () => {
      await crear({ modo, dueno: false });

      expect(normal(q('.estado-vacio')?.textContent)).toBe('Solo el Dueño puede importar listas de precios.');
      expect(q('#proveedor')).toBeNull();
      expect(q('#archivo')).toBeNull();
      expect(q('.pasos-celular')).toBeNull();
      expect(q('.ficha-pie')).toBeNull();
      expect(proveedoresServicio.listar).not.toHaveBeenCalled();
    });
  });

  // -------------------------------------------------------------------------------- lo propio del celular

  describe('la barra de pasos', () => {
    it('muestra los cuatro pasos y marca el primero', async () => {
      await crear();

      expect(q('ol.pasos-celular')).not.toBeNull();
      expect(pasos().map((p) => normal(p.textContent))).toEqual(['1 Archivo', '2 Columnas', '3 Revisar', '4 Confirmar']);
      expect(pasos().map((p) => p.getAttribute('aria-current'))).toEqual(['step', null, null, null]);
      expect(q('ol.pasos-celular')?.getAttribute('aria-label')).toBe('Paso 1 de 4');
    });

    it('es lo primero de la pantalla, antes del aviso y del paso', async () => {
      await crear();

      const raiz = el();
      const hijos = Array.from(raiz.children);
      const iBarra = hijos.indexOf(q('ol.pasos-celular')!);
      expect(iBarra).toBeGreaterThanOrEqual(0);
      expect(iBarra).toBeLessThan(hijos.indexOf(q('p.ayuda')!));
      expect(iBarra).toBeLessThan(hijos.indexOf(q('.paso')!));
    });

    it('con la lista leída pasa al segundo paso y deja el primero como hecho', async () => {
      await crear();

      await elegirArchivo(lista());

      expect(pasos().map((p) => p.getAttribute('aria-current'))).toEqual([null, 'step', null, null]);
      expect(pasos()[0].classList.contains('hecho')).toBe(true);
      expect(q('ol.pasos-celular')?.getAttribute('aria-label')).toBe('Paso 2 de 4');
    });

    it('los títulos numerados siguen en el DOM para los lectores de pantalla', async () => {
      await crear();

      expect(normal(q('.paso > h2')?.textContent)).toBe('1. Proveedor y archivo');
    });

    it('en la tablet no hay barra de pasos', async () => {
      await crear({ modo: 'tablet' });
      await elegirArchivo(lista());

      expect(q('.pasos-celular')).toBeNull();
    });
  });

  describe('el pie fijo del paso 1', () => {
    it('lleva «Elegir archivo…» como acción del paso, en un pie fijo sobre la barra de destinos', async () => {
      await crear();

      expect(pie()).not.toBeNull();
      const boton1 = pie()!.querySelector<HTMLButtonElement>('button')!;
      expect(normal(boton1.textContent)).toBe('Elegir archivo…');
      expect(boton1.getAttribute('aria-disabled')).toBeNull();
      expect(pie()!.querySelector('.motivo-accion')).toBeNull();
    });

    it('tocarlo abre el selector de archivos, el mismo input de siempre', async () => {
      await crear();
      const abrir = vi.spyOn(q<HTMLInputElement>('#archivo')!, 'click').mockImplementation(() => undefined);

      pie()!.querySelector<HTMLButtonElement>('button')!.click();
      await refrescar();

      expect(abrir).toHaveBeenCalledTimes(1);
      expect(servicio.muestra).not.toHaveBeenCalled();
    });

    it('mientras lee queda apagado y explica por qué, y tocarlo no abre nada', async () => {
      await crear();
      servicio.muestra.mockReturnValue(new Subject<Muestra>());
      await elegirArchivo(lista());
      const abrir = vi.spyOn(q<HTMLInputElement>('#archivo')!, 'click').mockImplementation(() => undefined);

      const accion = pie()!.querySelector<HTMLButtonElement>('button')!;
      expect(normal(accion.textContent)).toBe('Elegir archivo…');
      expect(accion.getAttribute('aria-disabled')).toBe('true');
      const motivo = pie()!.querySelector('.motivo-accion');
      expect(normal(motivo?.textContent)).toBe('Esperá a que termine de leerse la lista.');
      expect(motivo?.getAttribute('role')).toBe('status');
      expect(accion.getAttribute('aria-describedby')).toBe(motivo?.id);

      accion.click();
      await refrescar();
      expect(abrir).not.toHaveBeenCalled();
      expect(servicio.muestra).toHaveBeenCalledTimes(1);
    });

    it('al terminar de leer, el pie del paso 1 se va y queda el del paso 2', async () => {
      await crear();

      await elegirArchivo(lista());

      expect(normal(pie()!.querySelector('button')?.textContent)).toBe('Ver vista previa');
      expect(normal(pie()!.textContent)).not.toContain('Elegir archivo');
    });

    it('si la lectura falla, vuelve la acción prendida y el error sigue a la vista', async () => {
      await crear();
      servicio.muestra.mockReturnValue(throwError(() => errorHttp(422, 'La planilla está dañada.')));

      await elegirArchivo(lista());

      expect(normal(q('[role="alert"]')?.textContent)).toBe('La planilla está dañada.');
      const accion = pie()!.querySelector<HTMLButtonElement>('button')!;
      expect(accion.getAttribute('aria-disabled')).toBeNull();
      expect(pie()!.querySelector('.motivo-accion')).toBeNull();
    });

    it('en la tablet no hay pie fijo', async () => {
      await crear({ modo: 'tablet' });

      expect(pie()).toBeNull();
      expect(q('.pie-importar')).toBeNull();
    });
  });

  describe('la lectura como tarjeta', () => {
    it('muestra el nombre del archivo, una barra de progreso y el mismo aviso', async () => {
      await crear();
      servicio.muestra.mockReturnValue(new Subject<Muestra>());

      await elegirArchivo(lista('bulonera-sur-nov.xlsx'));

      const tarjeta = q('.leyendo-tarjeta')!;
      expect(tarjeta).not.toBeNull();
      expect(normal(tarjeta.querySelector('.leyendo-archivo')?.textContent)).toBe('bulonera-sur-nov.xlsx');
      const barra = tarjeta.querySelector('[role="progressbar"]');
      expect(barra?.getAttribute('aria-label')).toBe('Leyendo la lista');
      expect(barra?.hasAttribute('aria-valuenow')).toBe(false);
      const aviso = tarjeta.querySelector('p.leyendo');
      expect(normal(aviso?.textContent)).toBe('Leyendo la lista…');
      expect(aviso?.getAttribute('role')).toBe('status');
    });

    it('reusa los cuatro avisos rotativos', async () => {
      await crear();
      servicio.muestra.mockReturnValue(new Subject<Muestra>());
      vi.useFakeTimers();
      try {
        await elegirArchivo(lista());
        const vistos = [normal(q('.leyendo-tarjeta p.leyendo')?.textContent)];
        for (let i = 0; i < 3; i++) {
          vi.advanceTimersByTime(3000);
          fixture.detectChanges();
          vistos.push(normal(q('.leyendo-tarjeta p.leyendo')?.textContent));
        }

        expect(vistos).toEqual([
          'Leyendo la lista…',
          'Escaneando filas y columnas…',
          'Preparando la vista de la planilla…',
          'Sigue leyendo: las listas grandes tardan un poco más…',
        ]);
      } finally {
        vi.useRealTimers();
      }
    });

    it('mientras lee, el paso 1 queda en el DOM pero marcado para esconderse (la tarjeta lo reemplaza)', async () => {
      await crear();
      servicio.muestra.mockReturnValue(new Subject<Muestra>());

      await elegirArchivo(lista());

      expect(q('.paso')?.classList.contains('leyendo-celular')).toBe(true);
      expect(q('#proveedor')).not.toBeNull();
      expect(q('#archivo')).not.toBeNull();
    });

    it('al terminar la tarjeta se va y el paso vuelve a verse', async () => {
      await crear();
      const respuesta = new Subject<Muestra>();
      servicio.muestra.mockReturnValue(respuesta);
      await elegirArchivo(lista());

      respuesta.next(muestra);
      respuesta.complete();
      await refrescar();

      expect(q('.leyendo-tarjeta')).toBeNull();
      expect(q('.leyendo')).toBeNull();
      expect(q('.paso')?.classList.contains('leyendo-celular')).toBe(false);
    });

    it('en la tablet la lectura sigue siendo el párrafo de siempre, sin tarjeta ni barra', async () => {
      await crear({ modo: 'tablet' });
      servicio.muestra.mockReturnValue(new Subject<Muestra>());

      await elegirArchivo(lista());

      expect(q('.leyendo-tarjeta')).toBeNull();
      expect(q('[role="progressbar"]')).toBeNull();
      expect(q('.paso')?.className).toBe('paso panel');
      const aviso = q('p.leyendo')!;
      expect(aviso.parentElement).toBe(el());
      expect(aviso.className).toBe('ayuda leyendo');
    });
  });

  describe('al abrir', () => {
    it('no mueve el foco a ningún campo (subiría el teclado)', async () => {
      await crear();

      const activo = document.activeElement;
      expect(activo?.matches('input, select, textarea')).toBeFalsy();
    });
  });

  describe('otros pasos', () => {
    it('con la vista previa lista la barra marca el tercer paso', async () => {
      await crear();
      const vista = {
        conteos: { suben: 0, bajan: 0, nuevos: 0, sinCambio: 0, noEstan: 0, conError: 0, cambianMoneda: 0, corrigen: 0, salteadas: 0 },
        cambios: [],
        nuevos: [],
        errores: [],
        advertencias: [],
        noEstan: [],
        cambiosDeMoneda: [],
        correcciones: [],
        sinCambio: [],
      } as VistaPrevia;
      servicio.vistaPrevia.mockReturnValue(of(vista));
      await elegirArchivo(lista());
      const proveedor = q<HTMLSelectElement>('#proveedor')!;
      proveedor.value = Array.from(proveedor.options).find((o) => normal(o.textContent) === 'Sinteplast')!.value;
      proveedor.dispatchEvent(new Event('change'));
      await refrescar();

      boton('Ver vista previa')!.click();
      await refrescar();

      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
      expect(pasos().map((p) => p.getAttribute('aria-current'))).toEqual([null, null, 'step', null]);
      expect(pie()).toBeNull();
    });
  });

  // ===================================================================================== T13b: paso 2, el mapeo

  async function elegirProveedor(nombre = 'Sinteplast') {
    const proveedor = q<HTMLSelectElement>('#proveedor')!;
    proveedor.value = Array.from(proveedor.options).find((o) => normal(o.textContent) === nombre)!.value;
    proveedor.dispatchEvent(new Event('change'));
    await refrescar();
  }

  /** Deja el paso 2 a la vista: proveedor elegido y lista leída con la propuesta del servidor. */
  async function enElPaso2(modo: Modo, opts: { leida?: Muestra } = {}) {
    await crear({ modo });
    if (opts.leida) servicio.muestra.mockReturnValue(of(opts.leida));
    servicio.vistaPrevia.mockReturnValue(of(vistaVacia));
    await elegirProveedor();
    await elegirArchivo(lista());
  }

  /** El botón «Ver vista previa» que toque la persona: el del pie en el celular, el del cuerpo en la tablet. */
  const verVistaPrevia = () => boton('Ver vista previa')!;
  const fila = (campo: string) => q<HTMLButtonElement>(`button.fila-mapeo#${campo}`)!;
  const radios = () => qa('app-hoja-inferior [role="radio"]');
  const PAYLOAD_PROPUESTO = {
    proveedorId: 1,
    mapeo: { hoja: 0, filaDesde: 2, columnaCodigo: 0, columnaDescripcion: 1, columnaCosto: 2, hojas: [0] },
    listaIncluyeIva: false,
    bonificacion1: null,
    bonificacion2: null,
    bonificacion3: null,
    rubroId: null,
  };

  // ---------------------------------------------------------------- lo que no cambia, en celular y en tablet (pasa en la base)

  describe.each(modos)('el mapeo y sus pedidos, en %s (T13b)', (modo) => {
    it('«Ver vista previa» pide la vista previa una sola vez, con el mapeo reconocido y los valores de siempre', async () => {
      await enElPaso2(modo);

      verVistaPrevia().click();
      await refrescar();

      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
      expect(servicio.vistaPrevia).toHaveBeenCalledWith(expect.any(File), PAYLOAD_PROPUESTO);
      expect(servicio.aplicar).not.toHaveBeenCalled();
      expect(servicio.muestra).toHaveBeenCalledTimes(1);
    });

    it('manda IVA, bonificaciones y rubro tal como se cargaron', async () => {
      await enElPaso2(modo);
      q<HTMLInputElement>('#listaIncluyeIva')!.click();
      for (const [id, valor] of [['bonificacion1', '40'], ['bonificacion2', '10']] as const) {
        const input = q<HTMLInputElement>(`#${id}`)!;
        input.value = valor;
        input.dispatchEvent(new Event('input'));
      }
      const rubro = q<HTMLSelectElement>('#rubro')!;
      rubro.value = Array.from(rubro.options).find((o) => normal(o.textContent) === 'Pinturas')!.value;
      rubro.dispatchEvent(new Event('change'));
      await refrescar();

      verVistaPrevia().click();
      await refrescar();

      expect(servicio.vistaPrevia).toHaveBeenCalledWith(expect.any(File), {
        ...PAYLOAD_PROPUESTO,
        listaIncluyeIva: true,
        bonificacion1: 40,
        bonificacion2: 10,
        rubroId: 1,
      });
    });

    it('sin proveedor el aviso es «Elegí el proveedor.» y no se pide nada', async () => {
      await crear({ modo });
      await elegirArchivo(lista());

      verVistaPrevia().click();
      await refrescar();

      expect(normal(q('[role="alert"]')?.textContent)).toBe('Elegí el proveedor.');
      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
    });

    it('sin las columnas del código y del costo avisa con el texto de siempre y lleva el foco a la del código', async () => {
      await enElPaso2(modo, { leida: muestraSinPropuesta });

      verVistaPrevia().click();
      await refrescar();

      expect(normal(q('[role="alert"]')?.textContent)).toBe('Elegí las columnas del código y del costo.');
      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
      expect(document.activeElement?.id).toBe('columnaCodigo');
    });

    it('con una bonificación fuera de rango avisa «Revisá los datos marcados en rojo.» y no pide nada', async () => {
      await enElPaso2(modo);
      const input = q<HTMLInputElement>('#bonificacion1')!;
      input.value = '150';
      input.dispatchEvent(new Event('input'));
      await refrescar();

      verVistaPrevia().click();
      await refrescar();

      expect(normal(q('[role="alert"]')?.textContent)).toBe('Revisá los datos marcados en rojo.');
      expect(normal(q('#bonificacion1-error')?.textContent)).toBe('La bonificación 1 tiene que estar entre 0 y 100.');
      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
      expect(document.activeElement?.id).toBe('bonificacion1');
    });

    it('«Reconocida» marca la primera fila y las columnas que reconoció el servidor, con sus ids de siempre', async () => {
      await enElPaso2(modo);

      const marcas = qa('.etiqueta.reconocida');
      expect(marcas.map((m) => m.id)).toEqual([
        'filaDesde-reconocida',
        'columnaCodigo-reconocida',
        'columnaDescripcion-reconocida',
        'columnaCosto-reconocida',
      ]);
      expect(marcas.every((m) => normal(m.textContent) === 'Reconocida')).toBe(true);
    });

    it('con varias hojas, ◀ y ▶ cambian la hoja que se ve, con el mismo texto y sin pedir nada', async () => {
      await enElPaso2(modo, { leida: muestraConHojas });
      const texto = () => normal(q('.hoja-actual')?.textContent);

      expect(texto()).toBe('Hoja 1 de 2 · Lista');
      boton('▶')!.click();
      await refrescar();
      expect(texto()).toBe('Hoja 2 de 2 · Ofertas');
      expect(boton('▶')!.disabled).toBe(true);
      boton('◀')!.click();
      await refrescar();
      expect(texto()).toBe('Hoja 1 de 2 · Lista');
      expect(servicio.muestra).toHaveBeenCalledTimes(1);
      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
    });

    it('con varias hojas, la lista de hojas y la nota de referencia son las de hoy', async () => {
      await enElPaso2(modo, { leida: muestraConHojas });

      expect(normal(q('app-hojas-lista legend')?.textContent)).toBe('Hojas que se importan');
      expect(normal(q('app-hojas-lista .hojas-cuenta')?.textContent)).toBe('1 de 2 hojas marcadas');
      expect(normal(q('.hoja-de-referencia')?.textContent)).toBe(
        'Las columnas valen para todas las hojas. La primera fila de datos se toma de la hoja «Lista»; en las demás hojas el sistema busca el encabezado solo.',
      );
      boton('Marcar todas')!.click();
      await refrescar();
      expect(normal(q('app-hojas-lista .hojas-cuenta')?.textContent)).toBe('2 de 2 hojas marcadas');
    });

    it('cambiar el mapeo después de la vista previa la descarta y vuelve el paso 2', async () => {
      await enElPaso2(modo);
      verVistaPrevia().click();
      await refrescar();
      expect(q('.vista-previa')).not.toBeNull();

      q<HTMLInputElement>('#bonificacion1')!.value = '5';
      q<HTMLInputElement>('#bonificacion1')!.dispatchEvent(new Event('input'));
      await refrescar();

      expect(q('.vista-previa')).toBeNull();
      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
    });

    it('el error del servidor al pedir la vista previa es el suyo y el paso sigue a la vista', async () => {
      await enElPaso2(modo);
      servicio.vistaPrevia.mockReturnValue(throwError(() => errorHttp(422, 'La columna del costo no tiene números.')));

      verVistaPrevia().click();
      await refrescar();

      expect(normal(q('[role="alert"]')?.textContent)).toBe('La columna del costo no tiene números.');
      expect(q('#columnaCosto')).not.toBeNull();
    });

    it('mientras genera, el botón dice «Generando vista previa…» y está apagado', async () => {
      await enElPaso2(modo);
      servicio.vistaPrevia.mockReturnValue(new Subject<VistaPrevia>());

      verVistaPrevia().click();
      await refrescar();

      const apagado = boton('Generando vista previa…')!;
      expect(apagado.disabled).toBe(true);
      expect(boton('Ver vista previa')).toBeUndefined();
    });
  });

  describe('el paso 2 en la tablet (T13b)', () => {
    it('sigue el visor con zoom, los select y el botón del cuerpo, sin muestra, sin «Más ajustes» y sin pie', async () => {
      await enElPaso2('tablet');

      expect(q('cdk-virtual-scroll-viewport')).not.toBeNull();
      expect(q('.visor')).not.toBeNull();
      expect(q('.zoom')).not.toBeNull();
      expect(q('.muestra-celular')).toBeNull();
      expect(q('select#columnaCodigo')).not.toBeNull();
      expect(q('button.fila-mapeo')).toBeNull();
      expect(q('details.mas-ajustes')).toBeNull();
      expect(pie()).toBeNull();
      expect(q('.pie-columnas')).toBeNull();
      expect(q('form.mapeo button.generar')).not.toBeNull();
      expect(qa('app-mapeo-columnas fieldset > legend').map((l) => normal(l.textContent))[0]).toBe('Datos del artículo');
    });

    it('el texto de ayuda del paso sigue hablando del zoom', async () => {
      await enElPaso2('tablet');

      expect(normal(q('.paso:has(> app-mapeo-columnas) > p.ayuda')?.textContent)).toContain('usá el zoom');
    });
  });

  // ------------------------------------------------------------------------------------ lo propio del celular (rojo antes)

  describe('la muestra estática (T13b)', () => {
    it('reemplaza al visor con zoom: no hay scroll virtual, ni zoom, ni columnas A B C del visor', async () => {
      await enElPaso2('celular');

      expect(q('cdk-virtual-scroll-viewport')).toBeNull();
      expect(q('.visor')).toBeNull();
      expect(q('.zoom')).toBeNull();
      expect(q('.herramientas-visor')).toBeNull();
      expect(q('.muestra-celular')).not.toBeNull();
    });

    it('es una tabla con la columna «Fila», una letra por columna y las primeras filas con sus celdas', async () => {
      await enElPaso2('celular');

      const tabla = q('.muestra-celular table')!;
      expect(qa('.muestra-celular thead th').map((th) => normal(th.textContent))).toEqual(['Fila', 'A', 'B', 'C']);
      const filas = Array.from(tabla.querySelectorAll('tbody tr')).map((tr) =>
        Array.from(tr.querySelectorAll('td')).map((td) => normal(td.textContent)),
      );
      expect(filas).toEqual([
        ['1', 'Codigo', 'Descripcion', 'Precio'],
        ['2', 'A-1', 'Tornillo', '1.234,56'],
      ]);
    });

    it('muestra sólo las primeras cuatro filas de una hoja larga', async () => {
      const larga: Muestra = {
        ...muestra,
        hojas: [
          {
            ...muestra.hojas[0],
            filas: Array.from({ length: 50 }, (_, i) => ({ numero: i + 1, celdas: [`c${i + 1}`, 'd', 'e'] })),
          },
        ],
      };
      await enElPaso2('celular', { leida: larga });

      expect(qa('.muestra-celular tbody tr')).toHaveLength(4);
      expect(normal(q('.muestra-celular .muestra-conteo')?.textContent)).toBe('50 filas · 3 columnas');
    });

    it('es de sólo lectura y se puede recorrer de costado con el teclado', async () => {
      await enElPaso2('celular');

      const marco = q('.muestra-celular [role="region"]')!;
      expect(marco.getAttribute('tabindex')).toBe('0');
      expect(marco.getAttribute('aria-label')).toBe('Primeras filas de la lista');
      expect(q('.muestra-celular input, .muestra-celular select, .muestra-celular textarea')).toBeNull();
    });

    it('dice la hoja y el conteo con el mismo texto de siempre', async () => {
      await enElPaso2('celular');

      expect(normal(q('.muestra-celular .hoja-actual')?.textContent)).toBe('Lista');
      expect(normal(q('.muestra-celular .muestra-conteo')?.textContent)).toBe('2 filas · 3 columnas');
    });

    it('con varias hojas lleva ◀ Hoja 1 de 2 ▶ de 44 px y cambiarla cambia las filas', async () => {
      await enElPaso2('celular', { leida: muestraConHojas });

      expect(qa('.muestra-celular th').map((t) => normal(t.textContent))).toEqual(['Fila', 'A', 'B', 'C']);
      boton('▶')!.click();
      await refrescar();

      expect(qa('.muestra-celular th').map((t) => normal(t.textContent))).toEqual(['Fila', 'A', 'B']);
      expect(qa('.muestra-celular tbody tr td').map((t) => normal(t.textContent))).toEqual(['1', 'X-1', '10']);
      expect(boton('◀')?.getAttribute('aria-label')).toBe('Hoja anterior');
      expect(boton('▶')?.getAttribute('aria-label')).toBe('Hoja siguiente');
    });
  });

  describe('«Datos a mapear» (T13b)', () => {
    it('el primer grupo es «Datos a mapear» con la primera fila de datos y tres filas que abren una hoja', async () => {
      await enElPaso2('celular');

      expect(normal(q('app-mapeo-columnas fieldset > legend')?.textContent)).toBe('Datos a mapear');
      expect(q('select#columnaCodigo')).toBeNull();
      expect(Array.from(q('app-mapeo-columnas')!.querySelectorAll<HTMLElement>('fieldset button.fila-mapeo')).map((b) => b.id)).toEqual([
        'columnaCodigo',
        'columnaDescripcion',
        'columnaCosto',
      ]);
      expect(q('#filaDesde')).not.toBeNull();
    });

    it('cada fila muestra la columna reconocida con tres ejemplos y la marca «Reconocida»', async () => {
      await enElPaso2('celular');

      expect(normal(fila('columnaCosto').querySelector('.fila-mapeo-valor')?.textContent)).toBe('C · Precio');
      expect(normal(fila('columnaCosto').querySelector('.fila-mapeo-ejemplos')?.textContent)).toBe('1.234,56');
      expect(normal(fila('columnaCodigo').querySelector('.fila-mapeo-ejemplos')?.textContent)).toBe('A-1');
      expect(normal(fila('columnaCodigo').querySelector('.etiqueta.reconocida')?.textContent)).toBe('Reconocida');
    });

    it('tocar una fila abre la hoja con las columnas del archivo: letra, encabezado y ejemplos de los datos', async () => {
      await enElPaso2('celular');

      fila('columnaCosto').click();
      await refrescar();

      expect(normal(q('app-hoja-inferior h2')?.textContent)).toBe('Columna del costo');
      expect(radios().map((r) => normal(r.querySelector('.opcion-columna-letra')?.textContent))).toEqual(['A', 'B', 'C']);
      expect(radios().map((r) => normal(r.querySelector('b')?.textContent))).toEqual(['Codigo', 'Descripcion', 'Precio']);
      expect(radios().map((r) => normal(r.querySelector('small')?.textContent))).toEqual(['A-1', 'Tornillo', '1.234,56']);
      expect(radios().map((r) => r.getAttribute('aria-checked'))).toEqual(['false', 'false', 'true']);
    });

    it('al abrir la hoja no hay un campo de texto con el foco y al cerrarla vuelve a la fila', async () => {
      await enElPaso2('celular');

      fila('columnaDescripcion').click();
      await refrescar();
      expect(document.activeElement?.matches('input, select, textarea')).toBeFalsy();
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await refrescar();

      expect(q('app-hoja-inferior')).toBeNull();
      expect(document.activeElement).toBe(fila('columnaDescripcion'));
    });

    it('elegir una columna en la hoja manda el mismo pedido que elegirla en el select de la tablet', async () => {
      await enElPaso2('celular');
      fila('columnaCosto').click();
      await refrescar();
      radios()[1].click();
      await refrescar();
      verVistaPrevia().click();
      await refrescar();

      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
      const celular = servicio.vistaPrevia.mock.calls[0][1];
      TestBed.resetTestingModule();

      await enElPaso2('tablet');
      const select = q<HTMLSelectElement>('#columnaCosto')!;
      select.value = Array.from(select.options).find((o) => normal(o.textContent) === 'B · Descripcion')!.value;
      select.dispatchEvent(new Event('change'));
      await refrescar();
      verVistaPrevia().click();
      await refrescar();

      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
      expect(servicio.vistaPrevia.mock.calls[0][1]).toEqual(celular);
      expect(celular.mapeo.columnaCosto).toBe(1);
    });

    it('«No usar esta columna» en la descripción manda el pedido sin descripción', async () => {
      await enElPaso2('celular');

      fila('columnaDescripcion').click();
      await refrescar();
      radios().find((r) => normal(r.textContent) === 'No usar esta columna')!.click();
      await refrescar();
      verVistaPrevia().click();
      await refrescar();

      expect(servicio.vistaPrevia).toHaveBeenCalledWith(expect.any(File), {
        ...PAYLOAD_PROPUESTO,
        mapeo: { hoja: 0, filaDesde: 2, columnaCodigo: 0, columnaDescripcion: null, columnaCosto: 2, hojas: [0] },
      });
    });

    it('cambiar una columna deja sin efecto la vista previa que había', async () => {
      await enElPaso2('celular');
      verVistaPrevia().click();
      await refrescar();
      expect(q('.vista-previa')).not.toBeNull();

      fila('columnaCosto').click();
      await refrescar();
      radios()[0].click();
      await refrescar();

      expect(q('.vista-previa')).toBeNull();
    });

    it('«Reconocida» desaparece de una fila cuando se la cambia por otra columna', async () => {
      await enElPaso2('celular');

      fila('columnaCosto').click();
      await refrescar();
      radios()[1].click();
      await refrescar();

      expect(fila('columnaCosto').querySelector('.reconocida')).toBeNull();
      expect(fila('columnaCodigo').querySelector('.reconocida')).not.toBeNull();
    });

    it('la ayuda larga del visor (zoom y desplazamiento) sigue en el DOM para esconderse por CSS', async () => {
      await enElPaso2('celular');

      expect(normal(q('.paso:has(> app-mapeo-columnas) > p.ayuda')?.textContent)).toContain('usá el zoom');
    });
  });

  describe('«Más ajustes» (T13b)', () => {
    const mas = () => q<HTMLDetailsElement>('details.mas-ajustes')!;

    it('es un bloque plegado que lleva IVA, moneda, bonificaciones y rubro', async () => {
      await enElPaso2('celular');

      expect(mas().open).toBe(false);
      expect(normal(mas().querySelector('summary b')?.textContent)).toBe('Más ajustes');
      expect(normal(mas().querySelector('summary small')?.textContent)).toBe('IVA, moneda, bonificación, rubro nuevo');
      for (const id of [
        'monedaFija',
        'columnaMoneda',
        'columnaConIva',
        'columnaSinIva',
        'columnaIva',
        'columnaAjuste1',
        'columnaCodigoBarras',
        'listaIncluyeIva',
        'bonificacion1',
        'bonificacion2',
        'bonificacion3',
        'rubro',
      ]) {
        expect(mas().querySelector(`#${id}`), id).not.toBeNull();
      }
      expect(mas().querySelectorAll('fieldset > legend')).toHaveLength(4);
    });

    it('lo que va en «Datos a mapear» no está ahí adentro', async () => {
      await enElPaso2('celular');

      for (const id of ['filaDesde', 'columnaCodigo', 'columnaDescripcion', 'columnaCosto']) {
        expect(mas().querySelector(`#${id}`), id).toBeNull();
      }
    });

    it('hay un solo formulario de la lista y una sola de cada campo: nada duplicado', async () => {
      await enElPaso2('celular');

      expect(qa('form.mapeo')).toHaveLength(1);
      for (const id of ['listaIncluyeIva', 'bonificacion1', 'rubro', 'monedaFija', 'columnaMoneda']) {
        expect(el().querySelectorAll(`#${id}`), id).toHaveLength(1);
      }
    });

    it('un error en la bonificación abre el bloque y lleva el foco al campo', async () => {
      await enElPaso2('celular');
      const input = q<HTMLInputElement>('#bonificacion2')!;
      input.value = '-3';
      input.dispatchEvent(new Event('input'));
      await refrescar();
      expect(mas().open).toBe(false);

      verVistaPrevia().click();
      await refrescar();

      expect(mas().open).toBe(true);
      expect(document.activeElement?.id).toBe('bonificacion2');
    });

    it('con una sola columna de IVA, el error abre el bloque y lleva el foco a la fila que falta', async () => {
      await enElPaso2('celular');
      fila('columnaSinIva').click();
      await refrescar();
      radios()[1].click();
      await refrescar();

      verVistaPrevia().click();
      await refrescar();

      expect(normal(q('[role="alert"]')?.textContent)).toBe(
        'Elegí las dos columnas del IVA: el precio sin IVA y el precio con IVA, o ninguna de las dos.',
      );
      expect(mas().open).toBe(true);
      expect(document.activeElement?.id).toBe('columnaConIva');
    });

    it('con el costo y el código mal el foco va a «Datos a mapear» y el bloque sigue plegado', async () => {
      await enElPaso2('celular', { leida: muestraSinPropuesta });

      verVistaPrevia().click();
      await refrescar();

      expect(mas().open).toBe(false);
      expect(document.activeElement).toBe(fila('columnaCodigo'));
    });
  });

  describe('el pie fijo del paso 2 (T13b)', () => {
    it('lleva «Ver vista previa» como acción del paso, con el pie del paso 1 que ya había', async () => {
      await enElPaso2('celular');

      expect(pie()).not.toBeNull();
      expect(pie()!.classList.contains('pie-columnas')).toBe(true);
      expect(pie()!.querySelectorAll('button')).toHaveLength(1);
      expect(normal(pie()!.querySelector('button')?.textContent)).toBe('Ver vista previa');
      expect(pie()!.querySelector<HTMLButtonElement>('button')!.disabled).toBe(false);
      expect(pie()!.querySelector('.motivo-accion')).toBeNull();
    });

    it('el botón del cuerpo sigue en el DOM (se oculta por CSS) y hace lo mismo', async () => {
      await enElPaso2('celular');

      const delCuerpo = q<HTMLButtonElement>('form.mapeo button.generar')!;
      expect(normal(delCuerpo.textContent)).toBe('Ver vista previa');
      expect(pie()!.contains(delCuerpo)).toBe(false);
      delCuerpo.click();
      await refrescar();
      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
      expect(servicio.vistaPrevia).toHaveBeenCalledWith(expect.any(File), PAYLOAD_PROPUESTO);
    });

    it('tocarlo manda el mismo pedido que el botón del cuerpo, una sola vez', async () => {
      await enElPaso2('celular');

      pie()!.querySelector<HTMLButtonElement>('button')!.click();
      await refrescar();

      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
      expect(servicio.vistaPrevia).toHaveBeenCalledWith(expect.any(File), PAYLOAD_PROPUESTO);
    });

    it('mientras genera dice «Generando vista previa…» y está apagado: no se pide dos veces', async () => {
      await enElPaso2('celular');
      servicio.vistaPrevia.mockReturnValue(new Subject<VistaPrevia>());

      pie()!.querySelector<HTMLButtonElement>('button')!.click();
      await refrescar();
      const accion = pie()!.querySelector<HTMLButtonElement>('button')!;
      expect(normal(accion.textContent)).toBe('Generando vista previa…');
      expect(accion.disabled).toBe(true);
      accion.click();
      await refrescar();

      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
    });

    it('si falta algo, el aviso es el de siempre y el pie sigue ahí para reintentar', async () => {
      await crear();
      await elegirArchivo(lista());

      pie()!.querySelector<HTMLButtonElement>('button')!.click();
      await refrescar();

      expect(normal(q('[role="alert"]')?.textContent)).toBe('Elegí el proveedor.');
      expect(normal(pie()!.querySelector('button')?.textContent)).toBe('Ver vista previa');
      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
    });

    it('con la vista previa lista el pie se va (el paso 3 es de otra pantalla)', async () => {
      await enElPaso2('celular');

      pie()!.querySelector<HTMLButtonElement>('button')!.click();
      await refrescar();

      expect(pie()).toBeNull();
    });

    it('el Vendedor no ve ni el pie ni el paso', async () => {
      await crear({ dueno: false });

      expect(pie()).toBeNull();
      expect(q('app-mapeo-columnas')).toBeNull();
    });

    it('no mueve el foco a ningún campo al llegar al paso 2', async () => {
      await enElPaso2('celular');

      expect(document.activeElement?.matches('input, select, textarea')).toBeFalsy();
    });
  });
});
