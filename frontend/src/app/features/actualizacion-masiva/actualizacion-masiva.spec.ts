import { registerLocaleData } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import localeEsAr from '@angular/common/locales/es-AR';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LOCALE_ID, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { Rubro } from '../rubros/rubro.models';
import { RubrosService } from '../rubros/rubros.service';
import { ActualizacionMasiva } from './actualizacion-masiva';
import {
  CambioMasivo,
  ConteosMasivos,
  ErrorMasivo,
  ResultadoMasivo,
  VistaPreviaMasiva,
} from './actualizacion-masiva.models';
import { ActualizacionMasivaService } from './actualizacion-masiva.service';

registerLocaleData(localeEsAr);

describe('ActualizacionMasiva', () => {
  const sinteplast: Proveedor = { id: 1, nombre: 'Sinteplast', cuit: null, activo: true };
  const tersuave: Proveedor = { id: 2, nombre: 'Tersuave', cuit: null, activo: true };
  const pinturas: Rubro = { id: 1, nombre: 'Pinturas', margenPorcentaje: 30 };
  const herramientas: Rubro = { id: 2, nombre: 'Herramientas', margenPorcentaje: 40 };

  const conteosBase: ConteosMasivos = {
    total: 5,
    suben: 2,
    bajan: 0,
    sinCambioDePrecio: 2,
    conError: 1,
    pasanAMargenPropio: 0,
    conAdvertencia: 0,
    modificados: 3,
  };
  const vistaPreviaBase: VistaPreviaMasiva = {
    conteos: conteosBase,
    cambios: [],
    errores: [],
    detalleRecortado: false,
    huella: 'huella-1',
  };

  let fixture: ComponentFixture<ActualizacionMasiva>;
  let servicio: { vistaPrevia: ReturnType<typeof vi.fn>; aplicar: ReturnType<typeof vi.fn> };
  let proveedoresServicio: { listar: ReturnType<typeof vi.fn> };
  let rubrosServicio: { listar: ReturnType<typeof vi.fn> };
  let router: Router;

  const errorHttp = (status: number, message: string) =>
    new HttpErrorResponse({ status, error: { status, message } });

  async function crearComponente(dueno = true) {
    servicio = { vistaPrevia: vi.fn(() => of(vistaPreviaBase)), aplicar: vi.fn() };
    proveedoresServicio = { listar: vi.fn(() => of([sinteplast, tersuave])) };
    rubrosServicio = { listar: vi.fn(() => of([pinturas, herramientas])) };
    await TestBed.configureTestingModule({
      imports: [ActualizacionMasiva],
      providers: [
        { provide: ActualizacionMasivaService, useValue: servicio },
        { provide: ProveedoresService, useValue: proveedoresServicio },
        { provide: RubrosService, useValue: rubrosServicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
        { provide: LOCALE_ID, useValue: 'es-AR' },
      ],
    }).compileComponents();
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
    vi.spyOn(router, 'navigateByUrl');
    fixture = TestBed.createComponent(ActualizacionMasiva);
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
    const opcion = Array.from(select.options).find((o) => o.textContent?.trim() === texto);
    expect(opcion, `${selector} → ${texto}`).toBeDefined();
    select.value = opcion!.value;
    select.dispatchEvent(new Event('change'));
    await asentar();
  }

  async function escribir(selector: string, valor: string) {
    const input = q<HTMLInputElement>(selector);
    input.value = valor;
    input.dispatchEvent(new Event('input'));
    await asentar();
  }

  const textosDe = (selector: string) =>
    Array.from(q<HTMLSelectElement>(selector).options).map((o) => o.textContent?.trim());
  const seleccionado = (selector: string) => {
    const select = q<HTMLSelectElement>(selector);
    return select.options[select.selectedIndex]?.textContent?.trim();
  };
  const texto = (selector: string) => q(selector).textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const boton = () => q<HTMLButtonElement>('button.generar');

  /** Completa modo y valor: lo mínimo para poder pedir la vista previa. */
  async function completar(modo = 'Cambiar el costo en %', valor = '10') {
    await elegir('#modo', modo);
    await escribir('#valor', valor);
  }

  async function generar() {
    boton().click();
    await asentar();
  }

  afterEach(() => vi.restoreAllMocks());

  describe('permisos (R-023)', () => {
    it('el Vendedor ve el aviso y ni el formulario ni el botón', async () => {
      await crearComponente(false);

      expect(el().textContent).toContain('Solo el Dueño puede actualizar precios.');
      expect(el().querySelector('#proveedor')).toBeNull();
      expect(el().querySelector('#modo')).toBeNull();
      expect(el().querySelector('#valor')).toBeNull();
      expect(el().querySelector('button.generar')).toBeNull();
    });

    it('el Vendedor no dispara ninguna llamada a proveedores, rubros ni actualización masiva', async () => {
      await crearComponente(false);

      expect(proveedoresServicio.listar).not.toHaveBeenCalled();
      expect(rubrosServicio.listar).not.toHaveBeenCalled();
      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
      expect(servicio.aplicar).not.toHaveBeenCalled();
    });

    it('el Vendedor no es redirigido: el aviso queda en pantalla', async () => {
      await crearComponente(false);

      expect(router.navigate).not.toHaveBeenCalled();
      expect(router.navigateByUrl).not.toHaveBeenCalled();
      expect(q('[role="status"]').textContent).toContain('Solo el Dueño puede actualizar precios.');
    });

    it('el Dueño no ve el aviso, ve el formulario y carga proveedores y rubros una vez', async () => {
      await crearComponente(true);

      expect(el().textContent).not.toContain('Solo el Dueño puede actualizar precios.');
      expect(el().querySelector('#proveedor')).not.toBeNull();
      expect(proveedoresServicio.listar).toHaveBeenCalledTimes(1);
      expect(rubrosServicio.listar).toHaveBeenCalledTimes(1);
    });
  });

  describe('formulario (R-024)', () => {
    it('los selectores traen "Todos los proveedores" y "Todos los rubros" primero, seguidos de los de la ferretería', async () => {
      await crearComponente();

      expect(textosDe('#proveedor')).toEqual(['Todos los proveedores', 'Sinteplast', 'Tersuave']);
      expect(textosDe('#rubro')).toEqual(['Todos los rubros', 'Pinturas', 'Herramientas']);
      expect(seleccionado('#proveedor')).toBe('Todos los proveedores');
      expect(seleccionado('#rubro')).toBe('Todos los rubros');
    });

    it('al cargar: texto vacío, "Incluir dados de baja" desmarcada y el botón deshabilitado', async () => {
      await crearComponente();

      expect(q('label[for="q"]').textContent).toContain('Buscar por código o descripción');
      expect(q<HTMLInputElement>('#q').value).toBe('');
      expect(q('label[for="incluirInactivos"]').textContent).toContain('Incluir dados de baja');
      expect(q<HTMLInputElement>('#incluirInactivos').checked).toBe(false);
      expect(boton().disabled).toBe(true);
      expect(boton().textContent).toContain('Ver vista previa');
    });

    it('el selector de modo ofrece las dos operaciones', async () => {
      await crearComponente();

      expect(textosDe('#modo')).toEqual([
        'Elegí qué querés hacer',
        'Cambiar el costo en %',
        'Fijar el margen',
      ]);
      expect(seleccionado('#modo')).toBe('Elegí qué querés hacer');
    });

    it('la etiqueta del valor sigue al modo: Porcentaje (%) o Margen (%)', async () => {
      await crearComponente();
      expect(q('label[for="valor"]').textContent?.trim()).toBe('Porcentaje (%)');

      await elegir('#modo', 'Fijar el margen');
      expect(q('label[for="valor"]').textContent?.trim()).toBe('Margen (%)');

      await elegir('#modo', 'Cambiar el costo en %');
      expect(q('label[for="valor"]').textContent?.trim()).toBe('Porcentaje (%)');
    });

    it('el botón sigue deshabilitado con solo el modo o con solo el valor', async () => {
      await crearComponente();

      await elegir('#modo', 'Cambiar el costo en %');
      expect(boton().disabled).toBe(true);

      await elegir('#modo', 'Elegí qué querés hacer');
      await escribir('#valor', '10');
      expect(boton().disabled).toBe(true);
    });

    it('con modo y valor numérico el botón se habilita', async () => {
      await crearComponente();

      await completar();

      expect(boton().disabled).toBe(false);
    });

    it.each(['abc', '', '   ', '1,2,3', '12,5%', '1.234,5', '0x10', 'Infinity'])(
      'un valor que no es un número (%j) deja el botón deshabilitado',
      async (valor) => {
        await crearComponente();

        await completar('Cambiar el costo en %', valor);

        expect(boton().disabled).toBe(true);
        generarSinHabilitar();
      },
    );

    function generarSinHabilitar() {
      boton().click();
      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
    }

    it('pide la vista previa con modo COSTO_PORCENTAJE y valor 10 y el filtro vacío', async () => {
      await crearComponente();
      await completar('Cambiar el costo en %', '10');

      await generar();

      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
      expect(servicio.vistaPrevia).toHaveBeenCalledWith({
        proveedorId: null,
        rubroId: null,
        q: null,
        incluirInactivos: false,
        modo: 'COSTO_PORCENTAJE',
        valor: 10,
      });
    });

    it('pide la vista previa de MARGEN con el valor 30', async () => {
      await crearComponente();
      await completar('Fijar el margen', '30');

      await generar();

      expect(servicio.vistaPrevia).toHaveBeenCalledWith(
        expect.objectContaining({ modo: 'MARGEN', valor: 30 }),
      );
    });

    it.each([
      ['12,5', 12.5],
      ['12.5', 12.5],
      ['-20', -20],
      ['  7  ', 7],
      ['0', 0],
      ['0,01', 0.01],
    ])('el valor %j se envía como el número %f', async (escrito, esperado) => {
      await crearComponente();
      await completar('Cambiar el costo en %', escrito);

      await generar();

      const pedido = servicio.vistaPrevia.mock.calls[0][0];
      expect(typeof pedido.valor).toBe('number');
      expect(pedido.valor).toBe(esperado);
    });

    it('envía el proveedor, el rubro, el texto sin espacios sobrantes y la casilla marcada', async () => {
      await crearComponente();
      await elegir('#proveedor', 'Tersuave');
      await elegir('#rubro', 'Herramientas');
      await escribir('#q', '  cable  ');
      q<HTMLInputElement>('#incluirInactivos').click();
      await asentar();
      await completar('Fijar el margen', '45');

      await generar();

      expect(servicio.vistaPrevia).toHaveBeenCalledWith({
        proveedorId: 2,
        rubroId: 2,
        q: 'cable',
        incluirInactivos: true,
        modo: 'MARGEN',
        valor: 45,
      });
    });

    it('un texto en blanco viaja como null', async () => {
      await crearComponente();
      await escribir('#q', '   ');
      await completar();

      await generar();

      expect(servicio.vistaPrevia.mock.calls[0][0].q).toBeNull();
    });

    it('mientras genera, el botón dice "Generando vista previa…" y está deshabilitado', async () => {
      await crearComponente();
      const respuesta = new Subject<VistaPreviaMasiva>();
      servicio.vistaPrevia.mockReturnValue(respuesta);
      await completar();

      await generar();

      expect(boton().textContent).toContain('Generando vista previa…');
      expect(boton().disabled).toBe(true);

      respuesta.next(vistaPreviaBase);
      respuesta.complete();
      await asentar();

      expect(boton().textContent).toContain('Ver vista previa');
      expect(boton().disabled).toBe(false);
    });

    it('un segundo clic mientras genera no manda otro pedido', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(new Subject<VistaPreviaMasiva>());
      await completar();

      await generar();
      boton().click();
      await asentar();

      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
    });
  });

  describe('errores de la vista previa en el formulario (R-024)', () => {
    it('un 400 muestra el mensaje del servidor tal cual, junto al formulario, que queda intacto', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(
        throwError(() => errorHttp(400, 'El porcentaje no puede ser cero.')),
      );
      await elegir('#proveedor', 'Sinteplast');
      await completar('Cambiar el costo en %', '0');

      await generar();

      expect(q('[role="alert"]').textContent).toContain('El porcentaje no puede ser cero.');
      expect(seleccionado('#proveedor')).toBe('Sinteplast');
      expect(seleccionado('#modo')).toBe('Cambiar el costo en %');
      expect(q<HTMLInputElement>('#valor').value).toBe('0');
      expect(boton().disabled).toBe(false);
    });

    it('un 400 con errores por campo muestra cada mensaje y no el genérico', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: {
                status: 400,
                message: 'Datos inválidos',
                errors: {
                  modo: 'Elegí qué querés hacer: cambiar el costo en porcentaje o fijar el margen.',
                  valor: 'Ingresá un valor.',
                },
              },
            }),
        ),
      );
      await completar();

      await generar();

      const alerta = q('[role="alert"]');
      expect(alerta.textContent).toContain('Ingresá un valor.');
      expect(alerta.textContent).toContain('Elegí qué querés hacer: cambiar');
      expect(alerta.textContent).not.toContain('Datos inválidos');
    });

    it('un nuevo pedido borra el error anterior', async () => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValueOnce(throwError(() => errorHttp(400, 'Falló antes.')));
      await completar();
      await generar();
      expect(q('[role="alert"]').textContent).toContain('Falló antes.');

      await generar();

      expect(el().querySelector('[role="alert"]')).toBeNull();
    });

    it('avisa si no se pudieron cargar los proveedores o los rubros', async () => {
      servicio = { vistaPrevia: vi.fn(), aplicar: vi.fn() };
      await TestBed.configureTestingModule({
        imports: [ActualizacionMasiva],
        providers: [
          { provide: ActualizacionMasivaService, useValue: servicio },
          {
            provide: ProveedoresService,
            useValue: { listar: () => throwError(() => errorHttp(500, 'Falló')) },
          },
          { provide: RubrosService, useValue: { listar: () => throwError(() => errorHttp(500, 'Falló')) } },
          { provide: AuthService, useValue: { esDueno: signal(true) } },
        ],
      }).compileComponents();
      fixture = TestBed.createComponent(ActualizacionMasiva);
      await asentar();

      const alertas = Array.from(el().querySelectorAll('[role="alert"]')).map((a) => a.textContent);
      expect(alertas.join(' ')).toContain('No se pudieron cargar los proveedores.');
      expect(alertas.join(' ')).toContain('No se pudieron cargar los rubros.');
    });
  });

  describe('vista previa (R-025)', () => {
    const cambio = (sobre: Partial<CambioMasivo> = {}): CambioMasivo => ({
      articuloId: 1,
      codigo: 'A1',
      descripcion: 'Tornillo 8mm',
      costoAnterior: 100,
      costoNuevo: 110,
      margenAnterior: 30,
      margenNuevo: 30,
      precioAnterior: 160,
      precioNuevo: 180,
      advertencia: null,
      ...sobre,
    });
    const errorDeFila: ErrorMasivo = {
      articuloId: 4,
      codigo: 'A4',
      descripcion: 'Arandela',
      motivo: 'El costo nuevo da 0. Revisá el porcentaje.',
    };
    const conteos = (sobre: Partial<ConteosMasivos> = {}): ConteosMasivos => ({
      ...conteosBase,
      ...sobre,
    });

    async function conVistaPrevia(vp: Partial<VistaPreviaMasiva>) {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of({ ...vistaPreviaBase, ...vp }));
      await completar();
      await generar();
    }

    const tarjetas = () =>
      Array.from(el().querySelectorAll('.tarjeta')).map((t) => t.textContent?.replace(/\s+/g, ' ').trim());
    const filasDeCambios = () => Array.from(el().querySelectorAll('table.cambios tbody tr'));
    const celdas = (fila: Element) =>
      Array.from(fila.querySelectorAll('td')).map((c) => c.textContent?.replace(/\s+/g, ' ').trim());

    it('antes de pedirla no hay vista previa ni tarjetas', async () => {
      await crearComponente();

      expect(el().querySelector('.vista-previa')).toBeNull();
      expect(tarjetas()).toEqual([]);
    });

    it('muestra las seis tarjetas con los contadores, en orden (escenario A1 a A5)', async () => {
      await conVistaPrevia({
        conteos: conteos(),
        cambios: [cambio()],
        errores: [errorDeFila],
      });

      expect(tarjetas()).toEqual([
        'Artículos 5',
        'Suben 2',
        'Bajan 0',
        'Sin cambio de precio 2',
        'Con error 1',
        'Con advertencia 0',
      ]);
    });

    it('otros contadores dan otras tarjetas, con formato argentino', async () => {
      await conVistaPrevia({
        conteos: conteos({
          total: 12345,
          suben: 10000,
          bajan: 2000,
          sinCambioDePrecio: 300,
          conError: 40,
          conAdvertencia: 5,
        }),
        cambios: [cambio()],
      });

      expect(tarjetas()).toEqual([
        'Artículos 12.345',
        'Suben 10.000',
        'Bajan 2.000',
        'Sin cambio de precio 300',
        'Con error 40',
        'Con advertencia 5',
      ]);
    });

    it('la tabla muestra código, descripción y costo, margen y precio como "anterior → nuevo"', async () => {
      await conVistaPrevia({ cambios: [cambio()] });

      const encabezados = Array.from(el().querySelectorAll('table.cambios thead th')).map((t) =>
        t.textContent?.trim(),
      );
      expect(encabezados).toEqual(['Código', 'Descripción', 'Costo', 'Margen (%)', 'Precio']);
      expect(filasDeCambios()).toHaveLength(1);
      expect(celdas(filasDeCambios()[0])).toEqual([
        'A1',
        'Tornillo 8mm',
        `$ 100,00 → $ 110,00`,
        '30 %',
        `$ 160,00 → $ 180,00`,
      ]);
    });

    it('cada artículo tiene su fila, con miles con punto y decimales con coma', async () => {
      await conVistaPrevia({
        cambios: [
          cambio(),
          cambio({
            articuloId: 2,
            codigo: 'A2',
            descripcion: 'Taladro',
            costoAnterior: 12345.6,
            costoNuevo: 13580.16,
            margenAnterior: 30,
            margenNuevo: 50,
            precioAnterior: 20000,
            precioNuevo: 25670,
          }),
        ],
      });

      expect(filasDeCambios()).toHaveLength(2);
      expect(celdas(filasDeCambios()[1])).toEqual([
        'A2',
        'Taladro',
        `$ 12.345,60 → $ 13.580,16`,
        '30 % → 50 %',
        `$ 20.000,00 → $ 25.670,00`,
      ]);
    });

    it('resalta la advertencia de la fila que la trae y solo de esa (MARGEN 300)', async () => {
      const advertencia = 'El precio sube un 206,25 % (de 160,00 a 490,00). Revisá que sea correcto.';
      await conVistaPrevia({
        conteos: conteos({ conAdvertencia: 1 }),
        cambios: [
          cambio({ precioNuevo: 490, margenNuevo: 300, advertencia }),
          cambio({ articuloId: 2, codigo: 'A2', descripcion: 'Tuerca' }),
        ],
      });

      const avisos = el().querySelectorAll('table.cambios .advertencia');
      expect(avisos).toHaveLength(1);
      expect(avisos[0].textContent?.trim()).toBe(advertencia);
      expect(filasDeCambios()[0].contains(avisos[0])).toBe(true);
      expect(tarjetas()).toContain('Con advertencia 1');
    });

    it('lista los artículos con error con su motivo bajo "Artículos que no se van a actualizar"', async () => {
      await conVistaPrevia({
        cambios: [cambio()],
        errores: [errorDeFila, { ...errorDeFila, articuloId: 9, codigo: 'Z9', motivo: 'Otro motivo.' }],
      });

      expect(texto('h3.titulo-errores')).toBe('Artículos que no se van a actualizar');
      const filas = Array.from(el().querySelectorAll('table.errores tbody tr')).map(celdas);
      expect(filas).toEqual([
        ['A4', 'Arandela', 'El costo nuevo da 0. Revisá el porcentaje.'],
        ['Z9', 'Arandela', 'Otro motivo.'],
      ]);
    });

    it('sin errores no aparece el título del listado de errores', async () => {
      await conVistaPrevia({ cambios: [cambio()], errores: [] });

      expect(el().textContent).not.toContain('Artículos que no se van a actualizar');
      expect(el().querySelector('table.errores')).toBeNull();
    });

    it('sin cambios en el detalle (todo con error) no hay tabla de cambios pero sí tarjetas', async () => {
      await conVistaPrevia({
        conteos: conteos({ total: 1, suben: 0, sinCambioDePrecio: 0, conError: 1, modificados: 0 }),
        cambios: [],
        errores: [errorDeFila],
      });

      expect(el().querySelector('table.cambios')).toBeNull();
      expect(tarjetas()).toContain('Con error 1');
      expect(el().querySelector('table.errores')).not.toBeNull();
    });

    it.each([
      [
        1,
        '1 artículo deja de heredar el margen (del rubro o de la ferretería) y pasa a tener margen propio.',
      ],
      [
        2,
        '2 artículos dejan de heredar el margen (del rubro o de la ferretería) y pasan a tener margen propio.',
      ],
      [
        1500,
        '1.500 artículos dejan de heredar el margen (del rubro o de la ferretería) y pasan a tener margen propio.',
      ],
    ])('avisa cuántos artículos pasan a margen propio (%i)', async (cuantos, aviso) => {
      await conVistaPrevia({
        conteos: conteos({ pasanAMargenPropio: cuantos }),
        cambios: [cambio()],
      });

      expect(texto('.pasan-a-margen-propio')).toBe(aviso);
    });

    it('con 0 artículos que pasan a margen propio no hay aviso', async () => {
      await conVistaPrevia({ conteos: conteos({ pasanAMargenPropio: 0 }), cambios: [cambio()] });

      expect(el().textContent).not.toContain('de heredar el margen');
      expect(el().textContent).not.toContain('dejan de heredar');
    });

    it('con detalleRecortado avisa que se muestran solo los primeros 1.000', async () => {
      await conVistaPrevia({ detalleRecortado: true, cambios: [cambio()] });

      expect(texto('.recortado')).toBe(
        'Se muestran solo los primeros 1.000 artículos. Los totales incluyen todos los que se van a modificar.',
      );
    });

    it('sin recorte no hay aviso de recorte', async () => {
      await conVistaPrevia({ detalleRecortado: false, cambios: [cambio()] });

      expect(el().textContent).not.toContain('Se muestran solo los primeros');
    });

    it('con total 0 dice "No hay artículos con ese filtro." y no muestra tarjetas, tabla ni confirmar', async () => {
      await conVistaPrevia({
        conteos: conteos({
          total: 0,
          suben: 0,
          sinCambioDePrecio: 0,
          conError: 0,
          modificados: 0,
        }),
        cambios: [],
        errores: [],
      });

      expect(texto('.sin-articulos')).toBe('No hay artículos con ese filtro.');
      expect(tarjetas()).toEqual([]);
      expect(el().querySelector('table.cambios')).toBeNull();
      expect(el().querySelector('button.confirmar')).toBeNull();
    });

    it('con total mayor que 0 no aparece el aviso de que no hay artículos', async () => {
      await conVistaPrevia({ cambios: [cambio()] });

      expect(el().textContent).not.toContain('No hay artículos con ese filtro.');
    });
  });

  describe('invalidación de la vista previa (R-026)', () => {
    const unCambio: CambioMasivo = {
      articuloId: 1,
      codigo: 'A1',
      descripcion: 'Tornillo',
      costoAnterior: 100,
      costoNuevo: 110,
      margenAnterior: 30,
      margenNuevo: 30,
      precioAnterior: 160,
      precioNuevo: 180,
      advertencia: null,
    };
    const conCambios: VistaPreviaMasiva = { ...vistaPreviaBase, cambios: [unCambio] };

    async function conVistaPrevia() {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(conCambios));
      await completar('Cambiar el costo en %', '10');
      await generar();
      expect(el().querySelector('.vista-previa'), 'hay vista previa antes del cambio').not.toBeNull();
    }

    const cambios: [string, () => Promise<void>][] = [
      ['el proveedor', () => elegir('#proveedor', 'Tersuave')],
      ['el rubro', () => elegir('#rubro', 'Herramientas')],
      ['el texto', () => escribir('#q', 'cable')],
      ['la casilla "Incluir dados de baja"', async () => q<HTMLInputElement>('#incluirInactivos').click()],
      ['el modo', () => elegir('#modo', 'Fijar el margen')],
      ['el valor (de 10 a 12)', () => escribir('#valor', '12')],
    ];

    it.each(cambios)('cambiar %s descarta la vista previa', async (_nombre, cambiar) => {
      await conVistaPrevia();

      await cambiar();
      await asentar();

      expect(el().querySelector('.vista-previa')).toBeNull();
      expect(el().querySelector('table.cambios')).toBeNull();
      expect(tarjetasVisibles()).toEqual([]);
    });

    const tarjetasVisibles = () => Array.from(el().querySelectorAll('.tarjeta'));

    it.each(cambios)('cambiar %s borra el mensaje de error', async (_nombre, cambiar) => {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(
        throwError(() => errorHttp(400, 'El porcentaje no puede ser cero.')),
      );
      await completar('Cambiar el costo en %', '10');
      await generar();
      expect(q('[role="alert"]').textContent).toContain('El porcentaje no puede ser cero.');

      await cambiar();
      await asentar();

      expect(el().querySelector('[role="alert"]')).toBeNull();
    });

    it('volver a pedir la vista previa quita la anterior mientras llega la nueva', async () => {
      await conVistaPrevia();
      const nueva = new Subject<VistaPreviaMasiva>();
      servicio.vistaPrevia.mockReturnValue(nueva);

      await generar();

      expect(el().querySelector('.vista-previa')).toBeNull();
      nueva.next({ ...conCambios, huella: 'nueva' });
      nueva.complete();
      await asentar();
      expect(el().querySelector('.vista-previa')).not.toBeNull();
    });

    it('una respuesta que llega después de cambiar el formulario se descarta', async () => {
      await crearComponente();
      const enVuelo = new Subject<VistaPreviaMasiva>();
      servicio.vistaPrevia.mockReturnValue(enVuelo);
      await completar('Cambiar el costo en %', '10');
      await generar();
      expect(boton().textContent).toContain('Generando vista previa…');

      await escribir('#valor', '12');
      enVuelo.next(conCambios);
      enVuelo.complete();
      await asentar();

      expect(el().querySelector('.vista-previa')).toBeNull();
      expect(boton().textContent).toContain('Ver vista previa');
      expect(boton().disabled).toBe(false);
    });

    it('un error que llega después de cambiar el formulario tampoco se muestra', async () => {
      await crearComponente();
      const enVuelo = new Subject<VistaPreviaMasiva>();
      servicio.vistaPrevia.mockReturnValue(enVuelo);
      await completar('Cambiar el costo en %', '10');
      await generar();

      await escribir('#valor', '12');
      enVuelo.error(errorHttp(400, 'El porcentaje no puede ser cero.'));
      await asentar();

      expect(el().querySelector('[role="alert"]')).toBeNull();
    });

    it('una respuesta vieja no pisa a la más nueva: solo vale la última vista previa pedida', async () => {
      await crearComponente();
      const vieja = new Subject<VistaPreviaMasiva>();
      const nueva = new Subject<VistaPreviaMasiva>();
      servicio.vistaPrevia.mockReturnValueOnce(vieja).mockReturnValueOnce(nueva);
      await completar('Cambiar el costo en %', '10');
      await generar();
      await escribir('#valor', '12');
      await generar();
      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(2);
      expect(servicio.vistaPrevia.mock.calls[1][0].valor).toBe(12);

      vieja.next({ ...conCambios, conteos: { ...conteosBase, total: 77 } });
      await asentar();

      expect(el().querySelector('.vista-previa'), 'la vieja se descarta').toBeNull();
      expect(boton().textContent, 'la nueva sigue en camino').toContain('Generando vista previa…');

      nueva.next({ ...conCambios, conteos: { ...conteosBase, total: 8 } });
      await asentar();

      expect(
        Array.from(el().querySelectorAll('.tarjeta')).map((t) => t.textContent?.replace(/\s+/g, ' ').trim()),
      ).toContain('Artículos 8');
      vieja.next({ ...conCambios, conteos: { ...conteosBase, total: 77 } });
      await asentar();
      expect(el().textContent).not.toContain('Artículos 77');
    });
  });

  describe('confirmación (R-027, R-028)', () => {
    const unCambio: CambioMasivo = {
      articuloId: 1,
      codigo: 'A1',
      descripcion: 'Tornillo',
      costoAnterior: 100,
      costoNuevo: 110,
      margenAnterior: 30,
      margenNuevo: 30,
      precioAnterior: 160,
      precioNuevo: 180,
      advertencia: null,
    };
    const vistaPrevia = (modificados: number, total = modificados): VistaPreviaMasiva => ({
      ...vistaPreviaBase,
      conteos: { ...conteosBase, total, modificados },
      cambios: [unCambio],
      huella: 'huella-1',
    });
    const resultado = (modificados: number): ResultadoMasivo => ({
      conteos: { ...conteosBase, modificados },
      entradasDeHistorial: modificados,
    });
    const pedidoConProveedor = {
      proveedorId: 1,
      rubroId: null,
      q: null,
      incluirInactivos: false,
      modo: 'COSTO_PORCENTAJE',
      valor: 10,
      huella: 'huella-1',
    };

    const botonConfirmar = () => q<HTMLButtonElement>('button.confirmar');
    const botonPorTexto = (etiqueta: string) =>
      Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.trim() === etiqueta);
    const hayConfirmacion = () => el().querySelector('.confirmacion-total') !== null;

    /** Filtro con proveedor por defecto (no vacío); `preparar` lo reemplaza para probar los demás filtros. */
    async function conVistaPrevia(
      vp: VistaPreviaMasiva,
      preparar: () => Promise<void> = () => elegir('#proveedor', 'Sinteplast'),
    ) {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vp));
      await preparar();
      await completar('Cambiar el costo en %', '10');
      await generar();
    }

    async function confirmar() {
      botonConfirmar().click();
      await asentar();
    }

    describe('botón "Actualizar N artículos"', () => {
      it.each([
        [3, 'Actualizar 3 artículos'],
        [1, 'Actualizar 1 artículo'],
        [1500, 'Actualizar 1.500 artículos'],
      ])('con modificados %i dice "%s"', async (modificados, etiqueta) => {
        await conVistaPrevia(vistaPrevia(modificados, 5000));

        expect(botonConfirmar().textContent?.trim()).toBe(etiqueta);
        expect(botonConfirmar().disabled).toBe(false);
      });

      it('N es `modificados` y no `total`', async () => {
        await conVistaPrevia(vistaPrevia(3, 5));

        expect(botonConfirmar().textContent?.trim()).toBe('Actualizar 3 artículos');
      });

      it('sin vista previa no hay botón de confirmar', async () => {
        await crearComponente();

        expect(el().querySelector('button.confirmar')).toBeNull();
      });
    });

    describe('con un filtro que no está vacío', () => {
      it('un clic aplica directamente, con la huella de la vista previa', async () => {
        await conVistaPrevia(vistaPrevia(3));
        servicio.aplicar.mockReturnValue(of(resultado(3)));

        await confirmar();

        expect(servicio.aplicar).toHaveBeenCalledTimes(1);
        expect(servicio.aplicar).toHaveBeenCalledWith(pedidoConProveedor);
        expect(hayConfirmacion()).toBe(false);
      });

      it('aplica con la huella de la vista previa vigente y no con una vieja', async () => {
        await conVistaPrevia({ ...vistaPrevia(3), huella: 'otra-huella' });
        servicio.aplicar.mockReturnValue(of(resultado(3)));

        await confirmar();

        expect(servicio.aplicar.mock.calls[0][0].huella).toBe('otra-huella');
      });

      it('aplica el pedido de la vista previa (proveedor, rubro, texto, casilla, MARGEN)', async () => {
        await crearComponente();
        servicio.vistaPrevia.mockReturnValue(of(vistaPrevia(3)));
        servicio.aplicar.mockReturnValue(of(resultado(3)));
        await elegir('#proveedor', 'Tersuave');
        await elegir('#rubro', 'Herramientas');
        await escribir('#q', ' cable ');
        q<HTMLInputElement>('#incluirInactivos').click();
        await asentar();
        await completar('Fijar el margen', '45,5');
        await generar();

        await confirmar();

        expect(servicio.aplicar).toHaveBeenCalledWith({
          proveedorId: 2,
          rubroId: 2,
          q: 'cable',
          incluirInactivos: true,
          modo: 'MARGEN',
          valor: 45.5,
          huella: 'huella-1',
        });
      });

      it.each([
        [3, 'Se actualizaron 3 artículos.'],
        [1, 'Se actualizó 1 artículo.'],
        [2500, 'Se actualizaron 2.500 artículos.'],
      ])('con éxito (modificados %i) se lee "%s" y se descarta la vista previa', async (n, mensaje) => {
        await conVistaPrevia(vistaPrevia(3));
        servicio.aplicar.mockReturnValue(of(resultado(n)));

        await confirmar();

        expect(texto('.resumen')).toBe(mensaje);
        expect(q('.resumen').getAttribute('role')).toBe('status');
        expect(el().querySelector('.vista-previa')).toBeNull();
        expect(el().querySelector('button.confirmar')).toBeNull();
      });

      it('el mensaje de éxito sale de la respuesta, no de la vista previa', async () => {
        await conVistaPrevia(vistaPrevia(3));
        servicio.aplicar.mockReturnValue(of(resultado(2)));

        await confirmar();

        expect(texto('.resumen')).toBe('Se actualizaron 2 artículos.');
      });

      it('cambiar el formulario después de aplicar borra el mensaje de éxito', async () => {
        await conVistaPrevia(vistaPrevia(3));
        servicio.aplicar.mockReturnValue(of(resultado(3)));
        await confirmar();
        expect(el().querySelector('.resumen')).not.toBeNull();

        await escribir('#valor', '12');

        expect(el().querySelector('.resumen')).toBeNull();
      });

      it('dos clics rápidos mandan una sola solicitud y el botón dice "Actualizando…"', async () => {
        await conVistaPrevia(vistaPrevia(3));
        const enVuelo = new Subject<ResultadoMasivo>();
        servicio.aplicar.mockReturnValue(enVuelo);

        botonConfirmar().click();
        botonConfirmar().click();
        await asentar();

        expect(servicio.aplicar).toHaveBeenCalledTimes(1);
        expect(botonConfirmar().textContent?.trim()).toBe('Actualizando…');
        expect(botonConfirmar().disabled).toBe(true);

        enVuelo.next(resultado(3));
        enVuelo.complete();
        await asentar();

        expect(texto('.resumen')).toBe('Se actualizaron 3 artículos.');
        expect(servicio.aplicar).toHaveBeenCalledTimes(1);
      });

      it('mientras aplica no se puede pedir otra vista previa', async () => {
        await conVistaPrevia(vistaPrevia(3));
        servicio.aplicar.mockReturnValue(new Subject<ResultadoMasivo>());
        servicio.vistaPrevia.mockClear();

        await confirmar();

        expect(boton().disabled).toBe(true);
        boton().click();
        expect(servicio.vistaPrevia).not.toHaveBeenCalled();
      });
    });

    describe('con modificados 0 y total mayor que 0', () => {
      it('el botón está deshabilitado, un clic no envía nada y se explica por qué', async () => {
        await conVistaPrevia(vistaPrevia(0, 2));

        expect(botonConfirmar().textContent?.trim()).toBe('Actualizar 0 artículos');
        expect(botonConfirmar().disabled).toBe(true);
        botonConfirmar().click();
        await asentar();
        expect(servicio.aplicar).not.toHaveBeenCalled();
        expect(hayConfirmacion()).toBe(false);
        expect(texto('.sin-cambios')).toBe('Con esta operación no cambia ningún artículo.');
      });

      it('tampoco envía nada con el filtro vacío: no abre la segunda confirmación', async () => {
        await conVistaPrevia(vistaPrevia(0, 2), async () => undefined);

        botonConfirmar().click();
        await asentar();

        expect(servicio.aplicar).not.toHaveBeenCalled();
        expect(hayConfirmacion()).toBe(false);
      });

      it('aunque se lo invoque sin pasar por el botón deshabilitado, no envía nada', async () => {
        await conVistaPrevia(vistaPrevia(0, 2));
        const componente = fixture.componentInstance as unknown as { confirmar(): void };

        componente.confirmar();
        await asentar();

        expect(servicio.aplicar).not.toHaveBeenCalled();
        expect(hayConfirmacion()).toBe(false);
      });

      it('con modificados mayor que 0 no aparece esa explicación', async () => {
        await conVistaPrevia(vistaPrevia(3, 5));

        expect(el().textContent).not.toContain('Con esta operación no cambia ningún artículo.');
      });
    });

    describe('con el filtro vacío: segunda confirmación', () => {
      const sinFiltro = async () => undefined;

      it('un clic abre "Vas a modificar N artículos" y todavía no aplica', async () => {
        await conVistaPrevia(vistaPrevia(120, 130), sinFiltro);

        await confirmar();

        expect(servicio.aplicar).not.toHaveBeenCalled();
        expect(hayConfirmacion()).toBe(true);
        expect(texto('.confirmacion-total h3')).toBe('Vas a modificar 120 artículos');
        expect(texto('.confirmacion-total p')).toBe(
          'No elegiste ningún filtro: el cambio alcanza a todo el catálogo. Revisá la vista previa antes de seguir.',
        );
        expect(botonPorTexto('Sí, modificar todo')).toBeDefined();
        expect(botonPorTexto('Cancelar')).toBeDefined();
      });

      it('con un solo artículo dice "Vas a modificar 1 artículo"', async () => {
        await conVistaPrevia(vistaPrevia(1, 4), sinFiltro);

        await confirmar();

        expect(texto('.confirmacion-total h3')).toBe('Vas a modificar 1 artículo');
      });

      it('"Sí, modificar todo" aplica una sola vez y muestra "Se actualizaron 120 artículos."', async () => {
        await conVistaPrevia(vistaPrevia(120), sinFiltro);
        servicio.aplicar.mockReturnValue(of(resultado(120)));
        await confirmar();

        botonPorTexto('Sí, modificar todo')!.click();
        await asentar();

        expect(servicio.aplicar).toHaveBeenCalledTimes(1);
        expect(servicio.aplicar).toHaveBeenCalledWith({
          proveedorId: null,
          rubroId: null,
          q: null,
          incluirInactivos: false,
          modo: 'COSTO_PORCENTAJE',
          valor: 10,
          huella: 'huella-1',
        });
        expect(texto('.resumen')).toBe('Se actualizaron 120 artículos.');
        expect(hayConfirmacion()).toBe(false);
      });

      it('dos clics rápidos en "Sí, modificar todo" mandan una sola solicitud', async () => {
        await conVistaPrevia(vistaPrevia(120), sinFiltro);
        servicio.aplicar.mockReturnValue(new Subject<ResultadoMasivo>());
        await confirmar();
        const si = botonPorTexto('Sí, modificar todo')!;

        si.click();
        si.click();
        await asentar();

        expect(servicio.aplicar).toHaveBeenCalledTimes(1);
        expect(botonConfirmar().textContent?.trim()).toBe('Actualizando…');
        expect(hayConfirmacion()).toBe(false);
      });

      it('"Cancelar" cierra la confirmación sin aplicar y conserva la vista previa', async () => {
        await conVistaPrevia(vistaPrevia(120), sinFiltro);
        await confirmar();

        botonPorTexto('Cancelar')!.click();
        await asentar();

        expect(servicio.aplicar).not.toHaveBeenCalled();
        expect(hayConfirmacion()).toBe(false);
        expect(el().querySelector('.vista-previa')).not.toBeNull();
        expect(botonConfirmar().textContent?.trim()).toBe('Actualizar 120 artículos');
      });

      it('después de cancelar, un nuevo clic vuelve a pedir la confirmación', async () => {
        await conVistaPrevia(vistaPrevia(120), sinFiltro);
        await confirmar();
        botonPorTexto('Cancelar')!.click();
        await asentar();

        await confirmar();

        expect(hayConfirmacion()).toBe(true);
        expect(servicio.aplicar).not.toHaveBeenCalled();
      });

      it('cambiar el rubro o el valor con la confirmación abierta la cierra y descarta la vista previa', async () => {
        for (const cambiar of [() => elegir('#rubro', 'Pinturas'), () => escribir('#valor', '12')]) {
          TestBed.resetTestingModule();
          await conVistaPrevia(vistaPrevia(120), sinFiltro);
          await confirmar();
          expect(hayConfirmacion()).toBe(true);

          await cambiar();

          expect(hayConfirmacion()).toBe(false);
          expect(el().querySelector('.vista-previa')).toBeNull();
          expect(servicio.aplicar).not.toHaveBeenCalled();
        }
      });
    });

    describe('qué cuenta como filtro vacío', () => {
      const filtros: [string, () => Promise<void>, boolean][] = [
        ['ningún filtro', async () => undefined, true],
        [
          'solo "Incluir dados de baja" marcada',
          async () => {
            q<HTMLInputElement>('#incluirInactivos').click();
            await asentar();
          },
          true,
        ],
        ['solo texto en blanco ("   ")', () => escribir('#q', '   '), true],
        ['solo el texto "cable"', () => escribir('#q', 'cable'), false],
        ['solo el proveedor', () => elegir('#proveedor', 'Sinteplast'), false],
        ['solo el rubro', () => elegir('#rubro', 'Pinturas'), false],
      ];

      it.each(filtros)('%s', async (_nombre, preparar, pideConfirmacion) => {
        await conVistaPrevia(vistaPrevia(3), preparar);
        servicio.aplicar.mockReturnValue(of(resultado(3)));

        await confirmar();

        expect(hayConfirmacion()).toBe(pideConfirmacion);
        expect(servicio.aplicar).toHaveBeenCalledTimes(pideConfirmacion ? 0 : 1);
      });
    });
  });

  describe('errores (R-029)', () => {
    const MENSAJE_409 =
      'Los artículos cambiaron desde la vista previa. Volvé a generar la vista previa y confirmá de nuevo.';
    const MENSAJE_GENERICO = 'No se pudo completar la operación. Probá de nuevo.';

    const vistaPrevia: VistaPreviaMasiva = {
      ...vistaPreviaBase,
      cambios: [
        {
          articuloId: 1,
          codigo: 'A1',
          descripcion: 'Tornillo',
          costoAnterior: 100,
          costoNuevo: 110,
          margenAnterior: 30,
          margenNuevo: 30,
          precioAnterior: 160,
          precioNuevo: 180,
          advertencia: null,
        },
      ],
    };
    const botonConfirmar = () => q<HTMLButtonElement>('button.confirmar');
    const alerta = () => q('[role="alert"]');

    async function conVistaPrevia() {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
      await elegir('#proveedor', 'Sinteplast');
      await completar('Cambiar el costo en %', '10');
      await generar();
      expect(botonConfirmar().textContent?.trim()).toBe('Actualizar 3 artículos');
    }

    async function aplicarConError(err: unknown) {
      servicio.aplicar.mockReturnValue(throwError(() => err));
      botonConfirmar().click();
      await asentar();
    }

    const formularioIntacto = () => {
      expect(seleccionado('#proveedor')).toBe('Sinteplast');
      expect(seleccionado('#modo')).toBe('Cambiar el costo en %');
      expect(q<HTMLInputElement>('#valor').value).toBe('10');
    };

    describe('al aplicar', () => {
      it('un 409 descarta la vista previa y dice que hay que generarla de nuevo', async () => {
        await conVistaPrevia();

        await aplicarConError(errorHttp(409, MENSAJE_409));

        expect(alerta().textContent).toContain(MENSAJE_409);
        expect(el().querySelector('.vista-previa')).toBeNull();
        expect(el().querySelector('button.confirmar')).toBeNull();
        expect(el().querySelector('.resumen')).toBeNull();
        formularioIntacto();
      });

      it('un 409 sin cuerpo igual dice lo mismo', async () => {
        await conVistaPrevia();

        await aplicarConError(new HttpErrorResponse({ status: 409 }));

        expect(alerta().textContent).toContain(MENSAJE_409);
      });

      it('después de un 409, una nueva vista previa vuelve a ofrecer "Actualizar N artículos"', async () => {
        await conVistaPrevia();
        await aplicarConError(errorHttp(409, MENSAJE_409));
        expect(el().querySelector('button.confirmar')).toBeNull();

        await generar();

        expect(botonConfirmar().textContent?.trim()).toBe('Actualizar 3 artículos');
        expect(el().querySelector('[role="alert"]')).toBeNull();
      });

      it.each([
        'El filtro abarca 20.001 artículos y el máximo es 20.000. Afiná el filtro.',
        'No hay artículos para actualizar con ese filtro.',
        'El porcentaje no puede ser cero.',
      ])('un 400 muestra el mensaje del servidor "%s" y conserva el formulario', async (mensaje) => {
        await conVistaPrevia();

        await aplicarConError(errorHttp(400, mensaje));

        expect(alerta().textContent).toContain(mensaje);
        expect(el().querySelector('button.confirmar')).toBeNull();
        formularioIntacto();
        expect(boton().disabled).toBe(false);
      });

      it('un 400 con errores por campo muestra cada mensaje', async () => {
        await conVistaPrevia();

        await aplicarConError(
          new HttpErrorResponse({
            status: 400,
            error: {
              status: 400,
              message: 'Datos inválidos',
              errors: { huella: 'Falta la vista previa. Generala de nuevo antes de confirmar.' },
            },
          }),
        );

        expect(alerta().textContent).toContain('Falta la vista previa. Generala de nuevo antes de confirmar.');
        expect(alerta().textContent).not.toContain('Datos inválidos');
      });

      it('un 404 muestra el mensaje, descarta la vista previa y recarga los dos selectores', async () => {
        await conVistaPrevia();
        proveedoresServicio.listar.mockClear();
        rubrosServicio.listar.mockClear();
        proveedoresServicio.listar.mockReturnValue(of([sinteplast, tersuave]));

        await aplicarConError(errorHttp(404, 'Proveedor no encontrado'));

        expect(alerta().textContent).toContain('Proveedor no encontrado');
        expect(el().querySelector('button.confirmar')).toBeNull();
        expect(proveedoresServicio.listar).toHaveBeenCalledTimes(1);
        expect(rubrosServicio.listar).toHaveBeenCalledTimes(1);
      });

      it('tras el 404 el selector muestra la lista recargada y suelta el proveedor que ya no existe', async () => {
        await conVistaPrevia();
        proveedoresServicio.listar.mockReturnValue(of([tersuave]));

        await aplicarConError(errorHttp(404, 'Proveedor no encontrado'));

        expect(textosDe('#proveedor')).toEqual(['Todos los proveedores', 'Tersuave']);
        expect(seleccionado('#proveedor')).toBe('Todos los proveedores');
        expect(alerta().textContent).toContain('Proveedor no encontrado');
      });

      it('un 403 muestra el mensaje del servidor', async () => {
        await conVistaPrevia();

        await aplicarConError(errorHttp(403, 'No tenés permiso para hacer esto'));

        expect(alerta().textContent).toContain('No tenés permiso para hacer esto');
        expect(el().querySelector('button.confirmar')).toBeNull();
      });

      it.each([
        ['500 con mensaje del servidor', errorHttp(500, 'Error interno del servidor')],
        ['502 sin cuerpo', new HttpErrorResponse({ status: 502 })],
        ['sin conexión (status 0)', new HttpErrorResponse({ status: 0 })],
        ['un error que no es HTTP', new Error('caído')],
      ])('%s dice "No se pudo completar la operación. Probá de nuevo." sin tocar el formulario', async (_n, err) => {
        await conVistaPrevia();

        await aplicarConError(err);

        expect(alerta().textContent).toContain(MENSAJE_GENERICO);
        expect(alerta().textContent).not.toContain('Error interno del servidor');
        expect(el().querySelector('button.confirmar')).toBeNull();
        formularioIntacto();
      });

      it('un error de aplicar deja de mostrar "Actualizando…" y se puede pedir otra vista previa', async () => {
        await conVistaPrevia();
        await aplicarConError(errorHttp(500, 'x'));

        expect(boton().disabled).toBe(false);
        expect(boton().textContent).toContain('Ver vista previa');
      });

      it('un error después de la segunda confirmación cierra la confirmación y descarta la vista previa', async () => {
        await crearComponente();
        servicio.vistaPrevia.mockReturnValue(of(vistaPrevia));
        await completar('Cambiar el costo en %', '10');
        await generar();
        botonConfirmar().click();
        await asentar();
        expect(el().querySelector('.confirmacion-total')).not.toBeNull();
        servicio.aplicar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE_409)));

        q<HTMLButtonElement>('.confirmar-todo').click();
        await asentar();

        expect(alerta().textContent).toContain(MENSAJE_409);
        expect(el().querySelector('.confirmacion-total')).toBeNull();
        expect(el().querySelector('.vista-previa')).toBeNull();
        expect(servicio.aplicar).toHaveBeenCalledTimes(1);
      });

      it('un error nuevo reemplaza al anterior y el éxito posterior lo borra', async () => {
        await conVistaPrevia();
        await aplicarConError(errorHttp(409, MENSAJE_409));
        await generar();
        servicio.aplicar.mockReturnValue(of({ conteos: vistaPrevia.conteos, entradasDeHistorial: 3 }));

        botonConfirmar().click();
        await asentar();

        expect(el().querySelector('[role="alert"]')).toBeNull();
        expect(texto('.resumen')).toBe('Se actualizaron 3 artículos.');
      });
    });

    describe('al generar la vista previa', () => {
      it('un 400 con el tope muestra el mensaje y no hay vista previa', async () => {
        await crearComponente();
        const tope = 'El filtro abarca 20.001 artículos y el máximo es 20.000. Afiná el filtro.';
        servicio.vistaPrevia.mockReturnValue(throwError(() => errorHttp(400, tope)));
        await completar();

        await generar();

        expect(alerta().textContent).toContain(tope);
        expect(el().querySelector('.vista-previa')).toBeNull();
        expect(el().querySelector('button.confirmar')).toBeNull();
      });

      it.each([
        ['500', errorHttp(500, 'Error interno del servidor')],
        ['un error que no es HTTP', new Error('caído')],
      ])('%s dice "No se pudo completar la operación. Probá de nuevo."', async (_n, err) => {
        await crearComponente();
        servicio.vistaPrevia.mockReturnValue(throwError(() => err));
        await elegir('#rubro', 'Pinturas');
        await completar('Fijar el margen', '35');

        await generar();

        expect(alerta().textContent).toContain(MENSAJE_GENERICO);
        expect(alerta().textContent).not.toContain('Error interno del servidor');
        expect(seleccionado('#rubro')).toBe('Pinturas');
        expect(q<HTMLInputElement>('#valor').value).toBe('35');
        expect(boton().disabled).toBe(false);
      });

      it('un 404 de rubro muestra el mensaje y recarga los selectores', async () => {
        await crearComponente();
        servicio.vistaPrevia.mockReturnValue(throwError(() => errorHttp(404, 'Rubro no encontrado')));
        rubrosServicio.listar.mockReturnValue(of([herramientas]));
        proveedoresServicio.listar.mockClear();
        rubrosServicio.listar.mockClear();
        await elegir('#rubro', 'Pinturas');
        await completar();

        await generar();

        expect(alerta().textContent).toContain('Rubro no encontrado');
        expect(proveedoresServicio.listar).toHaveBeenCalledTimes(1);
        expect(rubrosServicio.listar).toHaveBeenCalledTimes(1);
        expect(textosDe('#rubro')).toEqual(['Todos los rubros', 'Herramientas']);
        expect(seleccionado('#rubro')).toBe('Todos los rubros');
      });

      it('un 403 muestra el mensaje del servidor', async () => {
        await crearComponente();
        servicio.vistaPrevia.mockReturnValue(
          throwError(() => errorHttp(403, 'No tenés permiso para hacer esto')),
        );
        await completar();

        await generar();

        expect(alerta().textContent).toContain('No tenés permiso para hacer esto');
      });

      it('si el 400 no trae mensaje usa el genérico', async () => {
        await crearComponente();
        servicio.vistaPrevia.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 400 })));
        await completar();

        await generar();

        expect(alerta().textContent).toContain(MENSAJE_GENERICO);
      });
    });
  });

  describe('pulido de la pantalla (H-08, H-15, H-19)', () => {
    const cambioBase: CambioMasivo = {
      articuloId: 1,
      codigo: 'A1',
      descripcion: 'Tornillo',
      costoAnterior: 100,
      costoNuevo: 110,
      margenAnterior: 30,
      margenNuevo: 30,
      precioAnterior: 160,
      precioNuevo: 180,
      advertencia: null,
    };
    const resultado: ResultadoMasivo = {
      conteos: { ...conteosBase, modificados: 3 },
      entradasDeHistorial: 3,
    };
    const despues = (a: Element, b: Element) =>
      Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
    const celdasDe = (fila: Element) =>
      Array.from(fila.querySelectorAll('td')).map((c) => c.textContent?.replace(/\s+/g, ' ').trim());

    async function aplicado() {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(of({ ...vistaPreviaBase, cambios: [cambioBase] }));
      servicio.aplicar.mockReturnValue(of(resultado));
      await elegir('#proveedor', 'Sinteplast');
      await completar('Cambiar el costo en %', '10');
      await generar();
      q<HTMLButtonElement>('button.confirmar').click();
      await asentar();
    }

    it('al confirmar limpia la operación y el porcentaje, pero conserva el filtro', async () => {
      await aplicado();

      expect(seleccionado('#modo')).toBe('Elegí qué querés hacer');
      expect(q<HTMLInputElement>('#valor').value).toBe('');
      expect(seleccionado('#proveedor')).toBe('Sinteplast');
      expect(boton().disabled).toBe(true);
      expect(texto('.resumen')).toBe('Se actualizaron 3 artículos.');
    });

    it('el resultado recibe el foco para que se vea', async () => {
      await aplicado();

      expect(document.activeElement).toBe(q('.resumen'));
    });

    it('los avisos van debajo del botón, para que no muevan el formulario', async () => {
      await aplicado();
      expect(despues(boton(), q('.resumen'))).toBe(true);

      servicio.vistaPrevia.mockReturnValue(throwError(() => errorHttp(400, 'Mal pedido')));
      await completar('Cambiar el costo en %', '10');
      await generar();
      expect(despues(boton(), q('[role="alert"]'))).toBe(true);
    });

    it('Enter en el porcentaje genera la vista previa', async () => {
      await crearComponente();
      await completar('Cambiar el costo en %', '10');

      q('#valor').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await asentar();

      expect(servicio.vistaPrevia).toHaveBeenCalledTimes(1);
    });

    it('Enter con el valor incompleto no genera nada', async () => {
      await crearComponente();
      await elegir('#modo', 'Cambiar el costo en %');

      q('#valor').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await asentar();

      expect(servicio.vistaPrevia).not.toHaveBeenCalled();
    });

    it.each([
      ['Cambiar el costo en %', '1001', 'El porcentaje tiene que estar entre -99,99 y 1.000.'],
      ['Cambiar el costo en %', '-100', 'El porcentaje tiene que estar entre -99,99 y 1.000.'],
      ['Fijar el margen', '1001', 'El margen tiene que estar entre 0 y 1.000.'],
      ['Fijar el margen', '-1', 'El margen tiene que estar entre 0 y 1.000.'],
    ])('%s con %s muestra el error junto al campo y no deja generar', async (modo, valor, mensaje) => {
      await crearComponente();
      await completar(modo, valor);

      expect(texto('.campo:has(#valor) .campo-error')).toBe(mensaje);
      expect(boton().disabled).toBe(true);
    });

    it('un valor válido no muestra error en el campo', async () => {
      await crearComponente();
      await completar('Cambiar el costo en %', '-99,99');

      expect(el().querySelector('.campo:has(#valor) .campo-error')).toBeNull();
      expect(boton().disabled).toBe(false);
    });

    async function conCambio(sobre: Partial<CambioMasivo>) {
      await crearComponente();
      servicio.vistaPrevia.mockReturnValue(
        of({ ...vistaPreviaBase, cambios: [{ ...cambioBase, ...sobre }] }),
      );
      await completar();
      await generar();
      return celdasDe(el().querySelector('table.cambios tbody tr')!);
    }

    it('el costo que no cambia se muestra sin flecha', async () => {
      const c = await conCambio({ costoAnterior: 119.35, costoNuevo: 119.35, margenNuevo: 35 });

      expect(c[2]).toBe('$ 119,35');
    });

    it('el margen se muestra con % y sin flecha cuando no cambia', async () => {
      const c = await conCambio({ margenAnterior: 45.5, margenNuevo: 45.5 });

      expect(c[3]).toBe('45,5 %');
    });

    it('el margen heredado se rotula como tal', async () => {
      const c = await conCambio({ margenAnterior: 35, margenNuevo: 35, margenHeredado: true });

      expect(c[3]).toBe('Heredado 35 %');
    });
  });
});
