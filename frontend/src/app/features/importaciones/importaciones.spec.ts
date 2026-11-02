import { registerLocaleData } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import localeEsAr from '@angular/common/locales/es-AR';
import { CdkFixedSizeVirtualScroll } from '@angular/cdk/scrolling';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LOCALE_ID, signal } from '@angular/core';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { RubrosService } from '../rubros/rubros.service';
import {
  AltaPlaneada,
  CambioPlaneado,
  Conteos,
  HojaDeMuestra,
  Importacion,
  Muestra,
  PropuestaDeMapeo,
  VistaPrevia,
} from './importacion.models';
import { Importaciones } from './importaciones';
import { ImportacionesService } from './importaciones.service';

registerLocaleData(localeEsAr);

const SIN_AJUSTES = { a1: null, a2: null, a3: null };

const CONTEOS_VACIOS: Conteos = {
  suben: 0,
  bajan: 0,
  nuevos: 0,
  sinCambio: 0,
  noEstan: 0,
  conError: 0,
  cambianMoneda: 0,
  corrigen: 0,
  salteadas: 0,
};

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

function cambioDe(parcial: Partial<CambioPlaneado>): CambioPlaneado {
  return {
    fila: 2,
    hoja: 0,
    articuloId: 10,
    codigoProveedor: 'A-1',
    descripcion: 'Tornillo 6mm',
    precioLista: 1234.56,
    costoAnterior: 1000,
    costoNuevo: 1111.1,
    precioAnterior: 1300,
    precioNuevo: 1444.43,
    activo: true,
    variacionFuerte: false,
    monedaAnterior: 'ARS',
    monedaNueva: 'ARS',
    alicuotaAnterior: 'IVA_21',
    alicuotaNueva: 'IVA_21',
    ajustesAnteriores: SIN_AJUSTES,
    ajustesNuevos: SIN_AJUSTES,
    ...parcial,
  };
}

function altaDe(parcial: Partial<AltaPlaneada>): AltaPlaneada {
  return {
    fila: 4,
    hoja: 0,
    codigo: 'N-9',
    descripcion: 'Arandela',
    precioLista: 20,
    costo: 18,
    moneda: 'ARS',
    alicuotaIva: 'IVA_21',
    ajustes: SIN_AJUSTES,
    unidad: 'UNIDAD',
    codigoBarras: null,
    rubroId: null,
    precioNuevo: 30,
    ...parcial,
  };
}

function vistaPreviaDe(parcial: Partial<VistaPrevia>): VistaPrevia {
  return {
    conteos: CONTEOS_VACIOS,
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

function hojaDe(
  indice: number,
  nombre: string,
  columnas: number,
  filas: string[][],
  filaEncabezado: number | null = null,
): HojaDeMuestra {
  return {
    indice,
    nombre,
    columnas,
    filaEncabezado,
    filas: filas.map((celdas, i) => ({ numero: i + 1, celdas })),
  };
}

describe('Importaciones', () => {
  const sinteplast: Proveedor = { id: 1, nombre: 'Sinteplast', cuit: null, activo: true };
  const tersuave: Proveedor = {
    id: 2,
    nombre: 'Tersuave',
    cuit: null,
    activo: true,
    mapeoLista: {
      hoja: 1,
      filaDesde: 3,
      columnaCodigo: 1,
      columnaDescripcion: 0,
      columnaCosto: 2,
      // Sin textos de encabezado: no se puede comparar con el archivo y se usa tal cual (ver mapeo-lista.spec).
      encabezados: null,
    },
    listaIncluyeIva: true,
    bonificacion1: 40,
    bonificacion2: 10,
    bonificacion3: null,
  };

  const pinturas = { id: 1, nombre: 'Pinturas', margenPorcentaje: 30 };

  const encabezado = ['Codigo', 'Descripcion', 'Precio'];
  /** Dos hojas con el encabezado en la fila 1; el servidor reconoció código A, descripción B y costo C. */
  const muestra: Muestra = {
    hojas: [
      hojaDe(0, 'Lista', 3, [encabezado, ['A-1', 'Tornillo', '1.234,56']], 1),
      hojaDe(1, 'Oferta', 3, [encabezado, ['B-1', 'Clavo', '50']], 1),
    ],
    propuesta: {
      ...SIN_PROPUESTA,
      hoja: 0,
      filaDesde: 2,
      columnaCodigo: 0,
      columnaDescripcion: 1,
      columnaCosto: 2,
      hojas: [0],
    },
  };
  /** Lo mismo, pero el servidor no reconoció nada: hay que elegir a mano. */
  const muestraSinReconocer: Muestra = { hojas: muestra.hojas, propuesta: SIN_PROPUESTA };

  let fixture: ComponentFixture<Importaciones>;
  let servicio: {
    muestra: ReturnType<typeof vi.fn>;
    vistaPrevia: ReturnType<typeof vi.fn>;
    aplicar: ReturnType<typeof vi.fn>;
  };
  let proveedoresServicio: { listar: ReturnType<typeof vi.fn> };

  const errorHttp = (status: number, message: string) =>
    new HttpErrorResponse({ status, error: { status, message } });

  async function crearComponente(dueno = true, lista: Proveedor[] = [sinteplast, tersuave]) {
    servicio = {
      muestra: vi.fn(() => of(muestra)),
      vistaPrevia: vi.fn(),
      aplicar: vi.fn(),
    };
    proveedoresServicio = { listar: vi.fn(() => of(lista)) };
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
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends Element = HTMLElement>(selector: string) => el().querySelector<T>(selector)!;
  const texto = (e: Element | null | undefined) => e?.textContent?.replace(/\s+/g, ' ').trim();

  async function asentar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** Elige la opción cuyo texto es `texto` (los valores de las opciones son internos de Angular). */
  async function elegir(selector: string, texto: string) {
    const select = q<HTMLSelectElement>(selector);
    const opcion = Array.from(select.options).find((o) => {
      const t = o.textContent?.trim();
      return t === texto || t?.startsWith(`${texto} · `);
    });
    expect(opcion, `${selector} → ${texto}`).toBeDefined();
    select.value = opcion!.value;
    select.dispatchEvent(new Event('change'));
    await asentar();
  }

  const textosDe = (selector: string) =>
    Array.from(q<HTMLSelectElement>(selector).options).map((o) => o.textContent?.trim());
  const seleccionado = (selector: string) => {
    const select = q<HTMLSelectElement>(selector);
    return select.options[select.selectedIndex]?.textContent?.trim();
  };

  async function elegirArchivo(archivo: File) {
    const input = q<HTMLInputElement>('#archivo');
    Object.defineProperty(input, 'files', { value: [archivo], configurable: true });
    input.dispatchEvent(new Event('change'));
    await asentar();
  }

  const lista = () => new File(['x'], 'lista.xlsx');

  async function escribir(selector: string, valor: string) {
    const input = q<HTMLInputElement>(selector);
    input.value = valor;
    input.dispatchEvent(new Event('input'));
    await asentar();
  }

  afterEach(() => vi.restoreAllMocks());

  it('el título va en la barra de acciones de arriba', async () => {
    await crearComponente(true);

    expect(el().querySelector('app-barra-acciones h1')?.textContent?.trim()).toBe(
      'Importar lista de precios',
    );
  });

  describe('permisos', () => {
    it('el Dueño ve el formulario y la lista de proveedores', async () => {
      await crearComponente(true);

      expect(proveedoresServicio.listar).toHaveBeenCalledTimes(1);
      expect(textosDe('#proveedor')).toEqual(['Elegí un proveedor', 'Sinteplast', 'Tersuave']);
    });

    it('el Vendedor ve un aviso y nada para importar', async () => {
      await crearComponente(false);

      expect(el().textContent).toContain('Solo el Dueño puede importar listas de precios.');
      expect(el().querySelector('#proveedor')).toBeNull();
      expect(el().querySelector('#archivo')).toBeNull();
    });
  });

  describe('paso 1: proveedor y archivo', () => {
    const MB = 1024 * 1024;
    const conTamano = (bytes: number, nombre = 'lista.xlsx') => {
      const archivo = new File(['x'], nombre);
      Object.defineProperty(archivo, 'size', { value: bytes });
      return archivo;
    };

    it('el input de archivo acepta xlsx, xls, csv y pdf', async () => {
      await crearComponente();

      expect(q('#archivo').getAttribute('accept')).toBe('.xlsx,.xls,.csv,.pdf');
    });

    it('la ayuda del archivo dice que acepta Excel, CSV o PDF de hasta 25 MB', async () => {
      await crearComponente();

      expect(q('app-selector-archivo .ayuda').textContent?.replace(/\s+/g, ' ')).toContain(
        'Excel (.xlsx, .xls), CSV o PDF, hasta 25 MB.',
      );
    });

    it('un archivo de más de 25 MB muestra el aviso y no llama al servicio', async () => {
      await crearComponente();

      await elegirArchivo(conTamano(25 * MB + 1));

      expect(q('[role="alert"]').textContent).toContain('El archivo supera el máximo de 25 MB.');
      expect(servicio.muestra).not.toHaveBeenCalled();
    });

    it('un archivo de exactamente 25 MB se acepta', async () => {
      await crearComponente();

      await elegirArchivo(conTamano(25 * MB));

      expect(servicio.muestra).toHaveBeenCalledTimes(1);
      expect(el().querySelector('[role="alert"]')).toBeNull();
    });

    it('uno de 10,5 MB, que con el tope de antes se rechazaba, se acepta', async () => {
      await crearComponente();

      await elegirArchivo(conTamano(10.5 * MB));

      expect(servicio.muestra).toHaveBeenCalledTimes(1);
    });

    it('.xlsx, .xls, .csv y .pdf se aceptan, sin importar mayúsculas', async () => {
      for (const nombre of ['lista.xlsx', 'lista.xls', 'lista.csv', 'lista.pdf', 'LISTA.XLSX', 'LISTA.PDF']) {
        TestBed.resetTestingModule();
        await crearComponente();

        await elegirArchivo(conTamano(1000, nombre));

        expect(servicio.muestra, nombre).toHaveBeenCalledTimes(1);
      }
    });

    it('una imagen se rechaza con el aviso y no llama al servicio', async () => {
      await crearComponente();

      await elegirArchivo(conTamano(1000, 'lista.png'));

      expect(q('[role="alert"]').textContent).toContain(
        'El archivo tiene que ser una planilla de Excel (.xlsx o .xls), un CSV o un PDF.',
      );
      expect(servicio.muestra).not.toHaveBeenCalled();
      expect(q('.nombre-archivo').textContent?.trim()).toBe('Ningún archivo elegido');
    });

    it('al elegir el archivo trae la muestra, que ya no depende de la hoja', async () => {
      await crearComponente();
      const archivo = lista();

      await elegirArchivo(archivo);

      expect(servicio.muestra).toHaveBeenCalledTimes(1);
      expect(servicio.muestra).toHaveBeenCalledWith(archivo);
    });

    it('muestra el error del backend si no se puede leer el archivo', async () => {
      await crearComponente();
      servicio.muestra.mockReturnValue(
        throwError(() => errorHttp(400, 'No se pudo leer el archivo. Verificá que no esté dañado.')),
      );

      await elegirArchivo(lista());

      expect(q('[role="alert"]').textContent).toContain(
        'No se pudo leer el archivo. Verificá que no esté dañado.',
      );
      expect(el().querySelector('.visor')).toBeNull();
    });

    it('un error que no es HTTP muestra un mensaje genérico', async () => {
      await crearComponente();
      servicio.muestra.mockReturnValue(throwError(() => new Error('caído')));

      await elegirArchivo(lista());

      expect(q('[role="alert"]').textContent).toContain('No se pudo leer la lista.');
    });

    it('avisa si no se pudieron cargar los proveedores', async () => {
      servicio = { muestra: vi.fn(), vistaPrevia: vi.fn(), aplicar: vi.fn() };
      await TestBed.configureTestingModule({
        imports: [Importaciones],
        providers: [
          { provide: ImportacionesService, useValue: servicio },
          {
            provide: ProveedoresService,
            useValue: { listar: () => throwError(() => errorHttp(500, 'Falló')) },
          },
          { provide: RubrosService, useValue: { listar: () => of([]) } },
          { provide: AuthService, useValue: { esDueno: signal(true) } },
        ],
      }).compileComponents();
      fixture = TestBed.createComponent(Importaciones);
      await asentar();

      expect(q('[role="alert"]').textContent).toContain('No se pudieron cargar los proveedores.');
    });
  });

  describe('paso 2: mapeo de columnas', () => {
    it('no muestra el mapeo hasta tener la muestra', async () => {
      await crearComponente();

      expect(el().querySelector('.visor')).toBeNull();
      expect(el().querySelector('#columnaCodigo')).toBeNull();
    });

    describe('visor tipo Excel', () => {
      const letras = () =>
        Array.from(el().querySelectorAll('.visor-encabezado .columna')).map((c) => c.textContent?.trim());
      const zoomActual = () => q('button[aria-label="Restablecer zoom"]').textContent?.trim();
      const alejar = () => q<HTMLButtonElement>('button[aria-label="Alejar"]');
      const acercar = () => q<HTMLButtonElement>('button[aria-label="Acercar"]');
      const restablecer = () => q<HTMLButtonElement>('button[aria-label="Restablecer zoom"]');
      const tamanoDeItem = () =>
        fixture.debugElement
          .query(By.directive(CdkFixedSizeVirtualScroll))
          .injector.get(CdkFixedSizeVirtualScroll).itemSize;

      async function conMuestra(m: Muestra) {
        await crearComponente();
        servicio.muestra.mockReturnValue(of(m));
        await elegirArchivo(lista());
      }

      const muestraDeUnaHoja = (hoja: HojaDeMuestra): Muestra => ({ hojas: [hoja], propuesta: SIN_PROPUESTA });

      const muestraGrande = (filas: number, columnas: number): Muestra =>
        muestraDeUnaHoja({
          indice: 0,
          nombre: 'Lista',
          columnas,
          filaEncabezado: null,
          filas: Array.from({ length: filas }, (_, i) => ({
            numero: i + 1,
            celdas: Array.from({ length: columnas }, (_, c) => `r${i + 1}c${c}`),
          })),
        });

      it('arma el marco con una letra por columna en el encabezado fijo', async () => {
        await crearComponente();
        await elegirArchivo(lista());

        expect(el().querySelector('.visor')).not.toBeNull();
        expect(q('.visor-encabezado .esquina').textContent?.trim()).toBe('Fila');
        expect(letras()).toEqual(['A', 'B', 'C']);
      });

      it('muestra las celdas de las filas con su número original en la primera columna', async () => {
        await crearComponente();
        await elegirArchivo(lista());
        await asentar(); // el scroll virtual mide el marco un ciclo después

        const filas = Array.from(el().querySelectorAll('.visor-fila')).map((fila) =>
          Array.from(fila.children).map((c) => c.textContent?.trim()),
        );
        expect(filas).toEqual([
          ['1', 'Codigo', 'Descripcion', 'Precio'],
          ['2', 'A-1', 'Tornillo', '1.234,56'],
        ]);
      });

      it('el texto largo va en una línea y se ve completo en el tooltip', async () => {
        await conMuestra(
          muestraDeUnaHoja(
            hojaDe(0, 'Lista', 1, [['Tornillo hexagonal galvanizado de 1/4 por 2 pulgadas']]),
          ),
        );

        await asentar(); // el scroll virtual mide el marco un ciclo después

        const celda = q('.visor-fila .celda');
        expect(celda.getAttribute('title')).toBe('Tornillo hexagonal galvanizado de 1/4 por 2 pulgadas');
      });

      it('cuenta las filas y las columnas debajo del marco', async () => {
        await crearComponente();
        await elegirArchivo(lista());

        expect(q('.visor-conteo').textContent?.trim()).toBe('2 filas · 3 columnas');
      });

      it('usa singular con una fila y una columna', async () => {
        await conMuestra(muestraDeUnaHoja(hojaDe(0, 'Lista', 1, [['x']])));

        expect(q('.visor-conteo').textContent?.trim()).toBe('1 fila · 1 columna');
      });

      it('con 20.000 filas no arma 20.000 nodos y el conteo las incluye todas', async () => {
        await conMuestra(muestraGrande(20_000, 3));

        expect(q('.visor-conteo').textContent?.trim()).toBe('20.000 filas · 3 columnas');
        expect(el().querySelectorAll('.visor-fila').length).toBeLessThan(200);
      });

      it('las letras siguen más allá de la Z: AA, AB…', async () => {
        await conMuestra(muestraGrande(1, 30));

        const todas = letras();
        expect(todas).toHaveLength(30);
        expect(todas[25]).toBe('Z');
        expect(todas[26]).toBe('AA');
        expect(todas[29]).toBe('AD');
      });

      it('los selectores del mapeo ofrecen TODAS las columnas, no solo las primeras 30', async () => {
        await conMuestra(muestraGrande(2, 45));

        const codigo = textosDe('#columnaCodigo');
        expect(codigo).toHaveLength(46);
        expect(codigo[1]).toBe('A');
        expect(codigo[45]).toBe('AS');
        expect(textosDe('#columnaDescripcion')).toHaveLength(46);
        expect(textosDe('#columnaCosto')).toHaveLength(46);
      });

      it('los selectores ofrecen las columnas de la hoja más ancha', async () => {
        await conMuestra({
          hojas: [hojaDe(0, 'Angosta', 2, [['a', 'b']]), hojaDe(1, 'Ancha', 5, [['a', 'b', 'c', 'd', 'e']])],
          propuesta: SIN_PROPUESTA,
        });

        expect(textosDe('#columnaCodigo')).toEqual(['Elegí la columna', 'A', 'B', 'C', 'D', 'E']);
      });

      describe('zoom', () => {
        it('arranca en 100 % con la altura de fila base', async () => {
          await crearComponente();
          await elegirArchivo(lista());

          expect(zoomActual()).toBe('100 %');
          expect(tamanoDeItem()).toBe(30);
        });

        it('acercar y alejar mueven de a 10 % y restablecer vuelve a 100 %', async () => {
          await crearComponente();
          await elegirArchivo(lista());

          acercar().click();
          await asentar();
          acercar().click();
          await asentar();
          expect(zoomActual()).toBe('120 %');

          alejar().click();
          await asentar();
          expect(zoomActual()).toBe('110 %');

          restablecer().click();
          await asentar();
          expect(zoomActual()).toBe('100 %');
        });

        it('el máximo es 200 % y ahí acercar queda deshabilitado', async () => {
          await crearComponente();
          await elegirArchivo(lista());

          for (let i = 0; i < 15; i++) {
            if (!acercar().disabled) acercar().click();
            await asentar();
          }

          expect(zoomActual()).toBe('200 %');
          expect(acercar().disabled).toBe(true);
          expect(alejar().disabled).toBe(false);
        });

        it('el mínimo es 50 % y ahí alejar queda deshabilitado', async () => {
          await crearComponente();
          await elegirArchivo(lista());

          for (let i = 0; i < 10; i++) {
            if (!alejar().disabled) alejar().click();
            await asentar();
          }

          expect(zoomActual()).toBe('50 %');
          expect(alejar().disabled).toBe(true);
          expect(acercar().disabled).toBe(false);
        });

        it('el tamaño de ítem del scroll virtual sigue al zoom (alto de fila × zoom)', async () => {
          await crearComponente();
          await elegirArchivo(lista());

          for (let i = 0; i < 10; i++) {
            acercar().click();
            await asentar();
          }
          expect(zoomActual()).toBe('200 %');
          expect(tamanoDeItem()).toBe(60);

          restablecer().click();
          await asentar();
          for (let i = 0; i < 5; i++) {
            alejar().click();
            await asentar();
          }
          expect(zoomActual()).toBe('50 %');
          expect(tamanoDeItem()).toBe(15);
        });

        it('el zoom escala la fila y la fuente del marco, y el marco no cambia de alto', async () => {
          await crearComponente();
          await elegirArchivo(lista());
          const marco = () => q('.visor');
          const altoDelMarco = marco().style.getPropertyValue('--alto-marco');

          acercar().click();
          await asentar();

          expect(marco().style.getPropertyValue('--alto-fila')).toBe('33px');
          expect(marco().style.getPropertyValue('--ancho-columna')).toBe('132px');
          expect(marco().style.getPropertyValue('--fuente')).toBe('15.4px');
          expect(marco().style.getPropertyValue('--alto-marco')).toBe(altoDelMarco);
        });
      });

      describe('resaltado del mapeo', () => {
        const columnas = () => Array.from(el().querySelectorAll('.visor-encabezado .columna'));

        it('marca en el encabezado las columnas elegidas como código, descripción y costo', async () => {
          await crearComponente();
          await elegirArchivo(lista());

          const [a, b, c] = columnas();
          expect(a.classList).toContain('col-codigo');
          expect(b.classList).toContain('col-descripcion');
          expect(c.classList).toContain('col-costo');
        });

        it('sin mapeo ninguna columna está resaltada', async () => {
          await crearComponente();
          servicio.muestra.mockReturnValue(of(muestraSinReconocer));
          await elegirArchivo(lista());

          for (const columna of columnas()) {
            expect(columna.classList).not.toContain('col-codigo');
            expect(columna.classList).not.toContain('col-descripcion');
            expect(columna.classList).not.toContain('col-costo');
          }
        });

        it('al cambiar la elección el resaltado se mueve de columna', async () => {
          await crearComponente();
          await elegirArchivo(lista());
          await elegir('#columnaDescripcion', 'Sin descripción');

          await elegir('#columnaCosto', 'B');

          const [, b, c] = columnas();
          expect(b.classList).toContain('col-costo');
          expect(c.classList).not.toContain('col-costo');
        });

        it('las columnas de los otros campos (moneda, IVA, ajustes…) también se resaltan, con su nombre', async () => {
          await crearComponente();
          servicio.muestra.mockReturnValue(
            of({
              hojas: [hojaDe(0, 'Lista', 5, [['a', 'b', 'c', 'd', 'e']], 1)],
              propuesta: { ...SIN_PROPUESTA, hoja: 0, filaDesde: 2, columnaCodigo: 0, columnaCosto: 2, hojas: [0] },
            }),
          );
          await elegirArchivo(lista());
          expect(columnas()[3].classList).not.toContain('col-otra');

          await elegir('#columnaMoneda', 'D');

          expect(columnas()[3].classList).toContain('col-otra');
          expect(columnas()[3].getAttribute('title')).toBe('Columna de la moneda');
          await elegir('#columnaSinIva', 'E');
          expect(columnas()[4].getAttribute('title')).toBe('Columna del precio sin IVA');
        });

        it('atenúa las filas anteriores a la primera fila de datos', async () => {
          await crearComponente();
          await elegirArchivo(lista());
          await escribir('#filaDesde', '2');

          const [encabezadoFila, datos] = Array.from(el().querySelectorAll('.visor-fila'));
          expect(encabezadoFila.classList).toContain('previa');
          expect(datos.classList).not.toContain('previa');
        });

        it('en otra hoja atenúa hasta el encabezado que reconoció el servidor', async () => {
          await crearComponente();
          servicio.muestra.mockReturnValue(
            of({
              ...muestra,
              hojas: [
                muestra.hojas[0],
                hojaDe(1, 'Oferta', 3, [['Titulo'], encabezado, ['B-1', 'Clavo', '50']], 2),
              ],
            }),
          );
          await elegirArchivo(lista());

          q<HTMLButtonElement>('button[aria-label="Hoja siguiente"]').click();
          await asentar();

          const filas = Array.from(el().querySelectorAll('.visor-fila'));
          expect(filas.map((f) => f.classList.contains('previa'))).toEqual([true, true, false]);
        });
      });
    });

    it('ofrece las columnas por letra con el texto del encabezado que reconoció el servidor', async () => {
      await crearComponente();
      await elegirArchivo(lista());

      const letras = ['A · Codigo', 'B · Descripcion', 'C · Precio'];
      expect(textosDe('#columnaCodigo')).toEqual(['Elegí la columna', ...letras]);
      expect(textosDe('#columnaCosto')).toEqual(['Elegí la columna', ...letras]);
      expect(textosDe('#columnaDescripcion')).toEqual(['Sin descripción', ...letras]);
    });

    describe('navegación de hojas: todo el archivo viene en una sola muestra', () => {
      const anterior = () => q<HTMLButtonElement>('button[aria-label="Hoja anterior"]');
      const siguiente = () => q<HTMLButtonElement>('button[aria-label="Hoja siguiente"]');
      const etiqueta = () => q('.navegacion-hojas .hoja-actual').textContent?.trim();

      const tres: Muestra = {
        hojas: [
          hojaDe(0, 'Lista', 3, [encabezado], 1),
          hojaDe(1, 'Oferta', 2, [['Z', 'Y']]),
          hojaDe(2, 'Histórico', 3, [['H', 'I', 'J']]),
        ],
        propuesta: { ...SIN_PROPUESTA, hoja: 0, filaDesde: 2, columnaCodigo: 0, columnaCosto: 2, hojas: [0] },
      };

      async function conArchivoDeTresHojas() {
        await crearComponente();
        servicio.muestra.mockReturnValue(of(tres));
        const archivo = lista();
        await elegirArchivo(archivo);
        servicio.muestra.mockClear();
        return archivo;
      }

      it('al cargar muestra la primera hoja con "Hoja 1 de N" y su nombre', async () => {
        await conArchivoDeTresHojas();

        expect(etiqueta()).toBe('Hoja 1 de 3 · Lista');
      });

      it('la flecha siguiente cambia la grilla y la etiqueta SIN volver a llamar al servicio', async () => {
        await conArchivoDeTresHojas();

        siguiente().click();
        await asentar();

        expect(servicio.muestra).not.toHaveBeenCalled();
        expect(etiqueta()).toBe('Hoja 2 de 3 · Oferta');
        expect(el().querySelectorAll('.visor-encabezado .columna')).toHaveLength(2);
      });

      it('la flecha anterior vuelve a la hoja previa', async () => {
        await conArchivoDeTresHojas();
        siguiente().click();
        await asentar();

        anterior().click();
        await asentar();

        expect(servicio.muestra).not.toHaveBeenCalled();
        expect(etiqueta()).toBe('Hoja 1 de 3 · Lista');
      });

      it('se puede elegir la hoja de una lista, sin llamar al servicio', async () => {
        await conArchivoDeTresHojas();

        await elegir('#hojaVista', 'Histórico');

        expect(etiqueta()).toBe('Hoja 3 de 3 · Histórico');
        expect(servicio.muestra).not.toHaveBeenCalled();
      });

      it('en la primera hoja la flecha anterior está deshabilitada', async () => {
        await conArchivoDeTresHojas();

        expect(anterior().disabled).toBe(true);
        expect(siguiente().disabled).toBe(false);
      });

      it('en la última hoja la flecha siguiente está deshabilitada', async () => {
        await conArchivoDeTresHojas();
        siguiente().click();
        await asentar();
        siguiente().click();
        await asentar();

        expect(etiqueta()).toBe('Hoja 3 de 3 · Histórico');
        expect(siguiente().disabled).toBe(true);
        expect(anterior().disabled).toBe(false);
      });

      it('ver otra hoja no deja sin efecto la vista previa: no cambia ningún dato', async () => {
        await conArchivoDeTresHojas();
        servicio.vistaPrevia.mockReturnValue(of(vistaPreviaDe({ conteos: CONTEOS_VACIOS })));
        await elegir('#proveedor', 'Sinteplast');
        q<HTMLButtonElement>('button.generar').click();
        await asentar();
        expect(el().querySelector('.vista-previa')).not.toBeNull();

        siguiente().click();
        await asentar();

        expect(el().querySelector('.vista-previa')).not.toBeNull();
      });

      it('un archivo de una sola hoja no muestra la navegación ni la lista de hojas', async () => {
        await crearComponente();
        servicio.muestra.mockReturnValue(of({ hojas: [tres.hojas[0]], propuesta: tres.propuesta }));

        await elegirArchivo(lista());

        expect(el().querySelector('.navegacion-hojas')).toBeNull();
        expect(el().querySelector('app-hojas-lista')).toBeNull();
        expect(el().querySelector('.visor')).not.toBeNull();
      });

      it('la navegación es accesible: botones con nombre y la etiqueta anuncia los cambios', async () => {
        await conArchivoDeTresHojas();

        expect(anterior().getAttribute('type')).toBe('button');
        expect(siguiente().getAttribute('type')).toBe('button');
        expect(q('.navegacion-hojas .hoja-actual').getAttribute('aria-live')).toBe('polite');
        expect(q('label[for="hojaVista"]').textContent?.trim()).toBe('Ver la hoja');
      });
    });

    describe('hojas que se importan', () => {
      const hojasChecks = () =>
        Array.from(el().querySelectorAll<HTMLInputElement>('app-hojas-lista input[type="checkbox"]'));

      it('las hojas con encabezado reconocido vienen marcadas y una hoja nueva sin marcar', async () => {
        await crearComponente();
        servicio.muestra.mockReturnValue(
          of({ ...muestra, propuesta: { ...muestra.propuesta!, hojas: [0] } }),
        );

        await elegirArchivo(lista());

        expect(hojasChecks().map((c) => c.checked)).toEqual([true, false]);
      });

      it('del mapeo guardado vienen las hojas guardadas', async () => {
        await crearComponente();
        await elegir('#proveedor', 'Tersuave');

        await elegirArchivo(lista());

        expect(hojasChecks().map((c) => c.checked)).toEqual([false, true]);
      });

      it('marcar una hoja la suma a lo que se manda en la vista previa', async () => {
        await crearComponente();
        servicio.vistaPrevia.mockReturnValue(of(vistaPreviaDe({})));
        await elegir('#proveedor', 'Sinteplast');
        await elegirArchivo(lista());

        hojasChecks()[1].click();
        await asentar();
        q<HTMLButtonElement>('button.generar').click();
        await asentar();

        expect(servicio.vistaPrevia.mock.calls[0][1].mapeo.hojas).toEqual([0, 1]);
      });

      it('sin ninguna hoja marcada no llama al servicio y lo avisa', async () => {
        await crearComponente();
        await elegir('#proveedor', 'Sinteplast');
        await elegirArchivo(lista());
        hojasChecks()[0].click();
        await asentar();

        q<HTMLButtonElement>('button.generar').click();
        await asentar();

        expect(servicio.vistaPrevia).not.toHaveBeenCalled();
        expect(q('[role="alert"]').textContent).toContain('Elegí al menos una hoja.');
      });

      it('cambiar las hojas deja sin efecto la vista previa', async () => {
        await crearComponente();
        servicio.vistaPrevia.mockReturnValue(of(vistaPreviaDe({})));
        await elegir('#proveedor', 'Sinteplast');
        await elegirArchivo(lista());
        q<HTMLButtonElement>('button.generar').click();
        await asentar();
        expect(el().querySelector('.vista-previa')).not.toBeNull();

        hojasChecks()[1].click();
        await asentar();

        expect(el().querySelector('.vista-previa')).toBeNull();
      });

      it('con varias hojas aclara de cuál sale la primera fila de datos', async () => {
        await crearComponente();
        await elegirArchivo(lista());

        expect(q('.hoja-de-referencia').textContent).toContain('«Lista»');
      });
    });

    describe('precarga del mapeo', () => {
      it('con un proveedor sin mapeo guardado usa la propuesta del servidor y la marca como «Reconocida»', async () => {
        await crearComponente();
        await elegir('#proveedor', 'Sinteplast');
        await elegirArchivo(lista());

        expect(seleccionado('#columnaCodigo')).toBe('A · Codigo');
        expect(seleccionado('#columnaDescripcion')).toBe('B · Descripcion');
        expect(seleccionado('#columnaCosto')).toBe('C · Precio');
        expect(q<HTMLInputElement>('#filaDesde').value).toBe('2');
        expect(q<HTMLInputElement>('#listaIncluyeIva').checked).toBe(false);
        const marcas = Array.from(el().querySelectorAll('.reconocida')).map((m) => m.id);
        expect(marcas).toEqual([
          'filaDesde-reconocida',
          'columnaCodigo-reconocida',
          'columnaDescripcion-reconocida',
          'columnaCosto-reconocida',
        ]);
      });

      it('con el mapeo guardado del proveedor usa ese y NO lo marca como reconocido', async () => {
        await crearComponente();
        const archivo = lista();
        await elegir('#proveedor', 'Tersuave');

        await elegirArchivo(archivo);

        expect(servicio.muestra).toHaveBeenCalledTimes(1);
        expect(seleccionado('#columnaCodigo')).toBe('B · Descripcion');
        expect(seleccionado('#columnaDescripcion')).toBe('A · Codigo');
        expect(seleccionado('#columnaCosto')).toBe('C · Precio');
        expect(q<HTMLInputElement>('#filaDesde').value).toBe('3');
        expect(q<HTMLInputElement>('#listaIncluyeIva').checked).toBe(true);
        expect(q<HTMLInputElement>('#bonificacion1').value).toBe('40');
        expect(q<HTMLInputElement>('#bonificacion2').value).toBe('10');
        expect(q<HTMLInputElement>('#bonificacion3').value).toBe('');
        expect(el().querySelectorAll('.reconocida')).toHaveLength(0);
      });

      it('si el encabezado guardado no está en el archivo, usa lo reconocido y no la primera fila guardada', async () => {
        const otraLista: Proveedor = {
          ...tersuave,
          mapeoLista: { ...tersuave.mapeoLista!, encabezados: { codigo: 'Art.', descripcion: null, costo: 'Neto' } },
        };
        await crearComponente(true, [sinteplast, otraLista]);
        await elegir('#proveedor', 'Tersuave');

        await elegirArchivo(lista());

        expect(q<HTMLInputElement>('#filaDesde').value).toBe('2');
        expect(seleccionado('#columnaCodigo')).toBe('A · Codigo');
        expect(seleccionado('#columnaCosto')).toBe('C · Precio');
      });

      it('el mapeo guardado abre la grilla en su hoja de referencia', async () => {
        await crearComponente();
        await elegir('#proveedor', 'Tersuave');

        await elegirArchivo(lista());

        expect(q('.navegacion-hojas .hoja-actual').textContent?.trim()).toBe('Hoja 2 de 2 · Oferta');
      });

      it('si una hoja del mapeo guardado no existe en el archivo, usa la propuesta', async () => {
        await crearComponente();
        const sinSegundaHoja: Muestra = { ...muestra, hojas: [muestra.hojas[0]] };
        servicio.muestra.mockReturnValue(of(sinSegundaHoja));
        await elegir('#proveedor', 'Tersuave');

        await elegirArchivo(lista());

        expect(seleccionado('#columnaCodigo')).toBe('A · Codigo');
        expect(q<HTMLInputElement>('#filaDesde').value).toBe('2');
        expect(el().querySelectorAll('.reconocida').length).toBeGreaterThan(0);
        expect(q<HTMLInputElement>('#bonificacion1').value).toBe('40');
      });

      it('si el proveedor se elige después del archivo, precarga SIN volver a pedir la muestra', async () => {
        await crearComponente();
        await elegirArchivo(lista());
        servicio.muestra.mockClear();

        await elegir('#proveedor', 'Tersuave');

        expect(servicio.muestra).not.toHaveBeenCalled();
        expect(seleccionado('#columnaCodigo')).toBe('B · Descripcion');
        expect(q<HTMLInputElement>('#bonificacion1').value).toBe('40');
        expect(el().querySelectorAll('.reconocida')).toHaveLength(0);
      });

      it('al cambiar a un proveedor sin mapeo guardado vuelve a la propuesta y limpia IVA y bonificaciones', async () => {
        await crearComponente();
        await elegir('#proveedor', 'Tersuave');
        await elegirArchivo(lista());

        await elegir('#proveedor', 'Sinteplast');

        expect(seleccionado('#columnaCodigo')).toBe('A · Codigo');
        expect(q<HTMLInputElement>('#listaIncluyeIva').checked).toBe(false);
        expect(q<HTMLInputElement>('#bonificacion1').value).toBe('');
      });

      it('un archivo sin encabezado reconocible deja elegir a mano, sin error', async () => {
        await crearComponente();
        servicio.muestra.mockReturnValue(of(muestraSinReconocer));
        await elegir('#proveedor', 'Sinteplast');

        await elegirArchivo(lista());

        expect(el().querySelector('[role="alert"]')).toBeNull();
        expect(seleccionado('#columnaCodigo')).toBe('Elegí la columna');
        expect(seleccionado('#columnaDescripcion')).toBe('Sin descripción');
        expect(seleccionado('#columnaCosto')).toBe('Elegí la columna');
        expect(q<HTMLInputElement>('#filaDesde').value).toBe('1');
        expect(el().querySelectorAll('.reconocida')).toHaveLength(0);
        expect(el().querySelector('#columnaCodigo')).not.toBeNull();
      });

      it('si el Dueño cambia un campo reconocido, ese campo pierde la marca y los demás la conservan', async () => {
        await crearComponente();
        await elegirArchivo(lista());

        await elegir('#columnaCosto', 'B');

        const marcas = Array.from(el().querySelectorAll('.reconocida')).map((m) => m.id);
        expect(marcas).not.toContain('columnaCosto-reconocida');
        expect(marcas).toContain('columnaCodigo-reconocida');
      });

      it('los rótulos de las columnas salen de la fila de encabezado que dice el servidor', async () => {
        await crearComponente();
        servicio.muestra.mockReturnValue(
          of({
            hojas: [hojaDe(0, 'Lista', 3, [['Lista de precios', '', ''], ['Código', 'Descripción', 'Precio'], ['A-1', 'x', '1']], 2)],
            propuesta: { ...SIN_PROPUESTA, hoja: 0, filaDesde: 3, columnaCodigo: 0, columnaCosto: 2, hojas: [0] },
          }),
        );

        await elegirArchivo(lista());

        expect(textosDe('#columnaCodigo')).toEqual([
          'Elegí la columna',
          'A · Código',
          'B · Descripción',
          'C · Precio',
        ]);
        expect(q<HTMLInputElement>('#filaDesde').value).toBe('3');
      });

      it('sin fila de encabezado quedan solo las letras', async () => {
        await crearComponente();
        servicio.muestra.mockReturnValue(
          of({ hojas: [hojaDe(0, 'Lista', 3, [['A-1', 'Tornillo', '1.234,56']], null)], propuesta: SIN_PROPUESTA }),
        );

        await elegirArchivo(lista());

        expect(textosDe('#columnaCodigo')).toEqual(['Elegí la columna', 'A', 'B', 'C']);
      });

      it('una celda de encabezado vacía queda solo con la letra', async () => {
        await crearComponente();
        servicio.muestra.mockReturnValue(
          of({ hojas: [hojaDe(0, 'Lista', 3, [['Codigo', '', 'Precio'], ['A-1', 'x', '1']], 1)], propuesta: SIN_PROPUESTA }),
        );

        await elegirArchivo(lista());

        expect(textosDe('#columnaCodigo')).toEqual(['Elegí la columna', 'A · Codigo', 'B', 'C · Precio']);
      });

      it('la primera fila de datos sigue siendo editable', async () => {
        await crearComponente();
        await elegirArchivo(lista());

        await escribir('#filaDesde', '5');

        expect(q<HTMLInputElement>('#filaDesde').value).toBe('5');
      });
    });

    it('explica que las bonificaciones se aplican en cascada y se guardan al confirmar', async () => {
      await crearComponente();
      await elegirArchivo(lista());

      expect(q('.ayuda-bonificaciones').textContent).toContain('en cascada');
      expect(q('.ayuda-bonificaciones').textContent).toContain('al confirmar la importación');
    });

    it('rotula los campos del mapeo y de las bonificaciones', async () => {
      await crearComponente();
      await elegirArchivo(lista());

      const rotulos = Array.from(el().querySelectorAll('label')).map((l) => l.textContent?.trim());
      for (const esperado of [
        'Columna del código del proveedor',
        'Columna de la descripción (opcional)',
        'Columna del costo',
        'Primera fila de datos',
        'La lista incluye IVA',
        'Bonificación 1 (%)',
        'Bonificación 2 (%)',
        'Bonificación 3 (%)',
        'Rubro de los artículos nuevos',
      ]) {
        expect(rotulos, esperado).toContain(esperado);
      }
    });

    it('marca una bonificación fuera de 0 a 100', async () => {
      await crearComponente();
      await elegirArchivo(lista());

      await escribir('#bonificacion1', '101');

      expect(el().textContent).toContain('La bonificación 1 tiene que estar entre 0 y 100.');
    });

    it('el rubro de los artículos nuevos se elige antes de la vista previa, porque define su precio', async () => {
      await crearComponente();
      await elegirArchivo(lista());

      expect(textosDe('#rubro')).toEqual(['Sin rubro (margen por defecto)', 'Pinturas']);
      expect(seleccionado('#rubro')).toBe('Sin rubro (margen por defecto)');
      expect(q('#rubro-ayuda').textContent).toContain('precio de los artículos nuevos');
    });
  });

  describe('paso 3: vista previa', () => {
    const conteos: Conteos = {
      suben: 2,
      bajan: 1,
      nuevos: 1,
      sinCambio: 3,
      noEstan: 1,
      conError: 1,
      cambianMoneda: 1,
      corrigen: 1,
      salteadas: 42,
    };
    const vistaPrevia: VistaPrevia = {
      conteos,
      cambios: [
        cambioDe({}),
        cambioDe({
          fila: 3,
          articuloId: 11,
          codigoProveedor: 'A-2',
          descripcion: 'Tuerca 6mm',
          precioLista: 500,
          costoAnterior: 100,
          costoNuevo: 500,
          precioAnterior: 130,
          precioNuevo: 650,
          activo: false,
          variacionFuerte: true,
        }),
        cambioDe({
          fila: 6,
          articuloId: 12,
          codigoProveedor: 'A-4',
          descripcion: 'Clavo',
          costoAnterior: 200,
          costoNuevo: 150.5,
          precioAnterior: 260,
          precioNuevo: 195.65,
        }),
      ],
      cambiosDeMoneda: [
        cambioDe({
          fila: 7,
          hoja: 1,
          articuloId: 13,
          codigoProveedor: 'M-1',
          descripcion: 'Cable',
          costoAnterior: 1000,
          costoNuevo: 10,
          precioAnterior: 1580,
          precioNuevo: 15730,
          monedaAnterior: 'ARS',
          monedaNueva: 'USD',
        }),
      ],
      correcciones: [
        cambioDe({
          fila: 8,
          hoja: 1,
          articuloId: 14,
          codigoProveedor: 'K-1',
          descripcion: 'Caño',
          costoAnterior: 10000,
          costoNuevo: 10000,
          precioAnterior: 15730,
          precioNuevo: 14370,
          alicuotaAnterior: 'IVA_21',
          alicuotaNueva: 'IVA_10_5',
        }),
      ],
      nuevos: [altaDe({})],
      sinCambio: [
        { id: 15, hoja: 1, fila: 9, codigo: 'S-1', descripcion: 'Igual 1' },
        { id: 16, hoja: 1, fila: 10, codigo: 'S-2', descripcion: 'Igual 2' },
        { id: 17, hoja: 1, fila: 11, codigo: 'S-3', descripcion: 'Igual 3' },
      ],
      errores: [{ hoja: 0, fila: 5, codigo: 'X-1', mensaje: 'El costo es cero o está vacío.' }],
      advertencias: [
        { hoja: 0, fila: null, codigo: null, mensaje: 'El encabezado de la columna del costo cambió.' },
        { hoja: 1, fila: 3, codigo: 'A-2', mensaje: 'El costo varía 400 % respecto del actual.' },
        { hoja: null, fila: null, codigo: null, mensaje: 'Aviso general.' },
      ],
      noEstan: [{ id: 20, codigoInterno: 'INT-20', codigoProveedor: 'Z-1', descripcion: 'Clavo largo' }],
    };

    /** Deja el formulario listo: Sinteplast y el archivo (la propuesta elige código A, descripción B y costo C). */
    async function prepararMapeo() {
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(lista());
    }

    async function generar() {
      q<HTMLButtonElement>('button.generar').click();
      await asentar();
    }

    const tarjetas = () =>
      Array.from(el().querySelectorAll('.tarjeta')).map((t) => texto(t));
    const filasDeTabla = () => Array.from(el().querySelectorAll('app-vista-previa-tabla tbody tr'));
    const celdas = (tr: Element) => Array.from(tr.querySelectorAll('td')).map((td) => texto(td));

    async function conVistaPrevia(vp: VistaPrevia = vistaPrevia) {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vp));
      await prepararMapeo();
      await generar();
    }

    it('no se ve la vista previa hasta pedirla', async () => {
      await crearComponente();
      await prepararMapeo();

      expect(el().querySelector('.vista-previa')).toBeNull();
      expect(q('button.generar').textContent).toContain('Ver vista previa');
    });

    it('manda archivo, proveedor, mapeo con hojas, IVA, bonificaciones y el rubro de los nuevos', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      const archivo = lista();
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(archivo);
      await elegir('#columnaCodigo', 'A');
      await elegir('#columnaDescripcion', 'B');
      await elegir('#columnaCosto', 'C');
      await escribir('#filaDesde', '2');
      q<HTMLInputElement>('#listaIncluyeIva').click();
      await escribir('#bonificacion1', '40');
      await escribir('#bonificacion2', '10');
      await elegir('#rubro', 'Pinturas');

      await generar();

      expect(servicio.vistaPrevia).toHaveBeenCalledWith(archivo, {
        proveedorId: 1,
        mapeo: {
          hoja: 0,
          filaDesde: 2,
          columnaCodigo: 0,
          columnaDescripcion: 1,
          columnaCosto: 2,
          hojas: [0],
        },
        listaIncluyeIva: true,
        bonificacion1: 40,
        bonificacion2: 10,
        bonificacion3: null,
        rubroId: 1,
      });
    });

    it('sin rubro elegido manda rubroId nulo', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await prepararMapeo();

      await generar();

      expect(servicio.vistaPrevia.mock.calls[0][1].rubroId).toBeNull();
    });

    it('manda los campos nuevos del mapeo: moneda, IVA, ajustes, barras, unidad y rubro', async () => {
      await crearComponente();
      servicio.muestra.mockReturnValue(
        of({
          hojas: [hojaDe(0, 'Lista', 13, [Array(13).fill('x'), Array(13).fill('1')], 1)],
          propuesta: SIN_PROPUESTA,
        }),
      );
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(lista());
      await elegir('#columnaCodigo', 'A');
      await elegir('#columnaCosto', 'C');
      await elegir('#columnaMoneda', 'D');
      await elegir('#columnaConIva', 'E');
      await elegir('#columnaSinIva', 'F');
      await elegir('#columnaIva', 'G');
      await elegir('#columnaAjuste1', 'H');
      await elegir('#columnaAjuste3', 'I');
      await elegir('#columnaCodigoBarras', 'J');
      await elegir('#columnaUnidad', 'K');
      await elegir('#columnaRubro', 'L');

      await generar();

      expect(servicio.vistaPrevia.mock.calls[0][1].mapeo).toEqual({
        hoja: 0,
        filaDesde: 1,
        columnaCodigo: 0,
        columnaDescripcion: null,
        columnaCosto: 2,
        hojas: [0],
        columnaMoneda: 3,
        columnaConIva: 4,
        columnaSinIva: 5,
        columnaIva: 6,
        columnaAjuste1: 7,
        columnaAjuste3: 8,
        columnaCodigoBarras: 9,
        columnaUnidad: 10,
        columnaRubro: 11,
      });
    });

    it('con moneda fija manda la moneda y no la columna', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await prepararMapeo();
      await elegir('#monedaFija', 'Dólares (USD)');

      await generar();

      const mapeo = servicio.vistaPrevia.mock.calls[0][1].mapeo;
      expect(mapeo.monedaFija).toBe('USD');
      expect('columnaMoneda' in mapeo).toBe(false);
    });

    it('sin proveedor no llama al servicio y lo avisa', async () => {
      await crearComponente();
      await elegirArchivo(lista());

      await generar();

      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
      expect(q('[role="alert"]').textContent).toContain('Elegí el proveedor.');
    });

    it('sin columna de código o de costo no llama al servicio y lo avisa', async () => {
      await crearComponente();
      servicio.muestra.mockReturnValue(of(muestraSinReconocer));
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(lista());

      await generar();

      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
      expect(q('[role="alert"]').textContent).toContain('Elegí las columnas del código y del costo.');
    });

    it('con el precio sin IVA y sin el precio con IVA no llama al servicio y lo avisa', async () => {
      await crearComponente();
      await prepararMapeo();
      await elegir('#columnaSinIva', 'C');

      await generar();

      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
      expect(q('[role="alert"]').textContent).toContain('Elegí las dos columnas del IVA');
      expect(document.activeElement).toBe(q('#columnaConIva'));
    });

    it('con una bonificación inválida no llama al servicio', async () => {
      await crearComponente();
      await prepararMapeo();
      await escribir('#bonificacion1', '150');

      await generar();

      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
    });

    it('muestra las nueve tarjetas con los contadores del archivo entero', async () => {
      await conVistaPrevia();

      expect(tarjetas()).toEqual([
        'Suben 2',
        'Bajan 1',
        'Cambian de moneda 1',
        'Corrigen IVA o ajustes 1',
        'Nuevos 1',
        'Sin cambio 3',
        'No están 1',
        'Con error 1',
        'Salteadas 42',
      ]);
    });

    it('las salteadas son solo una cantidad, sin detalle', async () => {
      await conVistaPrevia();

      expect(q('.salteadas').textContent).toContain('42 filas de título o de rubro');
      expect(q('.salteadas').textContent).toContain('no se importan');
    });

    it('sin salteadas no hay aviso', async () => {
      await conVistaPrevia({ ...vistaPrevia, conteos: { ...conteos, salteadas: 0 } });

      expect(el().querySelector('.salteadas')).toBeNull();
    });

    it('lista todas las filas, ordenadas por hoja y fila, con su tipo', async () => {
      await conVistaPrevia();

      const filas = filasDeTabla().map((tr) => {
        const c = celdas(tr);
        return [c[1], c[2], c[3], c[5]];
      });
      expect(filas).toEqual([
        ['Lista', '2', 'A-1', 'Sube'],
        ['Lista', '3', 'A-2', 'Sube'],
        ['Lista', '4', 'N-9', 'Nuevo'],
        ['Lista', '5', 'X-1', 'Con error'],
        ['Lista', '6', 'A-4', 'Baja'],
        ['Oferta', '7', 'M-1', 'Cambia de moneda'],
        ['Oferta', '8', 'K-1', 'Corrige IVA o ajustes'],
        ['Oferta', '9', 'S-1', 'Sin cambio'],
        ['Oferta', '10', 'S-2', 'Sin cambio'],
        ['Oferta', '11', 'S-3', 'Sin cambio'],
      ]);
    });

    it('un cambio de moneda se ve como ARS → USD con el costo en cada moneda y el precio en pesos', async () => {
      await conVistaPrevia();

      const fila = celdas(filasDeTabla().find((tr) => texto(tr)?.includes('M-1'))!);
      expect(fila.slice(6, 11)).toEqual(['$ 1.000,00', 'US$ 10,00', '$ 1.580,00', '$ 15.730,00', 'Moneda: ARS → USD']);
    });

    it('una corrección de IVA muestra la alícuota anterior y la nueva', async () => {
      await conVistaPrevia();

      const fila = celdas(filasDeTabla().find((tr) => texto(tr)?.includes('K-1'))!);
      expect(fila[10]).toBe('IVA: 21 % → 10,5 %');
    });

    it('los ajustes se muestran como descuento o recargo, con palabras', async () => {
      await conVistaPrevia(
        {
          ...vistaPrevia,
          correcciones: [
            cambioDe({
              fila: 8,
              hoja: 1,
              codigoProveedor: 'K-1',
              ajustesNuevos: { a1: -5, a2: 3, a3: null },
            }),
          ],
        },
      );

      const fila = celdas(filasDeTabla().find((tr) => texto(tr)?.includes('K-1'))!);
      expect(fila[10]).toBe('Descuentos y recargos: ninguno → descuento 5 % y recargo 3 %');
    });

    it('las filas con error no tienen casilla; todas las demás sí', async () => {
      await conVistaPrevia();

      const conCasilla = filasDeTabla().map((tr) => tr.querySelector('input[type="checkbox"]') !== null);
      expect(conCasilla.filter(Boolean)).toHaveLength(9);
      const error = filasDeTabla().find((tr) => texto(tr)?.includes('X-1'))!;
      expect(error.querySelector('input[type="checkbox"]')).toBeNull();
    });

    it('avisa cuántas filas tienen error y cómo verlas', async () => {
      await conVistaPrevia();

      expect(q('.aviso-errores').textContent).toContain('Hay 1 fila con error');
      expect(q('.aviso-errores').textContent).toContain('Con error');
    });

    it('sin filas con error no hay aviso', async () => {
      await conVistaPrevia({ ...vistaPrevia, errores: [], conteos: { ...conteos, conError: 0 } });

      expect(el().querySelector('.aviso-errores')).toBeNull();
    });

    it('lista las advertencias con la hoja y la fila', async () => {
      await conVistaPrevia();

      const items = Array.from(el().querySelectorAll('.advertencias li')).map((li) => texto(li));
      expect(items).toEqual([
        'Hoja «Lista»: El encabezado de la columna del costo cambió.',
        'Hoja «Oferta», fila 3 (A-2): El costo varía 400 % respecto del actual.',
        'Aviso general.',
      ]);
    });

    it('no pinta las secciones vacías', async () => {
      await conVistaPrevia({ ...vistaPreviaDe({}), conteos: CONTEOS_VACIOS });

      for (const selector of ['.advertencias', 'details.no-estan', '.aviso-errores', '.salteadas']) {
        expect(el().querySelector(selector), selector).toBeNull();
      }
      expect(filasDeTabla()).toHaveLength(0);
    });

    /** Abre el bloque como lo hace el navegador: cambia `open` y dispara `toggle`. */
    async function abrirNoEstan() {
      const detalle = q<HTMLDetailsElement>('details.no-estan');
      detalle.open = true;
      detalle.dispatchEvent(new Event('toggle'));
      await asentar();
    }

    const filasNoEstan = () => Array.from(el().querySelectorAll('details.no-estan tbody tr'));
    const noEstanDe = (cantidad: number) =>
      Array.from({ length: cantidad }, (_, i) => ({
        id: 1000 + i,
        codigoInterno: `INT-${i + 1}`,
        codigoProveedor: `Z-${i + 1}`,
        descripcion: `Artículo ${i + 1}`,
      }));

    it('"No están en la lista" es un bloque colapsable, cerrado al abrir la vista previa', async () => {
      await conVistaPrevia();

      const detalle = q<HTMLDetailsElement>('details.no-estan');
      expect(detalle.open).toBe(false);
      expect(detalle.querySelector('summary')?.textContent).toContain('No están en la lista');
    });

    it('abierto, "No están" muestra el código y la descripción de cada artículo', async () => {
      await conVistaPrevia();

      await abrirNoEstan();

      const detalle = q<HTMLDetailsElement>('details.no-estan');
      expect(detalle.textContent).toContain('INT-20');
      expect(detalle.textContent).toContain('Z-1');
      expect(detalle.textContent).toContain('Clavo largo');
    });

    describe('«No están» con muchos artículos', () => {
      async function conNoEstan(cantidad: number) {
        await conVistaPrevia({
          ...vistaPreviaDe({}),
          conteos: { ...CONTEOS_VACIOS, noEstan: cantidad },
          noEstan: noEstanDe(cantidad),
        });
      }

      it('cerrado no arma ninguna fila, aunque haya 250 artículos', async () => {
        await conNoEstan(250);

        expect(q('details.no-estan summary').textContent).toContain('250');
        expect(filasNoEstan()).toHaveLength(0);
      });

      it('al abrirlo arma las primeras 100 y las páginas', async () => {
        await conNoEstan(250);

        await abrirNoEstan();

        expect(filasNoEstan()).toHaveLength(100);
        expect(texto(filasNoEstan()[0].querySelectorAll('td')[0])).toBe('INT-1');
        expect(texto(filasNoEstan()[99].querySelectorAll('td')[0])).toBe('INT-100');
        expect(texto(q('details.no-estan nav.paginado'))).toContain('Página 1 de 3');
      });

      it('«Siguiente» pasa a las 100 que siguen y la última página trae el resto', async () => {
        await conNoEstan(250);
        await abrirNoEstan();

        q<HTMLButtonElement>('details.no-estan nav.paginado button:last-of-type').click();
        await asentar();
        expect(filasNoEstan()).toHaveLength(100);
        expect(texto(filasNoEstan()[0].querySelectorAll('td')[0])).toBe('INT-101');

        q<HTMLButtonElement>('details.no-estan nav.paginado button:last-of-type').click();
        await asentar();
        expect(filasNoEstan()).toHaveLength(50);
        expect(texto(filasNoEstan()[49].querySelectorAll('td')[0])).toBe('INT-250');
        expect(q<HTMLButtonElement>('details.no-estan nav.paginado button:last-of-type').disabled).toBe(true);
      });

      it('«Anterior» vuelve a la página previa y en la primera está deshabilitado', async () => {
        await conNoEstan(250);
        await abrirNoEstan();
        expect(q<HTMLButtonElement>('details.no-estan nav.paginado button:first-of-type').disabled).toBe(true);

        q<HTMLButtonElement>('details.no-estan nav.paginado button:last-of-type').click();
        await asentar();
        q<HTMLButtonElement>('details.no-estan nav.paginado button:first-of-type').click();
        await asentar();

        expect(texto(filasNoEstan()[0].querySelectorAll('td')[0])).toBe('INT-1');
      });

      it('si cierra el bloque las filas se sueltan, y al volver a abrirlo siguen en la página donde estaba', async () => {
        await conNoEstan(250);
        await abrirNoEstan();
        q<HTMLButtonElement>('details.no-estan nav.paginado button:last-of-type').click();
        await asentar();

        const detalle = q<HTMLDetailsElement>('details.no-estan');
        detalle.open = false;
        detalle.dispatchEvent(new Event('toggle'));
        await asentar();
        expect(filasNoEstan()).toHaveLength(0);

        await abrirNoEstan();
        expect(texto(filasNoEstan()[0].querySelectorAll('td')[0])).toBe('INT-101');
      });

      it('con 100 artículos o menos hay una sola página', async () => {
        await conNoEstan(100);

        await abrirNoEstan();

        expect(filasNoEstan()).toHaveLength(100);
        expect(texto(q('details.no-estan nav.paginado'))).toContain('Página 1 de 1');
      });

      it('una vista previa nueva vuelve a mostrar el bloque cerrado y en la primera página', async () => {
        await conNoEstan(250);
        await abrirNoEstan();
        q<HTMLButtonElement>('details.no-estan nav.paginado button:last-of-type').click();
        await asentar();

        await generar();

        expect(q<HTMLDetailsElement>('details.no-estan').open).toBe(false);
        expect(filasNoEstan()).toHaveLength(0);
        await abrirNoEstan();
        expect(texto(filasNoEstan()[0].querySelectorAll('td')[0])).toBe('INT-1');
      });
    });

    describe('advertencias con tope', () => {
      const advertenciasDe = (cantidad: number) =>
        Array.from({ length: cantidad }, (_, i) => ({
          hoja: null,
          fila: null,
          codigo: null,
          mensaje: `Aviso ${i + 1}.`,
        }));
      const items = () => Array.from(el().querySelectorAll('.advertencias li')).map((li) => texto(li));
      const verMas = () =>
        Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find(
          (b) => b.textContent?.trim() === 'Ver más',
        );

      async function conAdvertencias(cantidad: number) {
        await conVistaPrevia({ ...vistaPreviaDe({}), advertencias: advertenciasDe(cantidad) });
      }

      it('muestra las primeras 100 y un «Ver más»', async () => {
        await conAdvertencias(250);

        expect(items()).toHaveLength(100);
        expect(items()[0]).toBe('Aviso 1.');
        expect(items()[99]).toBe('Aviso 100.');
        expect(verMas()).toBeDefined();
        expect(texto(q('.advertencias-conteo'))).toContain('100 de 250');
      });

      it('«Ver más» suma de a 100 y desaparece cuando ya están todas', async () => {
        await conAdvertencias(250);

        verMas()!.click();
        await asentar();
        expect(items()).toHaveLength(200);
        expect(verMas()).toBeDefined();

        verMas()!.click();
        await asentar();
        expect(items()).toHaveLength(250);
        expect(items()[249]).toBe('Aviso 250.');
        expect(verMas()).toBeUndefined();
      });

      it('con 100 advertencias o menos no hay «Ver más» ni conteo', async () => {
        await conAdvertencias(100);

        expect(items()).toHaveLength(100);
        expect(verMas()).toBeUndefined();
        expect(el().querySelector('.advertencias-conteo')).toBeNull();
      });

      it('una vista previa nueva vuelve a las primeras 100', async () => {
        await conAdvertencias(250);
        verMas()!.click();
        await asentar();
        expect(items()).toHaveLength(200);

        await generar();

        expect(items()).toHaveLength(100);
      });
    });

    it('no avisa de ningún recorte: las filas se ven todas, por páginas', async () => {
      await conVistaPrevia();

      expect(el().querySelector('.recortado')).toBeNull();
      expect(el().textContent).not.toContain('Se muestran solo las primeras 1.000 filas');
    });

    it('con 4.139 filas los contadores son los del archivo y se llega a la última para desmarcarla', async () => {
      const cambios = Array.from({ length: 4139 }, (_, i) =>
        cambioDe({ fila: i + 2, hoja: 0, codigoProveedor: `C-${i + 2}`, costoNuevo: 1200 }),
      );
      await conVistaPrevia({
        ...vistaPreviaDe({ cambios }),
        conteos: { ...CONTEOS_VACIOS, suben: 4139 },
      });
      expect(tarjetas()[0]).toBe('Suben 4.139');
      expect(filasDeTabla()).toHaveLength(100);

      for (let i = 0; i < 41; i++) {
        q<HTMLButtonElement>('nav.paginado button:last-of-type').click();
        await asentar();
      }
      const ultima = filasDeTabla().at(-1)!;
      expect(celdas(ultima)[2]).toBe('4140');
      ultima.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
      await asentar();

      expect(tarjetas()[0]).toBe('Suben 4.138 de 4.139');
    });

    it('muestra el mensaje del backend si la vista previa falla', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(
        throwError(() => errorHttp(400, 'El archivo no tiene filas de datos.')),
      );
      await prepararMapeo();

      await generar();

      expect(q('[role="alert"]').textContent).toContain('El archivo no tiene filas de datos.');
      expect(el().querySelector('.vista-previa')).toBeNull();
    });

    it('muestra los errores de validación por campo en vez del mensaje genérico', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: {
                status: 400,
                message: 'Datos inválidos',
                errors: { bonificacion1: 'La bonificación 1 debe estar entre 0 y 100.' },
              },
            }),
        ),
      );
      await prepararMapeo();

      await generar();

      const alerta = q('[role="alert"]');
      expect(alerta.textContent).toContain('La bonificación 1 debe estar entre 0 y 100.');
      expect(alerta.textContent).not.toContain('Datos inválidos');
    });

    it('un error que no es HTTP muestra un mensaje genérico', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(throwError(() => new Error('caído')));
      await prepararMapeo();

      await generar();

      expect(q('[role="alert"]').textContent).toContain('No se pudo generar la vista previa.');
    });

    describe('elegir filas actualiza las tarjetas', () => {
      it('desmarcar una fila baja el contador de su tipo y muestra cuántas había', async () => {
        await conVistaPrevia();

        filasDeTabla()[2].querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
        await asentar();

        expect(tarjetas()).toContain('Nuevos 0 de 1');
        expect(tarjetas()).toContain('Suben 2');
        expect(tarjetas()).toContain('No están 1');
        expect(tarjetas()).toContain('Con error 1');
      });

      it('«Desmarcar todas» deja todos los contadores de lo elegido en 0 y los del archivo como están', async () => {
        await conVistaPrevia();

        Array.from(el().querySelectorAll('button'))
          .find((b) => b.textContent?.trim() === 'Desmarcar todas' && b.closest('app-vista-previa-tabla'))!
          .click();
        await asentar();

        expect(tarjetas()).toEqual([
          'Suben 0 de 2',
          'Bajan 0 de 1',
          'Cambian de moneda 0 de 1',
          'Corrigen IVA o ajustes 0 de 1',
          'Nuevos 0 de 1',
          'Sin cambio 0 de 3',
          'No están 1',
          'Con error 1',
          'Salteadas 42',
        ]);
      });
    });

    describe('una respuesta vieja que llega tarde se descarta', () => {
      it('si cambia una columna mientras se genera, la vista previa vieja no aparece', async () => {
        await crearComponente();
        const enVuelo = new Subject<VistaPrevia>();
        servicio.vistaPrevia.mockReturnValue(enVuelo);
        await prepararMapeo();
        await generar();
        expect(q('button.generar').textContent).toContain('Generando vista previa…');

        await elegir('#columnaCosto', 'B');
        enVuelo.next(vistaPrevia);
        enVuelo.complete();
        await asentar();

        expect(el().querySelector('.vista-previa')).toBeNull();
        expect(q('button.generar').textContent).toContain('Ver vista previa');
        expect(q<HTMLButtonElement>('button.generar').disabled).toBe(false);
      });

      it('lo mismo si la respuesta vieja es un error: no se muestra', async () => {
        await crearComponente();
        const enVuelo = new Subject<VistaPrevia>();
        servicio.vistaPrevia.mockReturnValue(enVuelo);
        await prepararMapeo();
        await generar();

        await elegir('#columnaCosto', 'B');
        enVuelo.error(errorHttp(500, 'Falló la vista previa vieja'));
        await asentar();

        expect(el().querySelector('[role="alert"]')).toBeNull();
      });

      it('la respuesta nueva sí aparece aunque la vieja llegue después', async () => {
        await crearComponente();
        const vieja = new Subject<VistaPrevia>();
        const nueva = new Subject<VistaPrevia>();
        servicio.vistaPrevia.mockReturnValueOnce(vieja).mockReturnValueOnce(nueva);
        await prepararMapeo();
        await generar();
        await elegir('#columnaCosto', 'B');
        await generar();

        nueva.next(vistaPreviaDe({ conteos: { ...CONTEOS_VACIOS, suben: 1 }, cambios: [cambioDe({})] }));
        vieja.next(vistaPrevia);
        await asentar();

        expect(tarjetas()[0]).toBe('Suben 1');
        expect(filasDeTabla()).toHaveLength(1);
      });
    });

    describe('una vista previa vieja nunca se confirma', () => {
      const cambios: [string, () => Promise<void>][] = [
        ['el proveedor', () => elegir('#proveedor', 'Tersuave')],
        ['el archivo', () => elegirArchivo(new File(['y'], 'otra.xlsx'))],
        ['la columna del código', () => elegir('#columnaCodigo', 'B')],
        ['la columna de la descripción', () => elegir('#columnaDescripcion', 'C')],
        ['la columna del costo', () => elegir('#columnaCosto', 'B')],
        ['la columna de la moneda', () => elegir('#columnaMoneda', 'B')],
        ['la moneda fija', () => elegir('#monedaFija', 'Dólares (USD)')],
        ['la columna del IVA', () => elegir('#columnaIva', 'B')],
        ['una columna de ajuste', () => elegir('#columnaAjuste1', 'B')],
        ['la columna del código de barras', () => elegir('#columnaCodigoBarras', 'B')],
        ['la primera fila de datos', () => escribir('#filaDesde', '5')],
        ['la casilla de IVA', async () => q<HTMLInputElement>('#listaIncluyeIva').click()],
        ['la bonificación 1', () => escribir('#bonificacion1', '5')],
        ['la bonificación 2', () => escribir('#bonificacion2', '5')],
        ['la bonificación 3', () => escribir('#bonificacion3', '5')],
        ['el rubro de los artículos nuevos', () => elegir('#rubro', 'Pinturas')],
        ['las hojas que se importan', async () => q<HTMLInputElement>('#hoja-1').click()],
      ];

      it.each(cambios)('cambiar %s limpia la vista previa', async (_nombre, cambiar) => {
        await crearComponente();
        servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
        await prepararMapeo();
        await generar();
        expect(el().querySelector('.vista-previa')).not.toBeNull();

        await cambiar();
        await asentar();

        expect(el().querySelector('.vista-previa')).toBeNull();
        expect(el().querySelector('button.confirmar')).toBeNull();
      });
    });
  });

  describe('paso 4: confirmar', () => {
    const conteos: Conteos = {
      suben: 2,
      bajan: 1,
      nuevos: 1,
      sinCambio: 1,
      noEstan: 1,
      conError: 1,
      cambianMoneda: 1,
      corrigen: 1,
      salteadas: 42,
    };
    const vistaPrevia: VistaPrevia = {
      conteos,
      cambios: [
        cambioDe({ fila: 2, codigoProveedor: 'A-1' }),
        cambioDe({ fila: 3, codigoProveedor: 'A-2' }),
        cambioDe({ fila: 6, codigoProveedor: 'A-4', costoNuevo: 800 }),
      ],
      cambiosDeMoneda: [cambioDe({ fila: 7, hoja: 1, codigoProveedor: 'M-1', monedaNueva: 'USD' })],
      correcciones: [cambioDe({ fila: 8, hoja: 1, codigoProveedor: 'K-1', alicuotaNueva: 'IVA_10_5' })],
      nuevos: [altaDe({ fila: 4, hoja: 0 })],
      sinCambio: [{ id: 15, hoja: 1, fila: 9, codigo: 'S-1', descripcion: 'Igual' }],
      errores: [{ hoja: 0, fila: 5, codigo: 'X-1', mensaje: 'Moneda no reconocida' }],
      advertencias: [],
      noEstan: [],
    };
    const aplicadas: Conteos = { ...conteos, noEstan: 1 };
    const importacion: Importacion = {
      id: 9,
      proveedorId: 1,
      proveedorNombre: 'Sinteplast',
      usuarioId: 3,
      archivo: 'lista.xlsx',
      fecha: '2026-10-12T10:00:00',
      conteos: aplicadas,
      estado: 'APLICADA',
    };
    const mapeoEsperado = {
      hoja: 0,
      filaDesde: 2,
      columnaCodigo: 0,
      columnaDescripcion: 1,
      columnaCosto: 2,
      hojas: [0],
    };
    const datosBase = {
      proveedorId: 1,
      mapeo: mapeoEsperado,
      listaIncluyeIva: false,
      bonificacion1: null,
      bonificacion2: null,
      bonificacion3: null,
      rubroId: null,
    };
    /** Las 7 filas elegibles en el orden del archivo (hoja, fila). */
    const todasLasFilas = [
      { hoja: 0, fila: 2 },
      { hoja: 0, fila: 3 },
      { hoja: 0, fila: 4 },
      { hoja: 0, fila: 6 },
      { hoja: 1, fila: 7 },
      { hoja: 1, fila: 8 },
      { hoja: 1, fila: 9 },
    ];

    async function conVistaPrevia() {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(archivoActual);
      q<HTMLButtonElement>('button.generar').click();
      await asentar();
    }

    async function confirmar() {
      q<HTMLButtonElement>('button.confirmar').click();
      await asentar();
    }

    const filasDeTabla = () => Array.from(el().querySelectorAll('app-vista-previa-tabla tbody tr'));
    const casillaDeLaFila = (numero: number) =>
      filasDeTabla()
        .find((tr) => tr.querySelectorAll('td')[2]?.textContent?.trim() === String(numero))!
        .querySelector<HTMLInputElement>('input[type="checkbox"]')!;

    let archivoActual: File;
    beforeEach(() => (archivoActual = lista()));

    it('ofrece confirmar solo con una vista previa', async () => {
      await conVistaPrevia();

      expect(q('button.confirmar').textContent).toContain('Confirmar importación');
    });

    it('sin vista previa no hay nada para confirmar', async () => {
      await crearComponente();

      expect(el().querySelector('button.confirmar')).toBeNull();
    });

    it('"Crear los artículos nuevos" arranca tildada: confirmar sin mirarla no deja nuevos sin crear', async () => {
      await conVistaPrevia();

      expect(q<HTMLInputElement>('#crearNuevos').checked).toBe(true);
    });

    it('confirma mandando las filas elegidas y los contadores de lo elegido', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(of(importacion));

      await confirmar();

      expect(servicio.aplicar).toHaveBeenCalledWith(archivoActual, {
        ...datosBase,
        crearNuevos: true,
        conteosEsperados: conteos,
        filasElegidas: todasLasFilas,
      });
    });

    it('si se desmarcan filas, manda solo las marcadas y los contadores recalculados', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(of(importacion));
      casillaDeLaFila(3).click();
      await asentar();
      casillaDeLaFila(9).click();
      await asentar();

      await confirmar();

      const [, datos] = servicio.aplicar.mock.calls[0];
      expect(datos.filasElegidas).toEqual([
        { hoja: 0, fila: 2 },
        { hoja: 0, fila: 4 },
        { hoja: 0, fila: 6 },
        { hoja: 1, fila: 7 },
        { hoja: 1, fila: 8 },
      ]);
      expect(datos.conteosEsperados).toEqual({ ...conteos, suben: 1, sinCambio: 0 });
    });

    it('sin ninguna fila marcada «Confirmar» queda deshabilitado con el aviso «Elegí al menos una fila»', async () => {
      await conVistaPrevia();

      Array.from(el().querySelectorAll('button'))
        .find((b) => b.textContent?.trim() === 'Desmarcar todas' && b.closest('app-vista-previa-tabla'))!
        .click();
      await asentar();

      expect(q<HTMLButtonElement>('button.confirmar').disabled).toBe(true);
      expect(q('#sin-filas-elegidas').textContent).toContain('Elegí al menos una fila');
      expect(q('button.confirmar').getAttribute('aria-describedby')).toContain('sin-filas-elegidas');
    });

    it('con al menos una fila marcada vuelve a habilitarse y el aviso desaparece', async () => {
      await conVistaPrevia();
      Array.from(el().querySelectorAll('button'))
        .find((b) => b.textContent?.trim() === 'Desmarcar todas' && b.closest('app-vista-previa-tabla'))!
        .click();
      await asentar();

      casillaDeLaFila(2).click();
      await asentar();

      expect(q<HTMLButtonElement>('button.confirmar').disabled).toBe(false);
      expect(el().querySelector('#sin-filas-elegidas')).toBeNull();
    });

    it('un 400 del backend por no haber filas se muestra', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(throwError(() => errorHttp(400, 'Elegí al menos una fila.')));

      await confirmar();

      expect(q('[role="alert"]').textContent).toContain('Elegí al menos una fila.');
      expect(el().querySelector('.vista-previa')).not.toBeNull();
    });

    it('confirma creando los nuevos con el rubro elegido antes de la vista previa', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(archivoActual);
      await elegir('#rubro', 'Pinturas');
      q<HTMLButtonElement>('button.generar').click();
      await asentar();
      servicio.aplicar.mockReturnValue(of(importacion));

      await confirmar();

      expect(servicio.vistaPrevia.mock.calls[0][1].rubroId).toBe(1);
      expect(servicio.aplicar).toHaveBeenCalledWith(archivoActual, {
        ...datosBase,
        rubroId: 1,
        crearNuevos: true,
        conteosEsperados: conteos,
        filasElegidas: todasLasFilas,
      });
    });

    it('confirma el mismo rubro que usó la vista previa, aunque no se creen los nuevos', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(archivoActual);
      await elegir('#rubro', 'Pinturas');
      q<HTMLButtonElement>('button.generar').click();
      await asentar();
      servicio.aplicar.mockReturnValue(of(importacion));
      q<HTMLInputElement>('#crearNuevos').click();
      await asentar();

      await confirmar();

      expect(servicio.aplicar.mock.calls[0][1]).toMatchObject({ crearNuevos: false, rubroId: 1 });
    });

    it('con 201 muestra el resumen con los contadores de lo aplicado, incluidos los nuevos, y retira la vista previa', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(of(importacion));

      await confirmar();

      const resumen = texto(q('.resumen')) ?? '';
      expect(resumen).toContain('Importación aplicada');
      expect(resumen).toContain('lista.xlsx');
      expect(resumen).toContain('Suben 2');
      expect(resumen).toContain('Cambian de moneda 1');
      expect(resumen).toContain('Corrigen IVA o ajustes 1');
      expect(resumen).toContain('Nuevos 1');
      expect(resumen).toContain('Salteadas 42');
      expect(el().querySelector('.vista-previa')).toBeNull();
      expect(el().querySelector('button.confirmar')).toBeNull();
    });

    it('el resumen muestra los contadores que devolvió el backend, no los de la pantalla', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(
        of({ ...importacion, conteos: { ...aplicadas, suben: 1, nuevos: 0 } }),
      );

      await confirmar();

      const resumen = texto(q('.resumen')) ?? '';
      expect(resumen).toContain('Suben 1');
      expect(resumen).toContain('Nuevos 0');
    });

    it('tras aplicar recarga los proveedores para traer lo que se guardó', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(of(importacion));
      proveedoresServicio.listar.mockClear();

      await confirmar();

      expect(proveedoresServicio.listar).toHaveBeenCalledTimes(1);
    });

    it('con 409 muestra el mensaje del backend y vuelve a pedir la vista previa', async () => {
      await conVistaPrevia();
      const mensaje =
        'La lista cambió desde la vista previa. No se aplicó nada: volvé a generar la vista previa.';
      servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, mensaje)));
      servicio.vistaPrevia.mockClear();

      await confirmar();

      expect(q('[role="alert"]').textContent).toContain(mensaje);
      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
      expect(servicio.vistaPrevia.mock.calls[0][1]).toEqual(datosBase);
      expect(el().querySelector('.vista-previa')).not.toBeNull();
      expect(el().querySelector('button.regenerar')).toBeNull();
    });

    it('con un 409 porque otro cambio modificó un artículo (versión) muestra el mensaje del servidor y no aplica nada', async () => {
      await conVistaPrevia();
      const mensaje = 'Otro cambio modificó este artículo. Recargá y volvé a intentar.';
      servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, mensaje)));

      await confirmar();

      expect(q('[role="alert"]').textContent).toContain(mensaje);
      expect(servicio.aplicar).toHaveBeenCalledTimes(1);
    });

    describe('después de un 409 la selección se conserva', () => {
      const MENSAJE = 'La lista cambió.';
      const aviso = () => el().querySelector('.aviso-perdidas');
      const marcada = (numero: number) => casillaDeLaFila(numero).checked;

      it('lo que el Dueño había desmarcado sigue desmarcado en la vista previa nueva', async () => {
        await conVistaPrevia();
        casillaDeLaFila(3).click();
        await asentar();
        servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE)));

        await confirmar();

        expect(marcada(3)).toBe(false);
        expect(marcada(2)).toBe(true);
        expect(marcada(6)).toBe(true);
        expect(q<HTMLButtonElement>('button.confirmar').disabled).toBe(false);
        expect(aviso()).toBeNull();
      });

      it('lo que se desmarcó en varias hojas también se conserva y lo aplicado sale con esa selección', async () => {
        await conVistaPrevia();
        casillaDeLaFila(3).click();
        await asentar();
        casillaDeLaFila(9).click();
        await asentar();
        servicio.aplicar.mockReturnValueOnce(throwError(() => errorHttp(409, MENSAJE)));
        await confirmar();
        servicio.aplicar.mockReturnValue(of(importacion));

        await confirmar();

        const [, datos] = servicio.aplicar.mock.calls[1];
        expect(datos.filasElegidas).toEqual([
          { hoja: 0, fila: 2 },
          { hoja: 0, fila: 4 },
          { hoja: 0, fila: 6 },
          { hoja: 1, fila: 7 },
          { hoja: 1, fila: 8 },
        ]);
      });

      it('las filas que aparecen recién entran marcadas y se avisa de la elegida que ya no está', async () => {
        await conVistaPrevia();
        casillaDeLaFila(3).click();
        await asentar();
        servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE)));
        // La lista nueva: la fila 6 ya no está y aparece la 12.
        servicio.vistaPrevia.mockReturnValue(
          of({
            ...vistaPrevia,
            cambios: [
              cambioDe({ fila: 2, codigoProveedor: 'A-1' }),
              cambioDe({ fila: 3, codigoProveedor: 'A-2' }),
              cambioDe({ fila: 12, codigoProveedor: 'A-9' }),
            ],
          }),
        );

        await confirmar();

        expect(marcada(2)).toBe(true);
        expect(marcada(3)).toBe(false);
        expect(marcada(12)).toBe(true);
        expect(texto(aviso())).toBe('1 fila que habías elegido ya no está en la lista nueva.');
      });

      it('con varias filas elegidas que ya no están, el aviso va en plural', async () => {
        await conVistaPrevia();
        servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE)));
        servicio.vistaPrevia.mockReturnValue(
          of({
            ...vistaPrevia,
            cambios: [cambioDe({ fila: 2, codigoProveedor: 'A-1' })],
            cambiosDeMoneda: [],
            correcciones: [],
          }),
        );

        await confirmar();

        expect(texto(aviso())).toBe('4 filas que habías elegido ya no están en la lista nueva.');
      });

      it('una fila desmarcada que ya no está no se cuenta en el aviso', async () => {
        await conVistaPrevia();
        casillaDeLaFila(6).click();
        await asentar();
        servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE)));
        servicio.vistaPrevia.mockReturnValue(
          of({ ...vistaPrevia, cambios: vistaPrevia.cambios.filter((c) => c.fila !== 6) }),
        );

        await confirmar();

        expect(aviso()).toBeNull();
      });

      it('si pedir de nuevo la vista previa falla, «Volver a generar» también conserva la selección', async () => {
        await conVistaPrevia();
        casillaDeLaFila(3).click();
        await asentar();
        servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE)));
        servicio.vistaPrevia.mockReturnValue(throwError(() => errorHttp(500, 'Falló')));
        await confirmar();

        servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
        q<HTMLButtonElement>('button.regenerar').click();
        await asentar();

        expect(marcada(3)).toBe(false);
        expect(marcada(2)).toBe(true);
      });

      it('si antes de volver a generar cambia un dato de la lista, la vista previa abre con todo marcado', async () => {
        await conVistaPrevia();
        casillaDeLaFila(3).click();
        await asentar();
        servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE)));
        servicio.vistaPrevia.mockReturnValue(throwError(() => errorHttp(500, 'Falló')));
        await confirmar();

        await escribir('#bonificacion1', '5');
        servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
        q<HTMLButtonElement>('button.generar').click();
        await asentar();

        expect(marcada(3)).toBe(true);
        expect(aviso()).toBeNull();
      });

      it('una vista previa pedida a mano después del 409 abre con todo marcado', async () => {
        await conVistaPrevia();
        casillaDeLaFila(3).click();
        await asentar();
        servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE)));
        await confirmar();
        expect(marcada(3)).toBe(false);

        q<HTMLButtonElement>('button.generar').click();
        await asentar();

        expect(marcada(3)).toBe(true);
      });
    });

    describe('«Crear los artículos nuevos» y las filas que se aplican', () => {
      const desmarcarTodas = async () => {
        Array.from(el().querySelectorAll('button'))
          .find((b) => b.textContent?.trim() === 'Desmarcar todas' && b.closest('app-vista-previa-tabla'))!
          .click();
        await asentar();
      };
      const tarjetas = () => Array.from(el().querySelectorAll('.tarjeta')).map((t) => texto(t));
      const conVistaPreviaSinCrearNuevos = async () => {
        await conVistaPrevia();
        q<HTMLInputElement>('#crearNuevos').click();
        await asentar();
      };

      it('con crear nuevos apagado, si solo quedan filas nuevas Confirmar se deshabilita y explica la casilla', async () => {
        await conVistaPreviaSinCrearNuevos();
        await desmarcarTodas();

        casillaDeLaFila(4).click();
        await asentar();

        expect(q<HTMLButtonElement>('button.confirmar').disabled).toBe(true);
        // Hay filas elegidas: «Elegí al menos una fila.» contradiría el contador. Lo que falta es la casilla.
        expect(el().querySelector('#sin-filas-elegidas')).toBeNull();
        expect(q('#nuevos-sin-crear').textContent).toContain('Tildala para crearlo.');
        expect(q('button.confirmar').getAttribute('aria-describedby')).toContain('nuevos-sin-crear');
      });

      it('las tarjetas siguen contando la nueva elegida: el backend la cuenta para la deriva', async () => {
        await conVistaPreviaSinCrearNuevos();
        await desmarcarTodas();

        casillaDeLaFila(4).click();
        await asentar();

        expect(tarjetas()).toContain('Nuevos 1');
        expect(tarjetas()).toContain('Suben 0 de 2');
      });

      it('al encender crear nuevos la nueva elegida se aplica y Confirmar se habilita', async () => {
        await conVistaPreviaSinCrearNuevos();
        await desmarcarTodas();
        casillaDeLaFila(4).click();
        await asentar();

        q<HTMLInputElement>('#crearNuevos').click();
        await asentar();

        expect(q<HTMLButtonElement>('button.confirmar').disabled).toBe(false);
        expect(el().querySelector('#sin-filas-elegidas')).toBeNull();
        servicio.aplicar.mockReturnValue(of(importacion));
        await confirmar();
        const [, datos] = servicio.aplicar.mock.calls[0];
        expect(datos.crearNuevos).toBe(true);
        expect(datos.filasElegidas).toEqual([{ hoja: 0, fila: 4 }]);
      });

      it('al volver a apagarlo, vuelve el aviso y Confirmar no hace nada', async () => {
        await conVistaPreviaSinCrearNuevos();
        await desmarcarTodas();
        casillaDeLaFila(4).click();
        await asentar();
        q<HTMLInputElement>('#crearNuevos').click();
        await asentar();

        q<HTMLInputElement>('#crearNuevos').click();
        await asentar();

        expect(q<HTMLButtonElement>('button.confirmar').disabled).toBe(true);
        expect(q('#nuevos-sin-crear')).not.toBeNull();
        expect(servicio.aplicar).not.toHaveBeenCalled();
      });

      it('con crear nuevos apagado y nuevas elegidas, avisa junto a la casilla que no se van a crear', async () => {
        await conVistaPreviaSinCrearNuevos();
        await desmarcarTodas();
        casillaDeLaFila(4).click();
        await asentar();
        casillaDeLaFila(2).click();
        await asentar();

        expect(q('#nuevos-sin-crear').textContent).toContain(
          'Elegiste 1 artículo nuevo. Con la casilla apagada no se crea. Tildala para crearlo.',
        );
        expect(q('#crearNuevos').getAttribute('aria-describedby')).toContain('nuevos-sin-crear');
      });

      it('sin el aviso si la casilla está encendida o no hay nuevas elegidas', async () => {
        await conVistaPreviaSinCrearNuevos();
        await desmarcarTodas();
        casillaDeLaFila(2).click();
        await asentar();
        expect(el().querySelector('#nuevos-sin-crear')).toBeNull();

        casillaDeLaFila(4).click();
        await asentar();
        expect(q('#nuevos-sin-crear')).not.toBeNull();

        q<HTMLInputElement>('#crearNuevos').click();
        await asentar();
        expect(el().querySelector('#nuevos-sin-crear')).toBeNull();
      });

      it('el resultado avisa cuántos artículos nuevos no se crearon', async () => {
        await conVistaPreviaSinCrearNuevos();
        await desmarcarTodas();
        casillaDeLaFila(4).click();
        await asentar();
        casillaDeLaFila(2).click();
        await asentar();

        servicio.aplicar.mockReturnValue(of(importacion));
        await confirmar();

        expect(q('.resumen .nuevos-no-creados').textContent).toContain(
          '1 artículo nuevo no se creó porque «Crear los artículos nuevos» estaba apagada.',
        );
      });

      it('el resultado no avisa nada si se crearon los nuevos', async () => {
        await conVistaPrevia();
        await desmarcarTodas(); // con la casilla tildada, como arranca
        casillaDeLaFila(4).click();
        await asentar();

        servicio.aplicar.mockReturnValue(of(importacion));
        await confirmar();

        expect(q('.resumen')).not.toBeNull();
        expect(el().querySelector('.resumen .nuevos-no-creados')).toBeNull();
      });

      it('si junto a la nueva hay otra fila elegida, Confirmar queda habilitado aun con crear nuevos apagado', async () => {
        await conVistaPreviaSinCrearNuevos();
        await desmarcarTodas();

        casillaDeLaFila(4).click();
        casillaDeLaFila(2).click();
        await asentar();

        expect(q<HTMLButtonElement>('button.confirmar').disabled).toBe(false);
        expect(el().querySelector('#sin-filas-elegidas')).toBeNull();
      });
    });

    it('si pedir de nuevo la vista previa falla, ofrece «Volver a generar la vista previa»', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, 'La lista cambió.')));
      servicio.vistaPrevia.mockReturnValue(throwError(() => errorHttp(500, 'Falló')));

      await confirmar();

      expect(el().querySelector('.vista-previa')).toBeNull();
      expect(q('button.regenerar').textContent).toContain('Volver a generar la vista previa');

      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      q<HTMLButtonElement>('button.regenerar').click();
      await asentar();

      expect(el().querySelector('.vista-previa')).not.toBeNull();
      expect(el().querySelector('button.regenerar')).toBeNull();
    });

    it('con 400 por campo muestra los mensajes de cada campo', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: {
                status: 400,
                message: 'Datos inválidos',
                errors: {
                  bonificacion2: 'La bonificación 2 debe estar entre 0 y 100.',
                  'conteosEsperados.suben': 'Faltan los contadores de la vista previa.',
                },
              },
            }),
        ),
      );

      await confirmar();

      const alerta = q('[role="alert"]');
      expect(alerta.textContent).toContain('La bonificación 2 debe estar entre 0 y 100.');
      expect(alerta.textContent).toContain('Faltan los contadores de la vista previa.');
      expect(alerta.textContent).not.toContain('Datos inválidos');
      expect(el().querySelector('.vista-previa')).not.toBeNull();
    });

    it('con un 404 muestra el mensaje del backend y conserva la vista previa', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(throwError(() => errorHttp(404, 'Rubro no encontrado')));

      await confirmar();

      expect(q('[role="alert"]').textContent).toContain('Rubro no encontrado');
      expect(el().querySelector('.vista-previa')).not.toBeNull();
    });

    it('un error que no es HTTP muestra un mensaje genérico', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(throwError(() => new Error('caído')));

      await confirmar();

      expect(q('[role="alert"]').textContent).toContain('No se pudo aplicar la importación.');
    });

    it('no deja confirmar dos veces mientras se aplica', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(new Subject());

      q<HTMLButtonElement>('button.confirmar').click();
      fixture.detectChanges();

      expect(q<HTMLButtonElement>('button.confirmar').disabled).toBe(true);
    });
  });

  describe('pulido de la pantalla (H-07, H-16)', () => {
    const vistaPrevia = vistaPreviaDe({
      conteos: { ...CONTEOS_VACIOS, suben: 1 },
      cambios: [cambioDe({})],
    });
    const importacion: Importacion = {
      id: 9,
      proveedorId: 1,
      proveedorNombre: 'Sinteplast',
      usuarioId: 3,
      archivo: 'lista.xlsx',
      fecha: '2026-10-12T10:00:00',
      conteos: vistaPrevia.conteos,
      estado: 'APLICADA',
    };

    describe('selector de archivo propio', () => {
      it('no muestra el "Choose File" nativo: hay un botón "Elegir archivo…"', async () => {
        await crearComponente();

        expect(q('#archivo').hidden).toBe(true);
        const boton = q<HTMLButtonElement>('button.elegir-archivo');
        expect(boton.textContent?.trim()).toBe('Elegir archivo…');
        expect(boton.type).toBe('button');
        expect(q('.nombre-archivo').textContent?.trim()).toBe('Ningún archivo elegido');
      });

      it('el botón abre el selector del navegador', async () => {
        await crearComponente();
        const clic = vi.spyOn(q<HTMLInputElement>('#archivo'), 'click');

        q<HTMLButtonElement>('button.elegir-archivo').click();

        expect(clic).toHaveBeenCalledTimes(1);
      });

      it('muestra el nombre del archivo elegido', async () => {
        await crearComponente();

        await elegirArchivo(new File(['x'], 'sinteplast-octubre.xlsx'));

        expect(q('.nombre-archivo').textContent?.trim()).toBe('sinteplast-octubre.xlsx');
      });

      it('arrastrar y soltar un archivo lo elige', async () => {
        await crearComponente();
        const archivo = lista();
        const soltar = new Event('drop', { bubbles: true, cancelable: true });
        Object.defineProperty(soltar, 'dataTransfer', { value: { files: [archivo] } });

        q('.zona-archivo').dispatchEvent(soltar);
        await asentar();

        expect(servicio.muestra).toHaveBeenCalledWith(archivo);
        expect(q('.nombre-archivo').textContent?.trim()).toBe('lista.xlsx');
      });

      it('un archivo de más de 25 MB soltado se rechaza con el aviso', async () => {
        await crearComponente();
        const grande = lista();
        Object.defineProperty(grande, 'size', { value: 25 * 1024 * 1024 + 1 });
        const soltar = new Event('drop', { bubbles: true, cancelable: true });
        Object.defineProperty(soltar, 'dataTransfer', { value: { files: [grande] } });

        q('.zona-archivo').dispatchEvent(soltar);
        await asentar();

        expect(q('[role="alert"]').textContent).toContain('El archivo supera el máximo de 25 MB.');
        expect(q('.nombre-archivo').textContent?.trim()).toBe('Ningún archivo elegido');
        expect(servicio.muestra).not.toHaveBeenCalled();
      });

      it('un PDF soltado se acepta y trae la muestra', async () => {
        await crearComponente();
        const soltar = new Event('drop', { bubbles: true, cancelable: true });
        Object.defineProperty(soltar, 'dataTransfer', { value: { files: [new File(['x'], 'lista.pdf')] } });

        q('.zona-archivo').dispatchEvent(soltar);
        await asentar();

        expect(servicio.muestra).toHaveBeenCalledTimes(1);
        expect(q('.nombre-archivo').textContent?.trim()).toBe('lista.pdf');
      });

      describe('mientras se trabaja con la lista no se elige otro archivo', () => {
        const soltarArchivo = async (archivo: File) => {
          const soltar = new Event('drop', { bubbles: true, cancelable: true });
          Object.defineProperty(soltar, 'dataTransfer', { value: { files: [archivo] } });
          q('.zona-archivo').dispatchEvent(soltar);
          await asentar();
        };

        it('mientras se lee la lista, el botón está deshabilitado y soltar otro archivo no hace nada', async () => {
          await crearComponente();
          const enVuelo = new Subject<Muestra>();
          servicio.muestra.mockReturnValue(enVuelo);
          await elegirArchivo(lista());

          expect(q<HTMLButtonElement>('button.elegir-archivo').disabled).toBe(true);
          expect(q<HTMLInputElement>('#archivo').disabled).toBe(true);
          expect(q('.zona-archivo').getAttribute('aria-disabled')).toBe('true');
          await soltarArchivo(new File(['y'], 'otra.csv'));
          expect(servicio.muestra).toHaveBeenCalledTimes(1);
          expect(q('.nombre-archivo').textContent?.trim()).toBe('lista.xlsx');

          enVuelo.next(muestra);
          enVuelo.complete();
          await asentar();
          expect(q<HTMLButtonElement>('button.elegir-archivo').disabled).toBe(false);
          await soltarArchivo(new File(['y'], 'otra.csv'));
          expect(servicio.muestra).toHaveBeenCalledTimes(2);
        });

        it('si la lectura falla, se puede elegir otro archivo', async () => {
          await crearComponente();
          servicio.muestra.mockReturnValue(throwError(() => errorHttp(400, 'No se pudo leer el CSV.')));
          await elegirArchivo(lista());

          expect(q<HTMLButtonElement>('button.elegir-archivo').disabled).toBe(false);
        });

        it('mientras se genera la vista previa o se importa, tampoco', async () => {
          await crearComponente();
          await elegir('#proveedor', 'Sinteplast');
          await elegirArchivo(lista());
          servicio.vistaPrevia.mockReturnValue(new Subject());

          q<HTMLButtonElement>('button.generar').click();
          await asentar();

          expect(q<HTMLButtonElement>('button.elegir-archivo').disabled).toBe(true);
        });

        it('el aviso de lectura cambia cada 3 segundos y se queda en el último', async () => {
          await crearComponente();
          servicio.muestra.mockReturnValue(new Subject<Muestra>());
          vi.useFakeTimers();
          try {
            await elegirArchivo(lista());
            const aviso = () => q('.leyendo').textContent?.trim();

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

        it('cada archivo nuevo arranca el aviso desde el principio', async () => {
          await crearComponente();
          vi.useFakeTimers();
          try {
            const primera = new Subject<Muestra>();
            servicio.muestra.mockReturnValue(primera);
            await elegirArchivo(lista());
            vi.advanceTimersByTime(9000);
            primera.error(errorHttp(400, 'No se pudo leer el CSV.'));
            fixture.detectChanges();

            servicio.muestra.mockReturnValue(new Subject<Muestra>());
            await elegirArchivo(new File(['y'], 'otra.csv'));

            expect(q('.leyendo').textContent?.trim()).toBe('Leyendo la lista…');
          } finally {
            vi.useRealTimers();
          }
        });
      });

      it('una imagen soltada se rechaza', async () => {
        await crearComponente();
        const soltar = new Event('drop', { bubbles: true, cancelable: true });
        Object.defineProperty(soltar, 'dataTransfer', { value: { files: [new File(['x'], 'lista.png')] } });

        q('.zona-archivo').dispatchEvent(soltar);
        await asentar();

        expect(q('[role="alert"]').textContent).toContain('El archivo tiene que ser una planilla de Excel');
        expect(servicio.muestra).not.toHaveBeenCalled();
      });
    });

    describe('resultado de la importación', () => {
      async function importada() {
        await crearComponente();
        servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
        servicio.aplicar.mockReturnValue(of(importacion));
        await elegir('#proveedor', 'Sinteplast');
        await elegirArchivo(lista());
        q<HTMLButtonElement>('button.generar').click();
        await asentar();
        q<HTMLButtonElement>('button.confirmar').click();
        await asentar();
      }

      it('reemplaza los pasos por el resultado, que recibe el foco', async () => {
        await importada();

        expect(q('.resumen').textContent).toContain('Importación aplicada');
        expect(document.activeElement).toBe(q('.resumen'));
        for (const ya of ['#proveedor', '#archivo', '#filaDesde', 'button.generar', 'button.confirmar', '.vista-previa']) {
          expect(el().querySelector(ya), ya).toBeNull();
        }
      });

      it('"Importar otra lista" vuelve al paso 1 vacío', async () => {
        await importada();
        servicio.muestra.mockClear();

        const otra = Array.from(el().querySelectorAll('button')).find(
          (b) => b.textContent?.trim() === 'Importar otra lista',
        )!;
        otra.click();
        await asentar();

        expect(el().querySelector('.resumen')).toBeNull();
        expect(seleccionado('#proveedor')).toBe('Elegí un proveedor');
        expect(q('.nombre-archivo').textContent?.trim()).toBe('Ningún archivo elegido');
        expect(el().querySelector('.visor')).toBeNull();
        expect(el().querySelector('button.generar')).toBeNull();
      });

      it('al importar otra lista, el mapeo vuelve a empezar desde la propuesta del archivo nuevo', async () => {
        await importada();
        Array.from(el().querySelectorAll('button'))
          .find((b) => b.textContent?.trim() === 'Importar otra lista')!
          .click();
        await asentar();

        await elegir('#proveedor', 'Sinteplast');
        await elegirArchivo(lista());

        expect(seleccionado('#columnaCodigo')).toBe('A · Codigo');
        expect(seleccionado('#rubro')).toBe('Sin rubro (margen por defecto)');
      });

      it('al importar otra lista, «Crear los artículos nuevos» vuelve a arrancar tildada', async () => {
        await crearComponente();
        servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
        servicio.aplicar.mockReturnValue(of(importacion));
        await elegir('#proveedor', 'Sinteplast');
        await elegirArchivo(lista());
        q<HTMLButtonElement>('button.generar').click();
        await asentar();
        q<HTMLInputElement>('#crearNuevos').click();
        await asentar();
        q<HTMLButtonElement>('button.confirmar').click();
        await asentar();
        Array.from(el().querySelectorAll('button'))
          .find((b) => b.textContent?.trim() === 'Importar otra lista')!
          .click();
        await asentar();

        await elegir('#proveedor', 'Sinteplast');
        await elegirArchivo(lista());
        q<HTMLButtonElement>('button.generar').click();
        await asentar();

        expect(q<HTMLInputElement>('#crearNuevos').checked).toBe(true);
      });
    });
  });

  describe('accesibilidad', () => {
    const vistaPrevia = vistaPreviaDe({
      conteos: { ...CONTEOS_VACIOS, suben: 1 },
      cambios: [cambioDe({})],
    });

    const descripciones = (selector: string) =>
      (q(selector).getAttribute('aria-describedby') ?? '')
        .split(' ')
        .filter(Boolean)
        .map((id) => el().querySelector(`[id="${id}"]`)?.textContent?.trim());

    async function conMuestra() {
      await crearComponente();
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(lista());
    }

    it('«Leyendo la lista…» es un status', async () => {
      await crearComponente();
      servicio.muestra.mockReturnValue(new Subject<Muestra>());
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(lista());

      const leyendo = Array.from(el().querySelectorAll('[role="status"]')).find((p) =>
        p.textContent?.includes('Leyendo la lista…'),
      );
      expect(leyendo).toBeDefined();
    });

    it('el archivo elegido se anuncia y el tope dice «25 MB» sin cortar', async () => {
      await crearComponente();

      expect(q('.nombre-archivo').getAttribute('aria-live')).toBe('polite');
      expect(q('app-selector-archivo').textContent).toContain('25 MB');
      expect(q('app-selector-archivo').textContent).not.toContain('10');
    });

    it('el contenedor interno del scroll virtual no corta la relación entre rowgroup y row', async () => {
      await conMuestra();

      const envoltorio = q('.visor-cuerpo .cdk-virtual-scroll-content-wrapper');
      expect(envoltorio.getAttribute('role')).toBe('presentation');
    });

    it('los números abren el teclado numérico', async () => {
      await conMuestra();

      expect(q('#filaDesde').getAttribute('inputmode')).toBe('numeric');
      for (const id of ['#bonificacion1', '#bonificacion2', '#bonificacion3']) {
        expect(q(id).getAttribute('inputmode')).toBe('decimal');
      }
    });

    it('un campo con error queda inválido y enlazado a su mensaje', async () => {
      await conMuestra();
      expect(q('#filaDesde').hasAttribute('aria-invalid')).toBe(false);

      await escribir('#filaDesde', '0');
      await escribir('#bonificacion2', '150');

      expect(q('#filaDesde').getAttribute('aria-invalid')).toBe('true');
      expect(descripciones('#filaDesde')).toContain('Indicá la primera fila de datos (1 o más).');
      expect(q('#bonificacion2').getAttribute('aria-invalid')).toBe('true');
      expect(descripciones('#bonificacion2')).toEqual(['La bonificación 2 tiene que estar entre 0 y 100.']);
      expect(q('#bonificacion1').hasAttribute('aria-invalid')).toBe(false);
    });

    it('pedir la vista previa sin columnas marca la del código y le da el foco', async () => {
      await crearComponente();
      servicio.muestra.mockReturnValue(of(muestraSinReconocer));
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(lista());

      q<HTMLButtonElement>('button.generar').click();
      await asentar();

      expect(q('#columnaCodigo').getAttribute('aria-invalid')).toBe('true');
      expect(q('#columnaCosto').getAttribute('aria-invalid')).toBe('true');
      expect(document.activeElement).toBe(q('#columnaCodigo'));
    });

    it('con las columnas elegidas y una bonificación mal, el foco va a esa bonificación', async () => {
      await conMuestra();
      await escribir('#bonificacion3', '-1');

      q<HTMLButtonElement>('button.generar').click();
      await asentar();

      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(q('#bonificacion3'));
    });

    it('mientras se genera la vista previa el botón lo dice', async () => {
      await conMuestra();
      servicio.vistaPrevia.mockReturnValue(new Subject<VistaPrevia>());

      q<HTMLButtonElement>('button.generar').click();
      await asentar();

      expect(q('button.generar').textContent?.trim()).toBe('Generando vista previa…');
      expect(q<HTMLButtonElement>('button.generar').disabled).toBe(true);
    });

    it('mientras se aplica la importación el botón lo dice', async () => {
      await conMuestra();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      servicio.aplicar.mockReturnValue(new Subject<Importacion>());
      q<HTMLButtonElement>('button.generar').click();
      await asentar();

      q<HTMLButtonElement>('button.confirmar').click();
      await asentar();

      expect(q('button.confirmar').textContent?.trim()).toBe('Importando…');
      expect(q<HTMLButtonElement>('button.confirmar').disabled).toBe(true);
    });
  });
});
