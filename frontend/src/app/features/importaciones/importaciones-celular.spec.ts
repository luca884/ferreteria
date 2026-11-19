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
describe('Importar lista de precios en el celular (T13a)', () => {
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
        { provide: RubrosService, useValue: { listar: vi.fn(() => of([])) } },
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

    it('al terminar de leer, el pie del paso 1 se va', async () => {
      await crear();

      await elegirArchivo(lista());

      expect(pie()).toBeNull();
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
});
