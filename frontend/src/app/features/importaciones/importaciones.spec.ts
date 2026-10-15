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
import { Importacion, Muestra, VistaPrevia } from './importacion.models';
import { Importaciones } from './importaciones';
import { ImportacionesService } from './importaciones.service';

registerLocaleData(localeEsAr);

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
      encabezados: { codigo: 'Codigo', descripcion: 'Detalle', costo: 'Precio' },
    },
    listaIncluyeIva: true,
    bonificacion1: 40,
    bonificacion2: 10,
    bonificacion3: null,
  };

  const pinturas = { id: 1, nombre: 'Pinturas', margenPorcentaje: 30 };

  const muestra: Muestra = {
    hojas: ['Lista', 'Oferta'],
    hoja: 0,
    columnas: 3,
    filas: [
      { numero: 1, celdas: ['Codigo', 'Descripcion', 'Precio'] },
      { numero: 2, celdas: ['A-1', 'Tornillo', '1.234,56'] },
    ],
  };

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
    it('el input de archivo acepta xlsx, xls y csv', async () => {
      await crearComponente();

      expect(q('#archivo').getAttribute('accept')).toBe('.xlsx,.xls,.csv');
    });

    it('un archivo de más de 10 MB muestra el aviso y no llama al servicio', async () => {
      await crearComponente();
      const grande = lista();
      Object.defineProperty(grande, 'size', { value: 10 * 1024 * 1024 + 1 });

      await elegirArchivo(grande);

      expect(q('[role="alert"]').textContent).toContain('El archivo supera el máximo de 10 MB.');
      expect(servicio.muestra).not.toHaveBeenCalled();
    });

    it('un archivo de exactamente 10 MB se acepta', async () => {
      await crearComponente();
      const justo = lista();
      Object.defineProperty(justo, 'size', { value: 10 * 1024 * 1024 });

      await elegirArchivo(justo);

      expect(servicio.muestra).toHaveBeenCalledTimes(1);
    });

    it('al elegir el archivo trae la muestra de la hoja 0', async () => {
      await crearComponente();
      const archivo = lista();

      await elegirArchivo(archivo);

      expect(servicio.muestra).toHaveBeenCalledWith(archivo, 0);
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

      const muestraGrande = (filas: number, columnas: number): Muestra => ({
        hojas: ['Lista'],
        hoja: 0,
        columnas,
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
        await conMuestra({
          hojas: ['Lista'],
          hoja: 0,
          columnas: 1,
          filas: [{ numero: 1, celdas: ['Tornillo hexagonal galvanizado de 1/4 por 2 pulgadas'] }],
        });

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
        await conMuestra({ hojas: ['Lista'], hoja: 0, columnas: 1, filas: [{ numero: 1, celdas: ['x'] }] });

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

          await elegir('#columnaCodigo', 'A');
          await elegir('#columnaDescripcion', 'B');
          await elegir('#columnaCosto', 'C');

          const [a, b, c] = columnas();
          expect(a.classList).toContain('col-codigo');
          expect(b.classList).toContain('col-descripcion');
          expect(c.classList).toContain('col-costo');
        });

        it('sin mapeo ninguna columna está resaltada', async () => {
          await crearComponente();
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
          await elegir('#columnaCosto', 'C');

          await elegir('#columnaCosto', 'B');

          const [, b, c] = columnas();
          expect(b.classList).toContain('col-costo');
          expect(c.classList).not.toContain('col-costo');
        });

        it('atenúa las filas anteriores a la primera fila de datos', async () => {
          await crearComponente();
          await elegirArchivo(lista());
          await escribir('#filaDesde', '2');

          const [encabezado, datos] = Array.from(el().querySelectorAll('.visor-fila'));
          expect(encabezado.classList).toContain('previa');
          expect(datos.classList).not.toContain('previa');
        });
      });
    });

    it('ofrece las columnas por letra; la descripción es opcional', async () => {
      await crearComponente();
      await elegirArchivo(lista());

      const letras = ['A · Codigo', 'B · Descripcion', 'C · Precio'];
      expect(textosDe('#columnaCodigo')).toEqual(['Elegí la columna', ...letras]);
      expect(textosDe('#columnaCosto')).toEqual(['Elegí la columna', ...letras]);
      expect(textosDe('#columnaDescripcion')).toEqual(['Sin descripción', ...letras]);
    });

    describe('navegación de hojas', () => {
      const anterior = () => q<HTMLButtonElement>('button[aria-label="Hoja anterior"]');
      const siguiente = () => q<HTMLButtonElement>('button[aria-label="Hoja siguiente"]');
      const etiqueta = () => q('.navegacion-hojas .hoja-actual').textContent?.trim();

      const hojas3 = ['Lista', 'Oferta', 'Histórico'];
      /** El backend devuelve siempre los nombres de todas las hojas y las filas de la pedida. */
      const muestraDe = (hojas: string[]) => (_archivo: File, hoja: number): unknown =>
        of({
          hojas,
          hoja,
          columnas: hoja === 0 ? 3 : 2,
          filas: [{ numero: 1, celdas: hoja === 0 ? ['Codigo', 'Descripcion', 'Precio'] : ['Z', 'Y'] }],
        });

      async function conArchivoDeTresHojas() {
        await crearComponente();
        servicio.muestra.mockImplementation(muestraDe(hojas3));
        const archivo = lista();
        await elegirArchivo(archivo);
        return archivo;
      }

      it('al cargar muestra la primera hoja con "Hoja 1 de N" y su nombre', async () => {
        const archivo = await conArchivoDeTresHojas();

        expect(servicio.muestra).toHaveBeenCalledWith(archivo, 0);
        expect(etiqueta()).toBe('Hoja 1 de 3 · Lista');
        expect(el().querySelector('#hoja')).toBeNull();
      });

      it('la flecha siguiente carga la hoja 1 y actualiza la grilla y la etiqueta', async () => {
        const archivo = await conArchivoDeTresHojas();

        siguiente().click();
        await asentar();

        expect(servicio.muestra).toHaveBeenLastCalledWith(archivo, 1);
        expect(etiqueta()).toBe('Hoja 2 de 3 · Oferta');
        expect(el().querySelectorAll('.visor-encabezado .columna')).toHaveLength(2);
      });

      it('la flecha anterior vuelve a la hoja previa', async () => {
        const archivo = await conArchivoDeTresHojas();
        siguiente().click();
        await asentar();

        anterior().click();
        await asentar();

        expect(servicio.muestra).toHaveBeenLastCalledWith(archivo, 0);
        expect(etiqueta()).toBe('Hoja 1 de 3 · Lista');
      });

      it('en la primera hoja la flecha anterior está deshabilitada y no llama al servicio', async () => {
        await conArchivoDeTresHojas();
        servicio.muestra.mockClear();

        expect(anterior().disabled).toBe(true);
        expect(siguiente().disabled).toBe(false);
        anterior().click();
        await asentar();

        expect(servicio.muestra).not.toHaveBeenCalled();
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

      it('las flechas se deshabilitan mientras se lee la hoja', async () => {
        await conArchivoDeTresHojas();
        const respuesta = new Subject<Muestra>();
        servicio.muestra.mockReturnValue(respuesta);

        siguiente().click();
        fixture.detectChanges();

        expect(siguiente().disabled).toBe(true);
        expect(anterior().disabled).toBe(true);
        respuesta.next({ hojas: hojas3, hoja: 1, columnas: 2, filas: [] });
        await asentar();
        expect(etiqueta()).toBe('Hoja 2 de 3 · Oferta');
        expect(siguiente().disabled).toBe(false);
      });

      it('un archivo de una sola hoja no muestra la navegación', async () => {
        await crearComponente();
        servicio.muestra.mockImplementation(muestraDe(['Hoja1']));

        await elegirArchivo(lista());

        expect(el().querySelector('.navegacion-hojas')).toBeNull();
        expect(el().querySelector('button[aria-label="Hoja anterior"]')).toBeNull();
        expect(el().querySelector('button[aria-label="Hoja siguiente"]')).toBeNull();
        expect(el().querySelector('.visor')).not.toBeNull();
      });

      it('con un proveedor que guardó otra hoja, abre esa hoja y las flechas parten de ahí', async () => {
        await crearComponente();
        servicio.muestra.mockImplementation(muestraDe(hojas3));
        await elegir('#proveedor', 'Tersuave');
        const archivo = lista();

        await elegirArchivo(archivo);

        expect(servicio.muestra).toHaveBeenCalledWith(archivo, 1);
        expect(etiqueta()).toBe('Hoja 2 de 3 · Oferta');
        expect(anterior().disabled).toBe(false);
        expect(siguiente().disabled).toBe(false);
      });

      it('la navegación es accesible: botones con nombre y la etiqueta anuncia los cambios', async () => {
        await conArchivoDeTresHojas();

        expect(anterior().getAttribute('type')).toBe('button');
        expect(siguiente().getAttribute('type')).toBe('button');
        expect(q('.navegacion-hojas .hoja-actual').getAttribute('aria-live')).toBe('polite');
      });
    });

    it('sin proveedor con mapeo guardado, los valores arrancan vacíos', async () => {
      await crearComponente();
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(lista());

      expect(seleccionado('#columnaCodigo')).toBe('Elegí la columna');
      expect(seleccionado('#columnaDescripcion')).toBe('Sin descripción');
      expect(seleccionado('#columnaCosto')).toBe('Elegí la columna');
      expect(q<HTMLInputElement>('#filaDesde').value).toBe('2');
      expect(q<HTMLInputElement>('#listaIncluyeIva').checked).toBe(false);
      expect(q<HTMLInputElement>('#bonificacion1').value).toBe('');
      expect(q<HTMLInputElement>('#bonificacion2').value).toBe('');
      expect(q<HTMLInputElement>('#bonificacion3').value).toBe('');
    });

    it('precarga mapeo, IVA y bonificaciones del proveedor elegido antes del archivo', async () => {
      await crearComponente();
      const archivo = lista();
      await elegir('#proveedor', 'Tersuave');

      await elegirArchivo(archivo);

      expect(servicio.muestra).toHaveBeenCalledWith(archivo, 1);
      expect(seleccionado('#columnaCodigo')).toBe('B · Descripcion');
      expect(seleccionado('#columnaDescripcion')).toBe('A · Codigo');
      expect(seleccionado('#columnaCosto')).toBe('C · Precio');
      expect(q<HTMLInputElement>('#filaDesde').value).toBe('3');
      expect(q<HTMLInputElement>('#listaIncluyeIva').checked).toBe(true);
      expect(q<HTMLInputElement>('#bonificacion1').value).toBe('40');
      expect(q<HTMLInputElement>('#bonificacion2').value).toBe('10');
      expect(q<HTMLInputElement>('#bonificacion3').value).toBe('');
    });

    it('si el proveedor se elige después del archivo, precarga y recarga la hoja guardada', async () => {
      await crearComponente();
      const archivo = lista();
      await elegirArchivo(archivo);
      servicio.muestra.mockClear();

      await elegir('#proveedor', 'Tersuave');

      expect(servicio.muestra).toHaveBeenCalledWith(archivo, 1);
      expect(seleccionado('#columnaCodigo')).toBe('B · Descripcion');
      expect(q<HTMLInputElement>('#bonificacion1').value).toBe('40');
    });

    it('al cambiar a un proveedor sin configuración, limpia lo precargado', async () => {
      await crearComponente();
      await elegir('#proveedor', 'Tersuave');
      await elegirArchivo(lista());

      await elegir('#proveedor', 'Sinteplast');

      expect(seleccionado('#columnaCodigo')).toBe('Elegí la columna');
      expect(q<HTMLInputElement>('#listaIncluyeIva').checked).toBe(false);
      expect(q<HTMLInputElement>('#bonificacion1').value).toBe('');
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
        'Columna del código',
        'Columna de la descripción (opcional)',
        'Columna del costo',
        'Primera fila de datos',
        'La lista incluye IVA',
        'Bonificación 1 (%)',
        'Bonificación 2 (%)',
        'Bonificación 3 (%)',
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
  });

  describe('paso 3: vista previa', () => {
    const conteos = { suben: 2, bajan: 1, nuevos: 1, sinCambio: 3, noEstan: 1, conError: 1 };
    const vistaPrevia: VistaPrevia = {
      conteos,
      cambios: [
        {
          fila: 2,
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
        },
        {
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
        },
      ],
      nuevos: [{ fila: 4, codigo: 'N-9', descripcion: 'Arandela', precioLista: 20, costo: 18 }],
      errores: [{ fila: 5, codigo: 'X-1', mensaje: 'El costo es cero o está vacío.' }],
      advertencias: [
        { fila: null, codigo: null, mensaje: 'El encabezado de la columna del costo cambió.' },
        { fila: 3, codigo: 'A-2', mensaje: 'El costo varía 400 % respecto del actual.' },
      ],
      noEstan: [
        { id: 20, codigoInterno: 'INT-20', codigoProveedor: 'Z-1', descripcion: 'Clavo largo' },
      ],
      detalleRecortado: false,
    };

    /** Deja el formulario listo: Sinteplast, archivo, código en A, costo en C. */
    async function prepararMapeo() {
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(lista());
      await elegir('#columnaCodigo', 'A');
      await elegir('#columnaCosto', 'C');
    }

    async function generar() {
      q<HTMLButtonElement>('button.generar').click();
      await asentar();
    }

    const tarjetas = () =>
      Array.from(el().querySelectorAll('.tarjeta')).map((t) =>
        t.textContent?.replace(/\s+/g, ' ').trim(),
      );
    const filasDe = (selector: string) =>
      Array.from(el().querySelectorAll(`${selector} tbody tr`)).map((tr) =>
        Array.from(tr.querySelectorAll('td')).map((td) => td.textContent?.replace(/\s+/g, ' ').trim()),
      );

    it('no se ve la vista previa hasta pedirla', async () => {
      await crearComponente();
      await prepararMapeo();

      expect(el().querySelector('.vista-previa')).toBeNull();
      expect(q('button.generar').textContent).toContain('Ver vista previa');
    });

    it('manda archivo, proveedor, mapeo, IVA y bonificaciones', async () => {
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

      await generar();

      expect(servicio.vistaPrevia).toHaveBeenCalledWith(archivo, {
        proveedorId: 1,
        mapeo: { hoja: 0, filaDesde: 2, columnaCodigo: 0, columnaDescripcion: 1, columnaCosto: 2 },
        listaIncluyeIva: true,
        bonificacion1: 40,
        bonificacion2: 10,
        bonificacion3: null,
      });
    });

    it('sin proveedor no llama al servicio y lo avisa', async () => {
      await crearComponente();
      await elegirArchivo(lista());
      await elegir('#columnaCodigo', 'A');
      await elegir('#columnaCosto', 'C');

      await generar();

      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
      expect(q('[role="alert"]').textContent).toContain('Elegí el proveedor.');
    });

    it('sin columna de código o de costo no llama al servicio y lo avisa', async () => {
      await crearComponente();
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(lista());

      await generar();

      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
      expect(q('[role="alert"]').textContent).toContain(
        'Elegí las columnas del código y del costo.',
      );
    });

    it('con una bonificación inválida no llama al servicio', async () => {
      await crearComponente();
      await prepararMapeo();
      await escribir('#bonificacion1', '150');

      await generar();

      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
    });

    it('muestra las tarjetas con los seis contadores', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await prepararMapeo();

      await generar();

      expect(tarjetas()).toEqual([
        'Suben 2',
        'Bajan 1',
        'Nuevos 1',
        'Sin cambio 3',
        'No están 1',
        'Con error 1',
      ]);
    });

    it('lista los cambios con precio de lista, costos, precios y tipo de cambio en es-AR', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await prepararMapeo();

      await generar();

      const filas = filasDe('table.cambios');
      expect(filas).toHaveLength(2);
      expect(filas[0]).toEqual([
        '2',
        'A-1',
        'Tornillo 6mm',
        '$ 1.234,56',
        '$ 1.000,00',
        '$ 1.111,10',
        '$ 1.300,00',
        '$ 1.444,43',
        'Sube',
      ]);
      expect(filas[1][1]).toBe('A-2');
      expect(filas[1][2]).toContain('Tuerca 6mm');
      expect(filas[1].slice(6)).toEqual(['$ 130,00', '$ 650,00', 'Sube']);
    });

    it('los encabezados de los cambios incluyen el precio anterior y el precio nuevo', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await prepararMapeo();

      await generar();

      const encabezados = Array.from(el().querySelectorAll('table.cambios thead th')).map((th) =>
        th.textContent?.trim(),
      );
      expect(encabezados).toEqual([
        'Fila',
        'Código',
        'Descripción',
        'Precio de lista',
        'Costo anterior',
        'Costo nuevo',
        'Precio anterior',
        'Precio nuevo',
        'Cambio',
      ]);
    });

    it('un cambio que baja el costo se marca como Baja y uno que lo sube como Sube', async () => {
      await crearComponente();
      const baja = { ...vistaPrevia.cambios[0], fila: 6, costoAnterior: 200, costoNuevo: 150.5, precioAnterior: 260, precioNuevo: 195.65 };
      servicio.vistaPrevia.mockReturnValue(
        of({ ...vistaPrevia, cambios: [vistaPrevia.cambios[0], baja] }),
      );
      await prepararMapeo();

      await generar();

      const filas = filasDe('table.cambios');
      expect(filas[0][8]).toBe('Sube');
      expect(filas[1].slice(4)).toEqual(['$ 200,00', '$ 150,50', '$ 260,00', '$ 195,65', 'Baja']);
      const etiquetas = el().querySelectorAll('table.cambios tbody tr:nth-child(2) .etiqueta-baja');
      expect(etiquetas).toHaveLength(1);
    });

    it('resalta las filas con variación fuerte y marca los artículos inactivos', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await prepararMapeo();

      await generar();

      const filas = el().querySelectorAll('table.cambios tbody tr');
      expect(filas[0].classList.contains('fuerte')).toBe(false);
      expect(filas[1].classList.contains('fuerte')).toBe(true);
      expect(filas[1].textContent).toContain('Inactivo');
      expect(filas[0].textContent).not.toContain('Inactivo');
    });

    it('lista los artículos nuevos, los errores y las advertencias', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await prepararMapeo();

      await generar();

      expect(filasDe('table.nuevos')).toEqual([['4', 'N-9', 'Arandela', '$ 20,00', '$ 18,00']]);
      expect(filasDe('table.errores')).toEqual([
        ['5', 'X-1', 'El costo es cero o está vacío.'],
      ]);
      const advertencias = q('.advertencias').textContent ?? '';
      expect(advertencias).toContain('El encabezado de la columna del costo cambió.');
      expect(advertencias).toContain('Fila 3');
      expect(advertencias).toContain('A-2');
      expect(advertencias).toContain('El costo varía 400 % respecto del actual.');
    });

    it('no pinta las secciones vacías', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(
        of({
          ...vistaPrevia,
          cambios: [],
          nuevos: [],
          errores: [],
          advertencias: [],
          noEstan: [],
        }),
      );
      await prepararMapeo();

      await generar();

      for (const selector of [
        'table.cambios',
        'table.nuevos',
        'table.errores',
        '.advertencias',
        'details.no-estan',
      ]) {
        expect(el().querySelector(selector), selector).toBeNull();
      }
    });

    it('"No están en la lista" es un bloque colapsable', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await prepararMapeo();

      await generar();

      const detalle = q<HTMLDetailsElement>('details.no-estan');
      expect(detalle.open).toBe(false);
      expect(detalle.querySelector('summary')?.textContent).toContain('No están en la lista');
      expect(detalle.textContent).toContain('INT-20');
      expect(detalle.textContent).toContain('Clavo largo');
    });

    it('avisa si el detalle está recortado, y no si no lo está', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await prepararMapeo();
      await generar();
      expect(el().querySelector('.recortado')).toBeNull();

      servicio.vistaPrevia.mockReturnValue(of({ ...vistaPrevia, detalleRecortado: true }));
      await generar();

      expect(q('.recortado').textContent).toContain('Se muestran solo las primeras 1.000 filas');
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

    describe('una vista previa vieja nunca se confirma', () => {
      const cambios: [string, () => Promise<void>][] = [
        ['el proveedor', () => elegir('#proveedor', 'Tersuave')],
        ['el archivo', () => elegirArchivo(new File(['y'], 'otra.xlsx'))],
        [
          'la hoja (flecha siguiente)',
          async () => q<HTMLButtonElement>('button[aria-label="Hoja siguiente"]').click(),
        ],
        ['la columna del código', () => elegir('#columnaCodigo', 'B')],
        ['la columna de la descripción', () => elegir('#columnaDescripcion', 'B')],
        ['la columna del costo', () => elegir('#columnaCosto', 'B')],
        ['la primera fila de datos', () => escribir('#filaDesde', '5')],
        ['la casilla de IVA', async () => q<HTMLInputElement>('#listaIncluyeIva').click()],
        ['la bonificación 1', () => escribir('#bonificacion1', '5')],
        ['la bonificación 2', () => escribir('#bonificacion2', '5')],
        ['la bonificación 3', () => escribir('#bonificacion3', '5')],
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
    const conteos = { suben: 2, bajan: 1, nuevos: 1, sinCambio: 3, noEstan: 1, conError: 1 };
    const vistaPrevia: VistaPrevia = {
      conteos,
      cambios: [],
      nuevos: [{ fila: 4, codigo: 'N-9', descripcion: 'Arandela', precioLista: 20, costo: 18 }],
      errores: [],
      advertencias: [],
      noEstan: [],
      detalleRecortado: false,
    };
    const importacion: Importacion = {
      id: 9,
      proveedorId: 1,
      archivo: 'lista.xlsx',
      fecha: '2026-10-12T10:00:00',
      conteos: { suben: 2, bajan: 1, nuevos: 1, sinCambio: 3, noEstan: 1, conError: 1 },
      estado: 'APLICADA',
    };
    // La muestra de prueba trae el encabezado en la fila 1: la primera fila de datos se propone en la 2.
    const mapeoEsperado = {
      hoja: 0,
      filaDesde: 2,
      columnaCodigo: 0,
      columnaDescripcion: null,
      columnaCosto: 2,
    };
    const datosBase = {
      proveedorId: 1,
      mapeo: mapeoEsperado,
      listaIncluyeIva: false,
      bonificacion1: null,
      bonificacion2: null,
      bonificacion3: null,
    };

    async function conVistaPrevia() {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await elegir('#proveedor', 'Sinteplast');
      await elegirArchivo(archivoActual);
      await elegir('#columnaCodigo', 'A');
      await elegir('#columnaCosto', 'C');
      q<HTMLButtonElement>('button.generar').click();
      await asentar();
    }

    async function confirmar() {
      q<HTMLButtonElement>('button.confirmar').click();
      await asentar();
    }

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

    it('"Crear los artículos nuevos" habilita el select de rubro, con la opción sin rubro', async () => {
      await conVistaPrevia();
      expect(q<HTMLInputElement>('#crearNuevos').checked).toBe(false);
      expect(q<HTMLSelectElement>('#rubro').disabled).toBe(true);

      q<HTMLInputElement>('#crearNuevos').click();
      await asentar();

      expect(q<HTMLSelectElement>('#rubro').disabled).toBe(false);
      expect(textosDe('#rubro')).toEqual(['Sin rubro (margen por defecto)', 'Pinturas']);
      expect(seleccionado('#rubro')).toBe('Sin rubro (margen por defecto)');
    });

    it('confirma con los conteos de la vista previa y sin crear artículos por defecto', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(of(importacion));

      await confirmar();

      expect(servicio.aplicar).toHaveBeenCalledWith(archivoActual, {
        ...datosBase,
        crearNuevos: false,
        rubroId: null,
        conteosEsperados: conteos,
      });
    });

    it('confirma creando los nuevos con el rubro elegido', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(of(importacion));
      q<HTMLInputElement>('#crearNuevos').click();
      await asentar();
      await elegir('#rubro', 'Pinturas');

      await confirmar();

      expect(servicio.aplicar).toHaveBeenCalledWith(archivoActual, {
        ...datosBase,
        crearNuevos: true,
        rubroId: 1,
        conteosEsperados: conteos,
      });
    });

    it('crear los nuevos sin rubro manda rubroId nulo', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(of(importacion));
      q<HTMLInputElement>('#crearNuevos').click();
      await asentar();

      await confirmar();

      expect(servicio.aplicar.mock.calls[0][1]).toMatchObject({ crearNuevos: true, rubroId: null });
    });

    it('si se destilda "crear nuevos" no manda el rubro que había elegido', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(of(importacion));
      q<HTMLInputElement>('#crearNuevos').click();
      await asentar();
      await elegir('#rubro', 'Pinturas');
      q<HTMLInputElement>('#crearNuevos').click();
      await asentar();

      await confirmar();

      expect(servicio.aplicar.mock.calls[0][1]).toMatchObject({ crearNuevos: false, rubroId: null });
    });

    it('con 201 muestra el resumen y retira la vista previa', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(of(importacion));

      await confirmar();

      const resumen = q('.resumen').textContent?.replace(/\s+/g, ' ') ?? '';
      expect(resumen).toContain('Importación aplicada');
      expect(resumen).toContain('lista.xlsx');
      expect(resumen).toContain('Suben 2');
      expect(resumen).toContain('Nuevos 1');
      expect(el().querySelector('.vista-previa')).toBeNull();
      expect(el().querySelector('button.confirmar')).toBeNull();
    });

    it('tras aplicar recarga los proveedores para traer lo que se guardó', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(of(importacion));
      proveedoresServicio.listar.mockClear();

      await confirmar();

      expect(proveedoresServicio.listar).toHaveBeenCalledTimes(1);
    });

    it('con 409 muestra el mensaje del backend, limpia la vista previa y ofrece regenerarla', async () => {
      await conVistaPrevia();
      const mensaje =
        'La lista cambió desde la vista previa. No se aplicó nada: volvé a generar la vista previa.';
      servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, mensaje)));

      await confirmar();

      expect(q('[role="alert"]').textContent).toContain(mensaje);
      expect(el().querySelector('.vista-previa')).toBeNull();
      expect(el().querySelector('button.confirmar')).toBeNull();
      expect(q('button.regenerar').textContent).toContain('Volver a generar la vista previa');
    });

    it('"Volver a generar la vista previa" la pide de nuevo con los mismos datos', async () => {
      await conVistaPrevia();
      servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, 'La lista cambió.')));
      await confirmar();
      servicio.vistaPrevia.mockClear();

      q<HTMLButtonElement>('button.regenerar').click();
      await asentar();

      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
      expect(servicio.vistaPrevia.mock.calls[0][1]).toEqual(datosBase);
      expect(el().querySelector('.vista-previa')).not.toBeNull();
      expect(el().querySelector('button.regenerar')).toBeNull();
      expect(el().querySelector('[role="alert"]')).toBeNull();
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
    const vistaPrevia: VistaPrevia = {
      conteos: { suben: 2, bajan: 1, nuevos: 1, sinCambio: 3, noEstan: 1, conError: 1 },
      cambios: [],
      nuevos: [],
      errores: [],
      advertencias: [],
      noEstan: [],
      detalleRecortado: false,
    };
    const importacion: Importacion = {
      id: 9,
      proveedorId: 1,
      archivo: 'lista.xlsx',
      fecha: '2026-10-12T10:00:00',
      conteos: vistaPrevia.conteos,
      estado: 'APLICADA',
    };
    const conMuestra = (filas: string[][]): Muestra => ({
      hojas: ['Lista'],
      hoja: 0,
      columnas: filas[0].length,
      filas: filas.map((celdas, i) => ({ numero: i + 1, celdas })),
    });

    async function conFilas(filas: string[][], proveedor = 'Sinteplast') {
      await crearComponente();
      servicio.muestra.mockReturnValue(of(conMuestra(filas)));
      await elegir('#proveedor', proveedor);
      await elegirArchivo(lista());
    }
    const filaDesde = () => q<HTMLInputElement>('#filaDesde').value;

    describe('fila de encabezado', () => {
      it('propone la fila siguiente al encabezado como primera fila de datos', async () => {
        await conFilas([['Codigo', 'Descripcion', 'Precio'], ['A-1', 'Tornillo', '1.234,56']]);

        expect(filaDesde()).toBe('2');
      });

      it('salta un título suelto antes del encabezado', async () => {
        await conFilas([
          ['Lista de precios Sinteplast', '', ''],
          ['Codigo', 'Descripcion', 'Precio'],
          ['A-1', 'Tornillo', '1234,56'],
        ]);

        expect(filaDesde()).toBe('3');
      });

      it('sin encabezado (la fila 1 ya tiene un precio) deja 1', async () => {
        await conFilas([['A-1', 'Tornillo', '1.234,56'], ['A-2', 'Tuerca', '500']]);

        expect(filaDesde()).toBe('1');
      });

      it('respeta el mapeo guardado del proveedor', async () => {
        await conFilas([['Codigo', 'Descripcion', 'Precio'], ['A-1', 'Tornillo', '1']], 'Tersuave');

        expect(filaDesde()).toBe('3');
      });

      it('si se elige el proveedor después del archivo, también la propone', async () => {
        await crearComponente();
        servicio.muestra.mockReturnValue(
          of(conMuestra([['Codigo', 'Descripcion', 'Precio'], ['A-1', 'Tornillo', '1']])),
        );
        await elegirArchivo(lista());
        await elegir('#proveedor', 'Sinteplast');

        expect(filaDesde()).toBe('2');
      });

      it('sigue siendo editable', async () => {
        await conFilas([['Codigo', 'Descripcion', 'Precio'], ['A-1', 'Tornillo', '1']]);
        await escribir('#filaDesde', '5');

        expect(filaDesde()).toBe('5');
      });
    });

    describe('columnas con el texto del encabezado', () => {
      it('las opciones dicen "A · Código"', async () => {
        await conFilas([['Código', 'Descripción', 'Precio'], ['A-1', 'Tornillo', '1']]);

        expect(textosDe('#columnaCodigo')).toEqual([
          'Elegí la columna',
          'A · Código',
          'B · Descripción',
          'C · Precio',
        ]);
        expect(textosDe('#columnaCosto')[3]).toBe('C · Precio');
        expect(textosDe('#columnaDescripcion')[2]).toBe('B · Descripción');
      });

      it('sin encabezado detectado quedan solo las letras', async () => {
        await conFilas([['A-1', 'Tornillo', '1.234,56']]);

        expect(textosDe('#columnaCodigo')).toEqual(['Elegí la columna', 'A', 'B', 'C']);
      });

      it('una celda de encabezado vacía queda solo con la letra', async () => {
        await conFilas([['Codigo', '', 'Precio'], ['A-1', 'x', '1']]);

        expect(textosDe('#columnaCodigo')).toEqual(['Elegí la columna', 'A · Codigo', 'B', 'C · Precio']);
      });
    });

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

        expect(servicio.muestra).toHaveBeenCalledWith(archivo, 0);
        expect(q('.nombre-archivo').textContent?.trim()).toBe('lista.xlsx');
      });

      it('un archivo de más de 10 MB soltado se rechaza con el aviso', async () => {
        await crearComponente();
        const grande = lista();
        Object.defineProperty(grande, 'size', { value: 10 * 1024 * 1024 + 1 });
        const soltar = new Event('drop', { bubbles: true, cancelable: true });
        Object.defineProperty(soltar, 'dataTransfer', { value: { files: [grande] } });

        q('.zona-archivo').dispatchEvent(soltar);
        await asentar();

        expect(q('[role="alert"]').textContent).toContain('El archivo supera el máximo de 10 MB.');
        expect(q('.nombre-archivo').textContent?.trim()).toBe('Ningún archivo elegido');
      });
    });

    describe('resultado de la importación', () => {
      async function importada() {
        await crearComponente();
        servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
        servicio.aplicar.mockReturnValue(of(importacion));
        await elegir('#proveedor', 'Sinteplast');
        await elegirArchivo(lista());
        await elegir('#columnaCodigo', 'A');
        await elegir('#columnaCosto', 'C');
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
    });
  });
});
