import { registerLocaleData } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { By } from '@angular/platform-browser';
import { AuthService } from '../../core/auth/auth.service';
import { Avisos } from '../../core/avisos/avisos';
import { Cotizacion } from '../../core/cotizacion/cotizacion.models';
import { CotizacionService } from '../../core/cotizacion/cotizacion.service';
import { DialogoAjusteStock } from './dialogo-ajuste-stock';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { Rubro } from '../rubros/rubro.models';
import { RubrosService } from '../rubros/rubros.service';
import { Articulo, DesglosePrecio } from './articulo.models';
import { ArticuloForm } from './articulo-form';
import { ArticulosService } from './articulos.service';

registerLocaleData(localeEsAr);

const NBSP = '\u00a0';
/** Vuelve al listado con la b\u00fasqueda, la p\u00e1gina y los inactivos que tra\u00eda la direcci\u00f3n. */
const VOLVER_AL_LISTADO = { queryParamsHandling: 'preserve' };

describe('ArticuloForm', () => {
  const rubros: Rubro[] = [
    { id: 1, nombre: 'Bulonería', margenPorcentaje: 35 },
    { id: 2, nombre: 'Herramientas', margenPorcentaje: 40 },
  ];
  const proveedores: Proveedor[] = [
    { id: 10, nombre: 'Sinteplast', cuit: null, activo: true },
    { id: 11, nombre: 'Tersuave', cuit: null, activo: true },
    { id: 12, nombre: 'Proveedor viejo', cuit: null, activo: false },
  ];

  const articuloGuardado = (cambios: Partial<Articulo> = {}): Articulo => ({
    id: 5,
    codigoInterno: 'TOR-001',
    codigoBarras: '7790001000012',
    codigoProveedor: 'AB-12',
    descripcion: 'Tornillo hexagonal 1/4',
    rubroId: 1,
    proveedorId: 10,
    costo: 100,
    moneda: 'ARS',
    margenPorcentaje: 35,
    alicuotaIva: 'IVA_10_5',
    precioFinal: 1234.5,
    unidad: 'METRO',
    stockActual: 12.5,
    stockMinimo: 3,
    ubicacion: 'Pasillo 2',
    activo: true,
    ...cambios,
  });

  /** Cotización activada con el dólar de los precios a 1.000 (el vigente del backend). */
  const cotizacionActiva = (cambios: Partial<Cotizacion> = {}, valor: number | null = 1000): Cotizacion => ({
    activa: true,
    desactualizada: false,
    ultimaRespuesta: '2026-10-16T12:30:00Z',
    visibles: [],
    precios: { codigo: 'USD:oficial', lado: 'VENTA', valor, desde: '2026-10-16T11:00:00Z' },
    ...cambios,
  });

  /** El artículo del ejemplo de R-209: lista 1.000,00, bonificación 10, ajustes -5 y +3, margen 30, IVA 21, múltiplo 10. */
  const articuloConLista = (cambios: Partial<Articulo> = {}): Articulo =>
    articuloGuardado({
      costo: 880.65,
      margenPorcentaje: 30,
      alicuotaIva: 'IVA_21',
      precioFinal: 1390,
      precioListaProveedor: 1000,
      ajuste1: -5,
      ajuste2: 3,
      ajuste3: null,
      bonificacion1: 10,
      bonificacion2: null,
      bonificacion3: null,
      listaIncluyeIva: false,
      ...cambios,
    });

  const desgloseDe = (cambios: Partial<DesglosePrecio> = {}): DesglosePrecio => ({
    articuloId: 5,
    tieneLista: true,
    motivoSinLista: null,
    moneda: 'ARS',
    precioLista: 1000,
    listaIncluyeIva: false,
    alicuotaIva: 'IVA_21',
    bonificacion1: 10,
    bonificacion2: null,
    bonificacion3: null,
    ajuste1: -5,
    ajuste2: 3,
    ajuste3: null,
    pasos: [],
    costoDeLista: 880.65,
    costoGuardado: 880.65,
    coincide: true,
    margen: 30,
    origenMargen: 'PROPIO',
    cotizacion: null,
    cotizacionCongelada: false,
    multiplo: 10,
    precioFinal: 1390,
    ...cambios,
  });

  /** Sin lista de proveedor: lo que manda el servidor con el texto único de la decisión 46. */
  const MOTIVO_SIN_LISTA =
    'Este artículo no tiene lista de proveedor. Los ajustes se usan solo con artículos que vienen de una lista.';
  const desgloseSinLista = (cambios: Partial<DesglosePrecio> = {}): DesglosePrecio =>
    desgloseDe({
      tieneLista: false,
      motivoSinLista: MOTIVO_SIN_LISTA,
      precioLista: null,
      listaIncluyeIva: null,
      bonificacion1: null,
      ajuste1: null,
      ajuste2: null,
      costoDeLista: null,
      coincide: null,
      ...cambios,
    });

  let fixture: ComponentFixture<ArticuloForm>;
  let servicio: {
    obtener: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    actualizar: ReturnType<typeof vi.fn>;
    reactivar: ReturnType<typeof vi.fn>;
    ajustarStock: ReturnType<typeof vi.fn>;
    desglose: ReturnType<typeof vi.fn>;
    guardarAjustes: ReturnType<typeof vi.fn>;
    configuracionPrecios: ReturnType<typeof vi.fn>;
  };
  let navigate: ReturnType<typeof vi.spyOn>;

  const errorHttp = (status: number, message: string) =>
    new HttpErrorResponse({ status, error: { status, message } });

  async function crearComponente(
    opciones: {
      dueno?: boolean;
      id?: string | null;
      articulo?: Articulo | HttpErrorResponse;
      /** Lo que responde GET /articulos/{id}/desglose; por defecto, un artículo sin lista. */
      desglose?: DesglosePrecio | HttpErrorResponse;
      sinConfiguracion?: boolean;
      /** Lo que responde GET /cotizacion; 'falla' si no se puede leer. */
      cotizacion?: Cotizacion | 'falla';
    } = {},
  ) {
    const {
      dueno = true,
      id = null,
      articulo = articuloGuardado(),
      desglose = desgloseSinLista(),
      sinConfiguracion = false,
      cotizacion = cotizacionActiva(),
    } = opciones;
    servicio = {
      obtener: vi.fn(() =>
        articulo instanceof HttpErrorResponse ? throwError(() => articulo) : of(articulo),
      ),
      crear: vi.fn(() => of(articuloGuardado())),
      actualizar: vi.fn(() => of(articuloGuardado())),
      reactivar: vi.fn(() => of(articuloGuardado({ activo: true }))),
      ajustarStock: vi.fn(),
      desglose: vi.fn(() =>
        desglose instanceof HttpErrorResponse ? throwError(() => desglose) : of(desglose),
      ),
      guardarAjustes: vi.fn(() => of(articuloConLista())),
      configuracionPrecios: vi.fn(() =>
        sinConfiguracion
          ? throwError(() => errorHttp(500, 'Error interno'))
          : of({ margenDefectoPorcentaje: 30, redondeoMultiplo: 10 }),
      ),
    };
    await TestBed.configureTestingModule({
      imports: [ArticuloForm],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ArticulosService, useValue: servicio },
        { provide: RubrosService, useValue: { listar: vi.fn(() => of(rubros)) } },
        { provide: ProveedoresService, useValue: { listar: vi.fn(() => of(proveedores)) } },
        {
          provide: CotizacionService,
          useValue: {
            obtener: vi.fn(() =>
              cotizacion === 'falla' ? throwError(() => errorHttp(500, 'Error interno')) : of(cotizacion),
            ),
          },
        },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap(id === null ? {} : { id }) },
          },
        },
      ],
    }).compileComponents();
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(ArticuloForm);
    await refrescar();
  }

  const el = () => fixture.nativeElement as HTMLElement;

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
  const select = (id: string) => el().querySelector<HTMLSelectElement>(`#${id}`)!;

  async function escribir(id: string, valor: string) {
    const campo = input(id);
    campo.value = valor;
    campo.dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function elegir(id: string, texto: string) {
    const campo = select(id);
    const opcion = Array.from(campo.options).find((o) => o.textContent?.trim() === texto)!;
    campo.value = opcion.value;
    campo.dispatchEvent(new Event('change'));
    await refrescar();
  }

  const opciones = (id: string) =>
    Array.from(select(id).options).map((o) => (o.textContent ?? '').trim());
  const elegida = (id: string) => {
    const campo = select(id);
    return (campo.options[campo.selectedIndex]?.textContent ?? '').trim();
  };

  async function enviar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  async function completarMinimos() {
    await escribir('codigoInterno', 'TOR-001');
    await escribir('descripcion', 'Tornillo');
    await escribir('costo', '100');
  }

  describe('alta', () => {
    it('arranca con IVA 21 %, unidad Unidad, stock mínimo 0 y sin datos de solo lectura', async () => {
      await crearComponente();

      expect(el().querySelector('app-barra-acciones h1')?.textContent).toContain('Nuevo artículo');
      expect(elegida('alicuotaIva')).toBe('21 %');
      expect(elegida('unidad')).toBe('Unidad');
      expect(input('stockMinimo').value).toBe('0');
      expect(input('codigoInterno').value).toBe('');
      expect(el().querySelector('.stock-actual')).toBeNull();
      expect(el().querySelector('.precio-final')).toBeNull();
      expect(servicio.obtener).not.toHaveBeenCalled();
    });

    it('ofrece las alícuotas de ARCA y las cuatro unidades', async () => {
      await crearComponente();

      expect(opciones('alicuotaIva')).toEqual(['0 %', '2,5 %', '5 %', '10,5 %', '21 %', '27 %']);
      expect(opciones('unidad')).toEqual(['Unidad', 'Metro', 'Kilo', 'Litro']);
    });

    it('ofrece "Sin rubro" y los rubros, y "Sin proveedor" y solo los proveedores activos', async () => {
      await crearComponente();

      expect(opciones('rubro')).toEqual(['Sin rubro', 'Bulonería', 'Herramientas']);
      expect(opciones('proveedor')).toEqual(['Sin proveedor', 'Sinteplast', 'Tersuave']);
      expect(elegida('rubro')).toBe('Sin rubro');
      expect(elegida('proveedor')).toBe('Sin proveedor');
    });

    it('aclara que el margen vacío usa el del rubro o el de la ferretería', async () => {
      await crearComponente();

      expect(el().textContent).toContain('Vacío = usa el margen del rubro o de la ferretería');
      expect(input('margen').value).toBe('');
    });

    it('crea con los datos mínimos: opcionales en null, margen heredado y nombres de enum', async () => {
      await crearComponente();
      await completarMinimos();

      await enviar();

      expect(servicio.crear).toHaveBeenCalledTimes(1);
      expect(servicio.crear).toHaveBeenCalledWith({
        codigoInterno: 'TOR-001',
        codigoBarras: null,
        codigoProveedor: null,
        descripcion: 'Tornillo',
        rubroId: null,
        proveedorId: null,
        costo: 100,
        moneda: 'ARS',
        margenPorcentaje: null,
        alicuotaIva: 'IVA_21',
        unidad: 'UNIDAD',
        stockMinimo: 0,
        ubicacion: null,
        activo: true,
      });
      expect(servicio.actualizar).not.toHaveBeenCalled();
    });

    it('crea con todos los campos, sin espacios sobrantes y con los ids elegidos', async () => {
      await crearComponente();
      await escribir('codigoInterno', '  TOR-001 ');
      await escribir('codigoBarras', ' 7790001000012 ');
      await escribir('codigoProveedor', 'AB-12');
      await escribir('descripcion', '  Tornillo hexagonal 1/4 ');
      await escribir('costo', '99.5');
      await escribir('margen', '35');
      await escribir('stockMinimo', '4.5');
      await escribir('ubicacion', ' Pasillo 2 ');
      await elegir('unidad', 'Metro');
      await elegir('alicuotaIva', '10,5 %');
      await elegir('rubro', 'Herramientas');
      await elegir('proveedor', 'Tersuave');

      await enviar();

      expect(servicio.crear).toHaveBeenCalledWith({
        codigoInterno: 'TOR-001',
        codigoBarras: '7790001000012',
        codigoProveedor: 'AB-12',
        descripcion: 'Tornillo hexagonal 1/4',
        rubroId: 2,
        proveedorId: 11,
        costo: 99.5,
        moneda: 'ARS',
        margenPorcentaje: 35,
        alicuotaIva: 'IVA_10_5',
        unidad: 'METRO',
        stockMinimo: 4.5,
        ubicacion: 'Pasillo 2',
        activo: true,
      });
    });

    it('respeta un margen propio de 0 (no lo confunde con heredado)', async () => {
      await crearComponente();
      await completarMinimos();
      await escribir('margen', '0');

      await enviar();

      expect(servicio.crear.mock.calls[0][0].margenPorcentaje).toBe(0);
    });

    it('al guardar vuelve al listado', async () => {
      await crearComponente();
      await completarMinimos();

      await enviar();

      expect(navigate).toHaveBeenCalledWith(['/articulos'], VOLVER_AL_LISTADO);
    });

    it('no manda stock actual ni precio final', async () => {
      await crearComponente();
      await completarMinimos();

      await enviar();

      const enviado = servicio.crear.mock.calls[0][0];
      expect(enviado).not.toHaveProperty('stockActual');
      expect(enviado).not.toHaveProperty('precioFinal');
      expect(enviado).not.toHaveProperty('id');
    });

    it('no envía si faltan código interno, descripción o costo, y marca los errores', async () => {
      await crearComponente();

      await enviar();

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(navigate).not.toHaveBeenCalled();
      expect(el().textContent).toContain('El código interno es obligatorio');
      expect(el().textContent).toContain('La descripción es obligatoria');
      expect(el().textContent).toContain('El costo es obligatorio');
    });

    it('no envía un código interno ni una descripción en blanco', async () => {
      await crearComponente();
      await escribir('codigoInterno', '   ');
      await escribir('descripcion', '   ');
      await escribir('costo', '10');

      await enviar();

      expect(servicio.crear).not.toHaveBeenCalled();
    });

    it('no envía un costo, un margen ni un stock mínimo negativos', async () => {
      await crearComponente();
      await completarMinimos();

      await escribir('costo', '-1');
      await enviar();
      await escribir('costo', '100');
      await escribir('margen', '-5');
      await enviar();
      await escribir('margen', '');
      await escribir('stockMinimo', '-2');
      await enviar();

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(el().textContent).toContain('El stock mínimo no puede ser negativo');
    });

    it('muestra el mensaje del backend ante un 409 y se queda en el formulario', async () => {
      await crearComponente();
      await completarMinimos();
      servicio.crear.mockReturnValue(
        throwError(() => errorHttp(409, 'Ya existe un artículo con el código interno TOR-001.')),
      );

      await enviar();

      expect(el().querySelector('.error')?.textContent).toContain(
        'Ya existe un artículo con el código interno TOR-001.',
      );
      expect(navigate).not.toHaveBeenCalled();
      expect(el().querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(false);
    });

    it('muestra el mensaje del backend ante un 400', async () => {
      await crearComponente();
      await completarMinimos();
      servicio.crear.mockReturnValue(
        throwError(() => errorHttp(400, 'La alícuota de IVA no es válida.')),
      );

      await enviar();

      expect(el().querySelector('.error')?.textContent).toContain(
        'La alícuota de IVA no es válida.',
      );
    });

    it('muestra los errores de validación del backend por campo en vez del mensaje genérico', async () => {
      await crearComponente();
      await completarMinimos();
      servicio.crear.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: {
                status: 400,
                message: 'Datos inválidos',
                errors: {
                  costo: 'El costo no puede ser negativo',
                  descripcion: 'La descripción no puede superar 200 caracteres',
                },
              },
            }),
        ),
      );

      await enviar();

      const alerta = el().querySelector('.error')!;
      expect(alerta.textContent).toContain('El costo no puede ser negativo');
      expect(alerta.textContent).toContain('La descripción no puede superar 200 caracteres');
      expect(alerta.textContent).not.toContain('Datos inválidos');
      expect(el().querySelectorAll('.error li').length).toBe(2);
      expect(navigate).not.toHaveBeenCalled();
    });

    it('también los muestra al editar y los limpia al volver a guardar', async () => {
      await crearComponente({ id: '5' });
      servicio.actualizar.mockReturnValueOnce(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: { message: 'Datos inválidos', errors: { costo: 'El costo no puede ser negativo' } },
            }),
        ),
      );
      await enviar();
      expect(el().querySelector('.error')?.textContent).toContain('El costo no puede ser negativo');

      await enviar();

      expect(el().querySelector('.error')).toBeNull();
    });

    it('muestra el mensaje del backend ante un 404 (rubro o proveedor ajeno)', async () => {
      await crearComponente();
      await completarMinimos();
      servicio.crear.mockReturnValue(throwError(() => errorHttp(404, 'Rubro no encontrado')));

      await enviar();

      expect(el().querySelector('.error')?.textContent).toContain('Rubro no encontrado');
    });

    it('usa un mensaje genérico si el error no trae mensaje', async () => {
      await crearComponente();
      await completarMinimos();
      servicio.crear.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

      await enviar();

      expect(el().querySelector('.error')?.textContent).toContain(
        'No se pudo guardar el artículo.',
      );
    });

    it('limpia el error anterior al volver a guardar', async () => {
      await crearComponente();
      await completarMinimos();
      servicio.crear.mockReturnValueOnce(throwError(() => errorHttp(409, 'Duplicado')));
      await enviar();
      expect(el().querySelector('.error')).not.toBeNull();

      await enviar();

      expect(el().querySelector('.error')).toBeNull();
      expect(navigate).toHaveBeenCalledWith(['/articulos'], VOLVER_AL_LISTADO);
    });

    it('tiene un enlace para volver al listado sin guardar', async () => {
      await crearComponente();

      const cancelar = el().querySelector<HTMLAnchorElement>('a.cancelar');
      expect(cancelar).not.toBeNull();
      expect(cancelar!.getAttribute('href')).toBe('/articulos');
    });
  });

  describe('edición', () => {
    it('carga el artículo por id de la ruta y completa el formulario', async () => {
      await crearComponente({ id: '5' });

      expect(servicio.obtener).toHaveBeenCalledWith(5);
      expect(el().querySelector('app-barra-acciones h1')?.textContent).toContain('Editar artículo');
      expect(input('codigoInterno').value).toBe('TOR-001');
      expect(input('codigoBarras').value).toBe('7790001000012');
      expect(input('codigoProveedor').value).toBe('AB-12');
      expect(input('descripcion').value).toBe('Tornillo hexagonal 1/4');
      expect(input('costo').value).toBe('100');
      expect(input('margen').value).toBe('35');
      expect(input('stockMinimo').value).toBe('3');
      expect(input('ubicacion').value).toBe('Pasillo 2');
      expect(elegida('unidad')).toBe('Metro');
      expect(elegida('alicuotaIva')).toBe('10,5 %');
      expect(elegida('rubro')).toBe('Bulonería');
      expect(elegida('proveedor')).toBe('Sinteplast');
    });

    it('muestra stock actual y precio final del backend, solo de lectura', async () => {
      await crearComponente({ id: '5' });

      expect(el().querySelector('.stock-actual dd')?.textContent).toContain('12,5 metros');
      expect(el().querySelector('.precio-final')?.textContent).toContain(`$${NBSP}1.234,50`);
      expect(el().querySelector('input#stockActual')).toBeNull();
      expect(el().querySelector('input#precioFinal')).toBeNull();
    });

    it('un artículo sin margen propio, rubro ni proveedor deja esos campos vacíos', async () => {
      await crearComponente({
        id: '5',
        articulo: articuloGuardado({
          margenPorcentaje: null,
          rubroId: null,
          proveedorId: null,
          codigoBarras: null,
          codigoProveedor: null,
          ubicacion: null,
        }),
      });

      expect(input('margen').value).toBe('');
      expect(elegida('rubro')).toBe('Sin rubro');
      expect(elegida('proveedor')).toBe('Sin proveedor');
      expect(input('codigoBarras').value).toBe('');
      expect(input('ubicacion').value).toBe('');
    });

    it('guarda con PUT sobre el id, sin stock ni precio, y vuelve al listado', async () => {
      await crearComponente({ id: '5' });
      await escribir('descripcion', 'Tornillo hexagonal 5/16');
      await elegir('proveedor', 'Tersuave');

      await enviar();

      expect(servicio.crear).not.toHaveBeenCalled();
      expect(servicio.actualizar).toHaveBeenCalledTimes(1);
      const [id, enviado] = servicio.actualizar.mock.calls[0];
      expect(id).toBe(5);
      expect(enviado).toEqual({
        codigoInterno: 'TOR-001',
        codigoBarras: '7790001000012',
        codigoProveedor: 'AB-12',
        descripcion: 'Tornillo hexagonal 5/16',
        rubroId: 1,
        proveedorId: 11,
        costo: 100,
        moneda: 'ARS',
        margenPorcentaje: 35,
        alicuotaIva: 'IVA_10_5',
        unidad: 'METRO',
        stockMinimo: 3,
        ubicacion: 'Pasillo 2',
        activo: true,
      });
      expect(enviado).not.toHaveProperty('stockActual');
      expect(enviado).not.toHaveProperty('precioFinal');
      expect(navigate).toHaveBeenCalledWith(['/articulos'], VOLVER_AL_LISTADO);
    });

    it('puede quitar el margen propio para volver a heredar', async () => {
      await crearComponente({ id: '5' });
      await escribir('margen', '');

      await enviar();

      expect(servicio.actualizar.mock.calls[0][1].margenPorcentaje).toBeNull();
    });

    it('puede quitar el rubro y el proveedor', async () => {
      await crearComponente({ id: '5' });
      await elegir('rubro', 'Sin rubro');
      await elegir('proveedor', 'Sin proveedor');

      await enviar();

      const enviado = servicio.actualizar.mock.calls[0][1];
      expect(enviado.rubroId).toBeNull();
      expect(enviado.proveedorId).toBeNull();
    });

    it('conserva activo=false al editar un artículo dado de baja (no lo reactiva sin querer)', async () => {
      await crearComponente({ id: '5', articulo: articuloGuardado({ activo: false }) });

      await enviar();

      expect(servicio.actualizar.mock.calls[0][1].activo).toBe(false);
    });

    it('muestra el 409 del backend al editar', async () => {
      await crearComponente({ id: '5' });
      servicio.actualizar.mockReturnValue(
        throwError(() =>
          errorHttp(409, 'Ya existe un artículo activo con el código de barras 7790001000012.'),
        ),
      );

      await enviar();

      expect(el().querySelector('.error')?.textContent).toContain(
        'Ya existe un artículo activo con el código de barras 7790001000012.',
      );
      expect(navigate).not.toHaveBeenCalled();
    });

    it('si el artículo no existe muestra el mensaje del backend y no deja guardar', async () => {
      await crearComponente({ id: '99', articulo: errorHttp(404, 'Artículo no encontrado') });

      expect(el().querySelector('.error')?.textContent).toContain('Artículo no encontrado');
      expect(el().querySelector('form')).toBeNull();
      expect(el().querySelector('a.cancelar')).not.toBeNull();
    });

    it('usa un mensaje genérico si la carga falla sin mensaje del backend', async () => {
      await crearComponente({ id: '5', articulo: new HttpErrorResponse({ status: 0 }) });

      expect(el().querySelector('.error')?.textContent).toContain(
        'No se pudo cargar el artículo.',
      );
    });

    it('un id que no es un número no consulta al backend', async () => {
      await crearComponente({ id: 'abc' });

      expect(servicio.obtener).not.toHaveBeenCalled();
      expect(el().querySelector('.error')?.textContent).toContain('Artículo no encontrado');
    });
  });

  describe('Vendedor', () => {
    it('ve el artículo en solo lectura: campos deshabilitados y sin botón Guardar', async () => {
      await crearComponente({ dueno: false, id: '5' });

      expect(el().querySelector('app-barra-acciones h1')?.textContent).toContain('Artículo');
      expect(el().querySelector('app-barra-acciones h1')?.textContent).not.toContain('Editar');
      expect(input('codigoInterno').value).toBe('TOR-001');
      expect(input('costo').value).toBe('100');
      expect(elegida('rubro')).toBe('Bulonería');
      const campos = Array.from(el().querySelectorAll<HTMLElement>('input, select'));
      expect(campos.length).toBeGreaterThan(5);
      expect(campos.every((c) => (c as HTMLInputElement).disabled)).toBe(true);
      expect(el().querySelector('button[type=submit]')).toBeNull();
      expect(el().querySelector('.precio-final')?.textContent).toContain(`$${NBSP}1.234,50`);
      expect(el().querySelector('a.cancelar')).not.toBeNull();
    });

    it('no puede guardar aunque dispare el envío del formulario', async () => {
      await crearComponente({ dueno: false, id: '5' });

      await enviar();

      expect(servicio.actualizar).not.toHaveBeenCalled();
      expect(servicio.crear).not.toHaveBeenCalled();
    });

    it('en /articulos/nuevo no ofrece guardar', async () => {
      await crearComponente({ dueno: false });

      expect(el().querySelector('button[type=submit]')).toBeNull();
      await enviar();
      expect(servicio.crear).not.toHaveBeenCalled();
    });
  });

  describe('Dueño', () => {
    it('ve el botón Guardar y los campos habilitados', async () => {
      await crearComponente({ id: '5' });

      expect(el().querySelector('button[type=submit]')?.textContent).toContain('Guardar');
      expect(input('codigoInterno').disabled).toBe(false);
    });
  });

  describe('precio final en vivo', () => {
    const enVivo = () => (el().querySelector('.precio-en-vivo')?.textContent ?? '').replace(/[ \n\t]+/g, ' ').trim();
    const herencia = () => (el().querySelector('.herencia')?.textContent ?? '').trim();

    it('sin costo todavía no hay precio', async () => {
      await crearComponente();

      expect(enVivo()).toContain('Completá el costo');
    });

    it('se recalcula mientras se cargan costo, rubro, margen e IVA, con el redondeo del backend', async () => {
      await crearComponente();

      await escribir('costo', '100');
      expect(enVivo()).toContain(`$${NBSP}160,00`);

      await elegir('rubro', 'Bulonería');
      expect(enVivo()).toContain(`$${NBSP}170,00`);

      await escribir('margen', '50');
      expect(enVivo()).toContain(`$${NBSP}190,00`);

      await elegir('alicuotaIva', '10,5 %');
      expect(enVivo()).toContain(`$${NBSP}170,00`);

      await escribir('costo', '1020');
      await escribir('margen', '0');
      await elegir('alicuotaIva', '21 %');
      expect(enVivo()).toContain(`$${NBSP}1.240,00`);
    });

    it('con el margen vacío dice de dónde lo hereda', async () => {
      await crearComponente();

      expect(herencia()).toBe('Hereda 30 % de la ferretería');

      await elegir('rubro', 'Bulonería');
      expect(herencia()).toBe('Hereda 35 % de Bulonería');

      await escribir('margen', '20');
      expect(el().querySelector('.herencia')).toBeNull();
    });

    it('en la edición parte del artículo guardado', async () => {
      await crearComponente({ id: '5', articulo: articuloGuardado({ alicuotaIva: 'IVA_21', margenPorcentaje: null }) });

      expect(enVivo()).toContain(`$${NBSP}170,00`);
      expect(herencia()).toBe('Hereda 35 % de Bulonería');
    });

    it('si no se pudo leer la configuración, el formulario funciona igual sin precio en vivo', async () => {
      await crearComponente({ sinConfiguracion: true });

      expect(el().querySelector('.precio-en-vivo')).toBeNull();
      expect(el().querySelector('.error')).toBeNull();
      await completarMinimos();
      await enviar();
      expect(servicio.crear).toHaveBeenCalled();
    });
  });

  describe('moneda del artículo', () => {
    const enVivo = () => (el().querySelector('.precio-en-vivo')?.textContent ?? '').replace(/[ \n\t]+/g, ' ').trim();
    const texto = (id: string) => (el().querySelector(`#${id}`)?.textContent ?? '').replace(/\s+/g, ' ').trim();
    const etiqueta = (id: string) => (el().querySelector(`label[for="${id}"]`)?.textContent ?? '').trim();

    it('el selector de moneda va junto al costo, con su etiqueta, y un alta arranca en pesos', async () => {
      await crearComponente();

      expect(etiqueta('moneda')).toBe('Moneda');
      expect(opciones('moneda')).toEqual(['Pesos (ARS)', 'Dólares (USD)']);
      expect(elegida('moneda')).toBe('Pesos (ARS)');
      const seccion = select('moneda').closest('fieldset')!;
      expect(seccion.querySelector('legend')?.textContent).toBe('Precio');
      expect(seccion.contains(input('costo'))).toBe(true);
    });

    it('un alta en pesos manda moneda ARS', async () => {
      await crearComponente();
      await completarMinimos();

      await enviar();

      expect(servicio.crear.mock.calls[0][0].moneda).toBe('ARS');
    });

    it('un alta en dólares manda moneda USD y el costo cargado en dólares', async () => {
      await crearComponente();
      await escribir('codigoInterno', 'TOR-001');
      await escribir('descripcion', 'Tornillo');
      await elegir('moneda', 'Dólares (USD)');
      await escribir('costo', '10');

      await enviar();

      expect(servicio.crear).toHaveBeenCalledTimes(1);
      expect(servicio.crear.mock.calls[0][0]).toMatchObject({ moneda: 'USD', costo: 10 });
    });

    it('al cambiar la moneda el costo se borra y no se puede guardar sin cargarlo de nuevo', async () => {
      await crearComponente();
      await escribir('costo', '100');
      expect(input('costo').value).toBe('100');

      await elegir('moneda', 'Dólares (USD)');

      expect(input('costo').value).toBe('');
      await escribir('codigoInterno', 'TOR-001');
      await escribir('descripcion', 'Tornillo');
      await enviar();
      expect(servicio.crear).not.toHaveBeenCalled();
      expect(el().textContent).toContain('El costo es obligatorio.');
    });

    it('no convierte nada: al volver a pesos el costo también queda vacío', async () => {
      await crearComponente({ id: '5', articulo: articuloGuardado({ moneda: 'USD', costo: 10 }) });
      expect(input('costo').value).toBe('10');

      await elegir('moneda', 'Pesos (ARS)');

      expect(input('costo').value).toBe('');
    });

    it('al cargar un artículo existente el costo NO se borra', async () => {
      await crearComponente({ id: '5', articulo: articuloGuardado({ moneda: 'USD', costo: 10 }) });

      expect(elegida('moneda')).toBe('Dólares (USD)');
      expect(input('costo').value).toBe('10');
    });

    it('avisa al lado del selector que cambiar la moneda borra el costo', async () => {
      await crearComponente();

      expect(texto('moneda-pista')).toBe('Si cambiás la moneda, el costo se borra y lo cargás de nuevo: no se convierte.');
      expect(select('moneda').getAttribute('aria-describedby')).toContain('moneda-pista');
    });

    it('el costo en dólares lleva su propio rótulo', async () => {
      await crearComponente();
      expect(etiqueta('costo')).toBe('Costo (sin IVA)');

      await elegir('moneda', 'Dólares (USD)');

      expect(etiqueta('costo')).toBe('Costo en dólares (sin IVA)');
    });

    describe('con la cotización apagada', () => {
      it('no se ofrece USD y se dice cómo cargar en dólares', async () => {
        await crearComponente({ cotizacion: cotizacionActiva({ activa: false }) });

        expect(opciones('moneda')).toEqual(['Pesos (ARS)']);
        expect(texto('moneda-apagada')).toBe('Activá la cotización para cargar artículos en dólares');
        expect(select('moneda').getAttribute('aria-describedby')).toContain('moneda-apagada');
      });

      it('un artículo que ya está en dólares sigue siendo editable y conserva USD', async () => {
        await crearComponente({
          id: '5',
          articulo: articuloGuardado({ moneda: 'USD', costo: 10 }),
          cotizacion: cotizacionActiva({ activa: false }),
        });

        expect(opciones('moneda')).toEqual(['Pesos (ARS)', 'Dólares (USD)']);
        expect(elegida('moneda')).toBe('Dólares (USD)');
        expect(el().querySelector('#moneda-apagada')).toBeNull();

        await escribir('margen', '40');
        await enviar();

        expect(servicio.actualizar.mock.calls[0][1]).toMatchObject({ moneda: 'USD', costo: 10, margenPorcentaje: 40 });
      });

      it('un artículo en pesos no puede pasarse a dólares: sigue sin ofrecerse', async () => {
        await crearComponente({ id: '5', cotizacion: cotizacionActiva({ activa: false }) });

        expect(opciones('moneda')).toEqual(['Pesos (ARS)']);
        expect(texto('moneda-apagada')).toBe('Activá la cotización para cargar artículos en dólares');
      });
    });

    it('con la cotización activada no aparece el aviso de apagada', async () => {
      await crearComponente();

      expect(el().querySelector('#moneda-apagada')).toBeNull();
    });

    it('si no se puede leer la cotización el formulario funciona igual y no esconde USD', async () => {
      await crearComponente({ cotizacion: 'falla' });

      expect(opciones('moneda')).toEqual(['Pesos (ARS)', 'Dólares (USD)']);
      expect(el().querySelector('#moneda-apagada')).toBeNull();
      await completarMinimos();
      await enviar();
      expect(servicio.crear).toHaveBeenCalled();
    });

    describe('precio en vivo en dólares', () => {
      it('10 USD con el dólar a 1.000 da $ 15.730, lo mismo que el backend', async () => {
        await crearComponente();
        await elegir('moneda', 'Dólares (USD)');

        await escribir('costo', '10');
        await escribir('margen', '30');
        await elegir('alicuotaIva', '21 %');

        expect(enVivo()).toContain(`$${NBSP}15.730,00`);
      });

      it('con el dólar a 1.100 da $ 17.310 y con 950, $ 14.950', async () => {
        await crearComponente({ cotizacion: cotizacionActiva({}, 1100) });
        await elegir('moneda', 'Dólares (USD)');
        await escribir('costo', '10');
        await escribir('margen', '30');
        expect(enVivo()).toContain(`$${NBSP}17.310,00`);

        TestBed.resetTestingModule();
        await crearComponente({ cotizacion: cotizacionActiva({}, 950) });
        await elegir('moneda', 'Dólares (USD)');
        await escribir('costo', '10');
        await escribir('margen', '30');
        expect(enVivo()).toContain(`$${NBSP}14.950,00`);
      });

      it('dice con qué dólar se calcula', async () => {
        await crearComponente();
        await elegir('moneda', 'Dólares (USD)');
        await escribir('costo', '10');

        expect(texto('precio-dolar')).toBe('Calculado con el dólar a 1.000,00');
      });

      it('en pesos el dólar no entra en la cuenta ni se nombra', async () => {
        await crearComponente({ cotizacion: cotizacionActiva({}, 1100) });

        await escribir('costo', '100');

        expect(enVivo()).toContain(`$${NBSP}160,00`);
        expect(el().querySelector('#precio-dolar')).toBeNull();
      });

      it('en la edición de un artículo en dólares parte del dólar vigente', async () => {
        await crearComponente({
          id: '5',
          articulo: articuloGuardado({ moneda: 'USD', costo: 10, margenPorcentaje: 30, alicuotaIva: 'IVA_21', precioFinal: 15730 }),
        });

        expect(enVivo()).toContain(`$${NBSP}15.730,00`);
      });

      it('sin ninguna cotización del dólar no hay precio y se explica por qué', async () => {
        await crearComponente({ cotizacion: cotizacionActiva({}, null) });
        await elegir('moneda', 'Dólares (USD)');

        await escribir('costo', '10');

        expect(enVivo()).toContain('Todavía no hay cotización del dólar para calcular el precio.');
        expect(enVivo()).not.toContain('$');
      });

      it('si no se pudo leer la cotización, en dólares no hay precio en vivo', async () => {
        await crearComponente({ cotizacion: 'falla' });
        await elegir('moneda', 'Dólares (USD)');

        await escribir('costo', '10');

        expect(enVivo()).toContain('Todavía no hay cotización del dólar para calcular el precio.');
      });
    });

    describe('errores del backend', () => {
      it('un 409 por falta de cotización se muestra', async () => {
        await crearComponente();
        servicio.crear.mockReturnValue(
          throwError(() => errorHttp(409, 'Todavía no hay cotización del dólar. Probá en unos minutos')),
        );
        await escribir('codigoInterno', 'TOR-001');
        await escribir('descripcion', 'Tornillo');
        await elegir('moneda', 'Dólares (USD)');
        await escribir('costo', '10');

        await enviar();

        expect(el().querySelector('.error[role="alert"]')?.textContent).toContain(
          'Todavía no hay cotización del dólar. Probá en unos minutos',
        );
        expect(navigate).not.toHaveBeenCalled();
      });

      it('un 409 por cotización apagada se muestra', async () => {
        await crearComponente();
        servicio.crear.mockReturnValue(
          throwError(() => errorHttp(409, 'Activá la cotización para cargar artículos en dólares')),
        );
        await escribir('codigoInterno', 'TOR-001');
        await escribir('descripcion', 'Tornillo');
        await elegir('moneda', 'Dólares (USD)');
        await escribir('costo', '10');

        await enviar();

        expect(el().querySelector('.error[role="alert"]')?.textContent).toContain(
          'Activá la cotización para cargar artículos en dólares',
        );
      });
    });

    it('el Vendedor ve la moneda del artículo, pero no la puede cambiar', async () => {
      await crearComponente({ dueno: false, id: '5', articulo: articuloGuardado({ moneda: 'USD', costo: 10 }) });

      expect(elegida('moneda')).toBe('Dólares (USD)');
      expect(select('moneda').disabled).toBe(true);
    });
  });

  describe('errores', () => {
    const errorDe = (campo: string) =>
      (el().querySelector(`[data-error-de="${campo}"]`)?.textContent ?? '').trim();

    it('los errores del backend aparecen junto a cada campo y todos juntos arriba', async () => {
      await crearComponente();
      await completarMinimos();
      servicio.crear.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: {
                message: 'Datos inválidos',
                errors: { costo: 'El costo admite hasta 2 decimales', descripcion: 'La descripción es obligatoria' },
              },
            }),
        ),
      );

      await enviar();

      expect(errorDe('costo')).toBe('El costo admite hasta 2 decimales');
      expect(errorDe('descripcion')).toBe('La descripción es obligatoria');
      expect(el().querySelectorAll('.error li').length).toBe(2);
    });

    it('un 409 con códigos repetidos marca cada campo', async () => {
      await crearComponente();
      await completarMinimos();
      servicio.crear.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: {
                message: 'Ya existe... Ya existe...',
                errors: {
                  codigoInterno: 'Ya existe un artículo con el código interno TOR-001.',
                  codigoBarras: 'Ya existe un artículo activo con el código de barras 779.',
                },
              },
            }),
        ),
      );

      await enviar();

      expect(errorDe('codigoInterno')).toBe('Ya existe un artículo con el código interno TOR-001.');
      expect(errorDe('codigoBarras')).toBe('Ya existe un artículo activo con el código de barras 779.');
    });

    it('al corregir un campo se borra su error del backend', async () => {
      await crearComponente();
      await completarMinimos();
      servicio.crear.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: { message: 'x', errors: { codigoInterno: 'Ya existe un artículo con el código interno TOR-001.' } },
            }),
        ),
      );
      await enviar();

      await escribir('codigoInterno', 'TOR-002');

      expect(el().querySelector('[data-error-de="codigoInterno"]')).toBeNull();
    });

    it('sin datos marca todos los campos obligatorios a la vez', async () => {
      await crearComponente();

      await enviar();

      const mensajes = Array.from(el().querySelectorAll('.campo-error')).map((e) => e.textContent?.trim());
      expect(mensajes).toEqual(
        expect.arrayContaining([
          'El código interno es obligatorio.',
          'La descripción es obligatoria.',
          'El costo es obligatorio.',
        ]),
      );
    });
  });

  describe('accesibilidad de los campos', () => {
    /** Los textos a los que apunta el aria-describedby del campo. */
    const descripciones = (id: string) =>
      (el().querySelector(`#${id}`)!.getAttribute('aria-describedby') ?? '')
        .split(' ')
        .filter((ref) => ref !== '')
        .map((ref) => el().querySelector(`#${ref}`)?.textContent?.trim());

    it('al enviar con errores enfoca el primer campo inválido', async () => {
      await crearComponente();

      await enviar();
      expect(document.activeElement).toBe(input('codigoInterno'));

      await escribir('codigoInterno', 'TOR-001');
      await enviar();
      expect(document.activeElement).toBe(input('descripcion'));
    });

    it('si el backend marca campos, enfoca el primero', async () => {
      await crearComponente();
      await completarMinimos();
      servicio.crear.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: { message: 'x', errors: { codigoBarras: 'Ya existe un artículo activo con el código de barras 779.' } },
            }),
        ),
      );

      await enviar();

      expect(document.activeElement).toBe(input('codigoBarras'));
    });

    it('los campos inválidos llevan aria-invalid y su error enlazado con aria-describedby', async () => {
      await crearComponente();

      await enviar();

      expect(input('codigoInterno').getAttribute('aria-invalid')).toBe('true');
      expect(descripciones('codigoInterno')).toEqual(['El código interno es obligatorio.']);
      expect(input('costo').getAttribute('aria-invalid')).toBe('true');
      expect(descripciones('costo')).toEqual(['El costo es obligatorio.']);
      expect(input('codigoBarras').getAttribute('aria-invalid')).toBeNull();
    });

    it('el error del backend también queda enlazado al campo', async () => {
      await crearComponente();
      await completarMinimos();
      servicio.crear.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: { message: 'x', errors: { costo: 'El costo admite hasta 2 decimales' } },
            }),
        ),
      );

      await enviar();

      expect(input('costo').getAttribute('aria-invalid')).toBe('true');
      expect(descripciones('costo')).toEqual(['El costo admite hasta 2 decimales']);
    });

    it('las pistas del margen quedan enlazadas al campo', async () => {
      await crearComponente();

      expect(descripciones('margen')).toEqual([
        'Vacío = usa el margen del rubro o de la ferretería',
        'Hereda 30 % de la ferretería',
      ]);
    });

    it('los códigos no autocompletan ni corrigen la ortografía', async () => {
      await crearComponente();

      for (const id of ['codigoInterno', 'codigoBarras', 'codigoProveedor']) {
        expect(input(id).getAttribute('autocomplete')).toBe('off');
        expect(input(id).getAttribute('spellcheck')).toBe('false');
        expect(input(id).name).toBe(id);
      }
    });

    it('costo, margen y stock mínimo abren el teclado decimal', async () => {
      await crearComponente();

      for (const id of ['costo', 'margen', 'stockMinimo']) {
        expect(input(id).getAttribute('inputmode')).toBe('decimal');
      }
    });

    it('los ejemplos de los campos terminan en «…»', async () => {
      await crearComponente();

      const placeholders = Array.from(el().querySelectorAll<HTMLInputElement>('input[placeholder]')).map(
        (i) => i.placeholder,
      );
      expect(placeholders.length).toBeGreaterThan(2);
      expect(placeholders.every((p) => p.endsWith('…'))).toBe(true);
    });
  });

  describe('guardar y cancelar', () => {
    it('al guardar deja un aviso de éxito para el listado', async () => {
      await crearComponente();
      await completarMinimos();

      await enviar();

      expect(TestBed.inject(Avisos).tomar()).toBe('Se guardó «Tornillo».');
      expect(navigate).toHaveBeenCalledWith(['/articulos'], VOLVER_AL_LISTADO);
    });

    it('cancelar sin cambios vuelve directo al listado', async () => {
      await crearComponente({ id: '5' });

      el().querySelector<HTMLAnchorElement>('a.cancelar')!.click();
      await refrescar();

      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(navigate).toHaveBeenCalledWith(['/articulos'], VOLVER_AL_LISTADO);
    });

    it('cancelar con cambios pregunta con el diálogo propio', async () => {
      await crearComponente({ id: '5' });
      await escribir('descripcion', 'Tornillo cambiado');

      el().querySelector<HTMLAnchorElement>('a.cancelar')!.click();
      await refrescar();

      expect(el().querySelector('app-confirmacion h2')?.textContent).toContain('¿Descartar los cambios?');
      expect(navigate).not.toHaveBeenCalled();

      el().querySelector<HTMLButtonElement>('app-confirmacion button.cancelar')!.click();
      await refrescar();
      expect(el().querySelector('app-confirmacion')).toBeNull();
      expect(navigate).not.toHaveBeenCalled();
      expect(input('descripcion').value).toBe('Tornillo cambiado');

      el().querySelector<HTMLAnchorElement>('a.cancelar')!.click();
      await refrescar();
      el().querySelector<HTMLButtonElement>('app-confirmacion button.confirmar')!.click();
      await refrescar();
      expect(navigate).toHaveBeenCalledWith(['/articulos'], VOLVER_AL_LISTADO);
    });
  });

  describe('stock y estado', () => {
    it('ya no promete que el stock se mueve con las compras', async () => {
      await crearComponente();

      expect(el().textContent).not.toContain('El stock se mueve desde las compras');
    });

    it('el Dueño ajusta el stock desde el formulario y ve el stock nuevo', async () => {
      await crearComponente({ id: '5' });

      await (async () => {
        el().querySelector<HTMLButtonElement>('button.ajustar-stock')!.click();
        await refrescar();
      })();
      const dialogo = fixture.debugElement.query(By.directive(DialogoAjusteStock));
      expect((dialogo.componentInstance as DialogoAjusteStock).articulo()).toEqual({
        id: 5,
        codigoInterno: 'TOR-001',
        descripcion: 'Tornillo hexagonal 1/4',
        unidad: 'METRO',
        stockActual: 12.5,
      });

      (dialogo.componentInstance as DialogoAjusteStock).ajustado.emit({
        articuloId: 5,
        stockAnterior: 12.5,
        diferencia: 37.5,
        stockActual: 50,
      });
      await refrescar();

      expect(fixture.debugElement.query(By.directive(DialogoAjusteStock))).toBeNull();
      expect(el().querySelector('.stock-actual dd')?.textContent).toContain('50 metros');
      expect(el().querySelector('.alerta.exito')?.textContent).toContain('Stock ajustado: ahora hay 50 metros.');
    });

    it('en el alta y para el Vendedor no hay «Ajustar stock»', async () => {
      await crearComponente();
      expect(el().querySelector('button.ajustar-stock')).toBeNull();

      TestBed.resetTestingModule();
      await crearComponente({ dueno: false, id: '5' });
      expect(el().querySelector('button.ajustar-stock')).toBeNull();
    });

    it('un artículo dado de baja tiene «Ajustar stock» deshabilitado con la ayuda', async () => {
      await crearComponente({
        id: '5',
        articulo: articuloGuardado({ activo: false, descripcion: 'Tuerca vieja' }),
      });

      const boton = el().querySelector<HTMLButtonElement>('button.ajustar-stock')!;
      expect(boton.disabled).toBe(true);
      const motivo = el().querySelector(`#${boton.getAttribute('aria-describedby')}`);
      expect(motivo?.textContent?.trim()).toBe('«Tuerca vieja» está dado de baja. Reactivalo para ajustar el stock.');
    });

    it('un artículo dado de baja muestra la etiqueta «De baja» y se puede reactivar desde la barra', async () => {
      await crearComponente({ id: '5', articulo: articuloGuardado({ activo: false }) });

      expect(el().querySelector('.etiqueta.de-baja')?.textContent?.trim()).toBe('De baja');
      el().querySelector<HTMLButtonElement>('app-barra-acciones button.reactivar')!.click();
      await refrescar();

      expect(servicio.reactivar).toHaveBeenCalledWith(5);
      expect(el().querySelector('.etiqueta.de-baja')).toBeNull();
      expect(el().querySelector('button.reactivar')).toBeNull();
      expect(el().querySelector('.alerta.exito')?.textContent).toContain('Se reactivó el artículo.');
    });

    it('un activo no muestra la etiqueta ni Reactivar; el Vendedor ve la etiqueta pero no Reactivar', async () => {
      await crearComponente({ id: '5' });
      expect(el().querySelector('.etiqueta.de-baja')).toBeNull();
      expect(el().querySelector('button.reactivar')).toBeNull();

      TestBed.resetTestingModule();
      await crearComponente({ dueno: false, id: '5', articulo: articuloGuardado({ activo: false }) });
      expect(el().querySelector('.etiqueta.de-baja')).not.toBeNull();
      expect(el().querySelector('button.reactivar')).toBeNull();
    });
  });
  describe('desglose y ajustes del artículo (R-212 a R-219)', () => {
    const limpio = (texto: string | null | undefined) => (texto ?? '').replace(/[\s ]+/g, ' ').trim();
    const importes = () =>
      Array.from(el().querySelectorAll('app-desglose-precio .paso-importe')).map((n) => limpio(n.textContent));
    const botonAjustes = () => el().querySelector<HTMLButtonElement>('button.guardar-ajustes')!;
    const banner = () => limpio(el().querySelector('[data-foco-ajustes]')?.textContent);

    async function clicAjustes() {
      botonAjustes().click();
      await refrescar();
    }
    const conLista = (cambios: Partial<DesglosePrecio> = {}) => ({
      id: '5',
      articulo: articuloConLista(),
      desglose: desgloseDe(cambios),
    });

    describe('carga', () => {
      it('la edición pide el desglose del artículo y lo muestra', async () => {
        await crearComponente(conLista());

        expect(servicio.desglose).toHaveBeenCalledTimes(1);
        expect(servicio.desglose).toHaveBeenCalledWith(5);
        expect(el().querySelector('app-desglose-precio')).not.toBeNull();
        expect(importes().at(-1)).toBe('$ 1.390,00');
      });

      it('la cadena arranca con los ajustes guardados en los tres campos', async () => {
        await crearComponente(conLista());

        expect(input('ajuste1').value).toBe('-5');
        expect(input('ajuste2').value).toBe('3');
        expect(input('ajuste3').value).toBe('');
        expect(importes()).toEqual([
          '$ 1.000,00', '$ 900,00', '$ 855,00', '$ 880,65', '$ 880,65', '$ 1.144,845', '$ 1.385,2625', '$ 1.390,00',
        ]);
      });

      it('el alta no pide el desglose ni muestra campos de ajuste', async () => {
        await crearComponente();

        expect(servicio.desglose).not.toHaveBeenCalled();
        expect(el().querySelector('app-desglose-precio')).toBeNull();
        expect(el().querySelector('#ajuste1')).toBeNull();
        expect(el().querySelector('button.guardar-ajustes')).toBeNull();
      });

      it('si el desglose no se puede leer, el formulario del artículo funciona igual', async () => {
        await crearComponente({ id: '5', desglose: errorHttp(500, 'Error interno') });

        expect(el().querySelector('app-desglose-precio')).toBeNull();
        expect(input('descripcion').value).toBe('Tornillo hexagonal 1/4');
      });
    });

    describe('el PUT del artículo no toca los ajustes (decisión 43)', () => {
      it('guardar el artículo no manda ajustes ni llama al endpoint de ajustes', async () => {
        await crearComponente(conLista());
        await escribir('ajuste1', '10');
        await escribir('descripcion', 'Otra descripción');

        await enviar();

        const [, enviado] = servicio.actualizar.mock.calls[0];
        expect(Object.keys(enviado).filter((k) => /^(ajuste|bonificacion)/.test(k))).toEqual([]);
        expect(servicio.guardarAjustes).not.toHaveBeenCalled();
      });

      it('escribir un ajuste no ensucia el formulario del artículo', async () => {
        await crearComponente(conLista());

        await escribir('ajuste1', '10');
        el().querySelector<HTMLAnchorElement>('a.cancelar')!.click();
        await refrescar();

        expect(el().querySelector('app-confirmacion')).toBeNull();
        expect(navigate).toHaveBeenCalledWith(['/articulos'], VOLVER_AL_LISTADO);
      });
    });

    describe('en vivo', () => {
      it('al borrar el +3 la pantalla muestra costo 855,00 y precio $ 1.350,00 antes de guardar', async () => {
        await crearComponente(conLista());

        await escribir('ajuste2', '');

        expect(importes().slice(0, 4)).toEqual(['$ 1.000,00', '$ 900,00', '$ 855,00', '$ 855,00']);
        expect(importes().at(-1)).toBe('$ 1.350,00');
        expect(servicio.guardarAjustes).not.toHaveBeenCalled();
      });
    });

    describe('guardar ajustes', () => {
      it('está apagado mientras no haya cambios', async () => {
        await crearComponente(conLista());

        expect(botonAjustes().disabled).toBe(true);

        await escribir('ajuste2', '');
        expect(botonAjustes().disabled).toBe(false);

        await escribir('ajuste2', '3');
        expect(botonAjustes().disabled).toBe(true);
      });

      it('está apagado con un ajuste inválido', async () => {
        await crearComponente(conLista());

        await escribir('ajuste1', '-100');

        expect(botonAjustes().disabled).toBe(true);
      });

      it('si el costo guardado coincide con el de la lista guarda directo, sin aviso ni confirmación', async () => {
        await crearComponente(conLista());
        await escribir('ajuste2', '');

        await clicAjustes();

        expect(el().querySelector('app-confirmacion')).toBeNull();
        expect(servicio.guardarAjustes).toHaveBeenCalledTimes(1);
        expect(servicio.guardarAjustes).toHaveBeenCalledWith(
          5,
          expect.objectContaining({ ajuste1: -5, ajuste2: null, ajuste3: null, confirmaPisarCosto: false }),
        );
        const pedido = servicio.guardarAjustes.mock.calls[0][1];
        expect(pedido).not.toHaveProperty('costo');
      });

      it('manda también un ajuste nuevo y decimales exactos', async () => {
        await crearComponente(conLista());
        await escribir('ajuste3', '1.5');

        await clicAjustes();

        expect(servicio.guardarAjustes.mock.calls[0][1]).toEqual(
          expect.objectContaining({ ajuste1: -5, ajuste2: 3, ajuste3: 1.5 }),
        );
      });

      it('después de guardar vuelve a pedir el artículo y el desglose y muestra lo que devolvió el servidor', async () => {
        await crearComponente(conLista());
        await escribir('ajuste2', '');
        const guardado = articuloConLista({ costo: 855, precioFinal: 1350, ajuste2: null });
        servicio.guardarAjustes.mockReturnValue(of(guardado));
        servicio.obtener.mockReturnValue(of(guardado));
        // El servidor manda: acá difiere del cálculo en vivo (860 contra 855).
        servicio.desglose.mockReturnValue(
          of(desgloseDe({ ajuste2: null, costoDeLista: 855, costoGuardado: 860, coincide: false, precioFinal: 1360 })),
        );

        await clicAjustes();

        expect(servicio.obtener).toHaveBeenCalledTimes(2);
        expect(servicio.desglose).toHaveBeenCalledTimes(2);
        expect(importes()).toContain('$ 860,00');
        expect(limpio(el().querySelector('.precio-final dd')?.textContent)).toBe('$ 1.350,00');
        expect(banner()).toBe('Se guardaron los ajustes.');
      });

      it('el costo nuevo pasa al campo costo del formulario, para no volver a pisarlo con el viejo', async () => {
        await crearComponente(conLista());
        await escribir('ajuste2', '');
        const guardado = articuloConLista({ costo: 855, precioFinal: 1350, ajuste2: null });
        servicio.guardarAjustes.mockReturnValue(of(guardado));
        servicio.obtener.mockReturnValue(of(guardado));

        await clicAjustes();

        expect(input('costo').value).toBe('855');
      });

      it('lo escrito en el resto del formulario se conserva al guardar ajustes', async () => {
        await crearComponente(conLista());
        await escribir('descripcion', 'Cambio sin guardar');
        await escribir('ajuste2', '');
        const guardado = articuloConLista({ costo: 855, ajuste2: null });
        servicio.guardarAjustes.mockReturnValue(of(guardado));
        servicio.obtener.mockReturnValue(of(guardado));

        await clicAjustes();

        expect(input('descripcion').value).toBe('Cambio sin guardar');
      });

      it('un 409 (por ejemplo sin cotización) muestra el mensaje del servidor y conserva lo escrito', async () => {
        await crearComponente(conLista());
        await escribir('ajuste2', '7');
        servicio.guardarAjustes.mockReturnValue(
          throwError(() => errorHttp(409, 'Todavía no hay cotización del dólar. Probá en unos minutos')),
        );

        await clicAjustes();

        expect(banner()).toBe('Todavía no hay cotización del dólar. Probá en unos minutos');
        expect(input('ajuste2').value).toBe('7');
        expect(input('ajuste1').value).toBe('-5');
        expect(servicio.obtener).toHaveBeenCalledTimes(1);
      });

      it('un error del servidor sin mensaje usa un texto propio', async () => {
        await crearComponente(conLista());
        await escribir('ajuste2', '7');
        servicio.guardarAjustes.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

        await clicAjustes();

        expect(banner()).toBe('No se pudieron guardar los ajustes.');
      });

      it('el aviso del resultado recibe el foco para que se anuncie', async () => {
        await crearComponente(conLista());
        await escribir('ajuste2', '7');
        servicio.guardarAjustes.mockReturnValue(throwError(() => errorHttp(409, 'No se puede')));

        await clicAjustes();

        expect(document.activeElement).toBe(el().querySelector('[data-foco-ajustes]'));
      });

      it('mientras guarda, el botón está apagado', async () => {
        await crearComponente(conLista());
        await escribir('ajuste2', '');
        const respuesta = new Subject<Articulo>();
        servicio.guardarAjustes.mockReturnValue(respuesta);

        await clicAjustes();

        expect(botonAjustes().disabled).toBe(true);
        respuesta.error(errorHttp(409, 'x'));
        await refrescar();
        expect(botonAjustes().disabled).toBe(false);
      });
    });

    describe('aviso antes de pisar un costo cargado a mano (R-214)', () => {
      const costoAMano = () =>
        conLista({
          bonificacion1: 10,
          ajuste1: null,
          ajuste2: null,
          costoDeLista: 900,
          costoGuardado: 950,
          coincide: false,
          precioFinal: 1500,
        });

      it('antes de enviar abre la confirmación con los dos costos y el que quedaría, sin pedido de prueba', async () => {
        await crearComponente({ ...costoAMano(), articulo: articuloConLista({ costo: 950, ajuste1: null, ajuste2: null }) });
        await escribir('ajuste1', '-5');

        await clicAjustes();

        expect(servicio.guardarAjustes).not.toHaveBeenCalled();
        expect(limpio(el().querySelector('app-confirmacion p')?.textContent)).toBe(
          'El costo guardado ($ 950,00) no coincide con el que sale de la lista ($ 900,00). Si guardás, el costo pasa a $ 855,00.',
        );
      });

      it('confirmar envía confirmaPisarCosto: true', async () => {
        await crearComponente({ ...costoAMano(), articulo: articuloConLista({ costo: 950, ajuste1: null, ajuste2: null }) });
        await escribir('ajuste1', '-5');
        await clicAjustes();

        el().querySelector<HTMLButtonElement>('app-confirmacion button.confirmar')!.click();
        await refrescar();

        expect(servicio.guardarAjustes).toHaveBeenCalledTimes(1);
        expect(servicio.guardarAjustes).toHaveBeenCalledWith(
          5,
          expect.objectContaining({ ajuste1: -5, confirmaPisarCosto: true }),
        );
        expect(el().querySelector('app-confirmacion')).toBeNull();
      });

      it('cancelar no envía nada, cierra el aviso y conserva lo escrito', async () => {
        await crearComponente({ ...costoAMano(), articulo: articuloConLista({ costo: 950, ajuste1: null, ajuste2: null }) });
        await escribir('ajuste1', '-5');
        await clicAjustes();

        el().querySelector<HTMLButtonElement>('app-confirmacion button.cancelar')!.click();
        await refrescar();

        expect(servicio.guardarAjustes).not.toHaveBeenCalled();
        expect(el().querySelector('app-confirmacion')).toBeNull();
        expect(input('ajuste1').value).toBe('-5');
      });

      it('en dólares los importes llevan «US$»', async () => {
        await crearComponente({
          id: '5',
          articulo: articuloConLista({ moneda: 'USD', costo: 9.5, ajuste1: null, ajuste2: null }),
          desglose: desgloseDe({
            moneda: 'USD',
            precioLista: 10,
            bonificacion1: 10,
            ajuste1: null,
            ajuste2: null,
            costoDeLista: 9,
            costoGuardado: 9.5,
            coincide: false,
            cotizacion: 1000,
          }),
        });
        await escribir('ajuste1', '-10');

        await clicAjustes();

        expect(limpio(el().querySelector('app-confirmacion p')?.textContent)).toBe(
          'El costo guardado (US$ 9,50) no coincide con el que sale de la lista (US$ 9,00). Si guardás, el costo pasa a US$ 8,10.',
        );
      });

      it('si el costo no cambia (el nuevo es igual al guardado) no hay aviso aunque no coincida con la lista', async () => {
        // Guardado 855, la lista hoy da 900 y el ajuste -5 lleva el costo a 855: no hay nada que pisar.
        await crearComponente({
          id: '5',
          articulo: articuloConLista({ costo: 855, ajuste1: null, ajuste2: null }),
          desglose: desgloseDe({
            bonificacion1: 10,
            ajuste1: null,
            ajuste2: null,
            costoDeLista: 900,
            costoGuardado: 855,
            coincide: false,
            precioFinal: 1350,
          }),
        });
        await escribir('ajuste1', '-5');

        await clicAjustes();

        expect(el().querySelector('app-confirmacion')).toBeNull();
        expect(servicio.guardarAjustes).toHaveBeenCalledWith(5, expect.objectContaining({ confirmaPisarCosto: false }));
      });
    });

    describe('sin lista (R-213, decisión 46)', () => {
      it('un artículo de alta manual tiene los campos de ajuste deshabilitados y el texto del servidor', async () => {
        await crearComponente({ id: '5', articulo: articuloGuardado(), desglose: desgloseSinLista() });

        for (const id of ['ajuste1', 'ajuste2', 'ajuste3']) expect(input(id).disabled).toBe(true);
        expect(limpio(el().querySelector('#motivo-sin-lista')?.textContent)).toBe(MOTIVO_SIN_LISTA);
        expect(botonAjustes().disabled).toBe(true);
      });

      it('con precio de lista pero sin proveedor se ve igual', async () => {
        await crearComponente({
          id: '5',
          articulo: articuloConLista({ proveedorId: null }),
          desglose: desgloseSinLista({ costoGuardado: 880.65 }),
        });

        for (const id of ['ajuste1', 'ajuste2', 'ajuste3']) expect(input(id).disabled).toBe(true);
        expect(limpio(el().querySelector('#motivo-sin-lista')?.textContent)).toBe(MOTIVO_SIN_LISTA);
      });

      it('no se puede guardar ajustes aunque se llame a la acción directamente', async () => {
        await crearComponente({ id: '5', articulo: articuloGuardado(), desglose: desgloseSinLista() });

        (fixture.componentInstance as unknown as { guardarAjustes: () => void }).guardarAjustes();
        await refrescar();

        expect(servicio.guardarAjustes).not.toHaveBeenCalled();
      });

      it('la cadena arranca en el costo', async () => {
        await crearComponente({ id: '5', articulo: articuloGuardado(), desglose: desgloseSinLista({ costoGuardado: 500 }) });

        expect(importes()[0]).toBe('$ 500,00');
      });
    });

    describe('Vendedor (decisión 44)', () => {
      it('ve el desglose sin campos de ajuste ni botón para guardarlos', async () => {
        await crearComponente({ ...conLista(), dueno: false });

        expect(importes().at(-1)).toBe('$ 1.390,00');
        expect(el().querySelector('#ajuste1')).toBeNull();
        expect(el().querySelector('button.guardar-ajustes')).toBeNull();
      });

      it('no puede guardar ajustes aunque llegue el método', async () => {
        await crearComponente({ ...conLista(), dueno: false });

        (fixture.componentInstance as unknown as { guardarAjustes: () => void }).guardarAjustes();
        await refrescar();

        expect(servicio.guardarAjustes).not.toHaveBeenCalled();
      });
    });

    describe('accesibilidad', () => {
      it('cada campo de ajuste tiene su etiqueta y la pista enlazada', async () => {
        await crearComponente(conLista());

        for (const [id, rotulo] of [['ajuste1', 'Ajuste 1 (%)'], ['ajuste2', 'Ajuste 2 (%)'], ['ajuste3', 'Ajuste 3 (%)']]) {
          expect(limpio(el().querySelector(`label[for="${id}"]`)?.textContent)).toBe(rotulo);
          expect(input(id).getAttribute('aria-describedby')).toContain('ajustes-pista');
        }
      });
    });
  });
  describe('versión del artículo y aviso del 409 (R-224, R-225)', () => {
    const MENSAJE_VERSION = 'Otro cambio modificó este artículo. Recargá y volvé a intentar.';
    const limpio = (texto: string | null | undefined) => (texto ?? '').replace(/[\s ]+/g, ' ').trim();
    const aviso = () => el().querySelector('[data-foco-ajustes]');
    const botonRecargar = () => el().querySelector<HTMLButtonElement>('button.recargar');
    const botonAjustes = () => el().querySelector<HTMLButtonElement>('button.guardar-ajustes')!;

    const conVersion = (version: number, cambios: Partial<Articulo> = {}) => ({
      id: '5',
      articulo: articuloConLista({ version, ...cambios }),
      desglose: desgloseDe(),
    });

    describe('lo que se manda', () => {
      it('el PUT del artículo manda la versión que se cargó', async () => {
        await crearComponente(conVersion(3));
        await escribir('descripcion', 'Tornillo nuevo');

        await enviar();

        expect(servicio.actualizar.mock.calls[0][1]).toEqual(expect.objectContaining({ version: 3 }));
      });

      it('el alta no manda versión', async () => {
        await crearComponente();
        await completarMinimos();

        await enviar();

        expect(servicio.crear.mock.calls[0][0]).not.toHaveProperty('version');
      });

      it('el PUT de ajustes manda la versión junto con confirmaPisarCosto', async () => {
        await crearComponente(conVersion(3));
        await escribir('ajuste2', '');

        botonAjustes().click();
        await refrescar();

        expect(servicio.guardarAjustes).toHaveBeenCalledWith(5, {
          ajuste1: -5,
          ajuste2: null,
          ajuste3: null,
          confirmaPisarCosto: false,
          version: 3,
        });
      });

      it('confirmar el aviso de pisar el costo también manda la versión', async () => {
        await crearComponente({
          id: '5',
          articulo: articuloConLista({ version: 7, costo: 950, ajuste1: null, ajuste2: null }),
          desglose: desgloseDe({ ajuste1: null, ajuste2: null, costoDeLista: 900, costoGuardado: 950, coincide: false }),
        });
        await escribir('ajuste1', '-5');
        botonAjustes().click();
        await refrescar();

        el().querySelector<HTMLButtonElement>('app-confirmacion button.confirmar')!.click();
        await refrescar();

        expect(servicio.guardarAjustes.mock.calls[0][1]).toEqual(
          expect.objectContaining({ confirmaPisarCosto: true, version: 7 }),
        );
      });

      it('un artículo sin versión (servidor viejo) no manda un campo inventado', async () => {
        await crearComponente({ id: '5', articulo: articuloConLista(), desglose: desgloseDe() });
        await escribir('descripcion', 'Otra');

        await enviar();

        expect(servicio.actualizar.mock.calls[0][1].version).toBeUndefined();
      });
    });

    describe('la versión de cada respuesta', () => {
      it('después de guardar el artículo toma la versión de la respuesta: dos guardados seguidos no dan 409', async () => {
        await crearComponente(conVersion(3));
        servicio.actualizar.mockReturnValue(of(articuloConLista({ version: 4 })));
        await escribir('descripcion', 'Primero');
        await enviar();
        await escribir('descripcion', 'Segundo');

        await enviar();

        expect(servicio.actualizar.mock.calls.map(([, pedido]) => pedido.version)).toEqual([3, 4]);
      });

      it('dos guardados de ajustes seguidos, sin recargar la página, mandan la versión nueva', async () => {
        await crearComponente(conVersion(3));
        const primero = articuloConLista({ version: 4, ajuste2: null });
        servicio.guardarAjustes.mockReturnValue(of(primero));
        servicio.obtener.mockReturnValue(of(primero));
        servicio.desglose.mockReturnValue(of(desgloseDe({ ajuste2: null })));
        await escribir('ajuste2', '');
        botonAjustes().click();
        await refrescar();

        await escribir('ajuste3', '2');
        botonAjustes().click();
        await refrescar();

        expect(servicio.guardarAjustes.mock.calls.map(([, pedido]) => pedido.version)).toEqual([3, 4]);
      });

      it('si el PUT de ajustes responde y la relectura falla, usa la respuesta: versión, costo, ajustes y bloquea guardar hasta «Recargar»', async () => {
        await crearComponente(conVersion(3));
        servicio.guardarAjustes.mockReturnValue(
          of(articuloConLista({ version: 4, ajuste2: null, costo: 855, precioFinal: 1350 })),
        );
        servicio.obtener.mockReturnValue(throwError(() => errorHttp(500, 'Error interno')));
        await escribir('ajuste2', '');
        botonAjustes().click();
        await refrescar();

        // El costo del formulario es el de la respuesta, no el viejo.
        expect(input('costo').value).toBe('855');
        expect(input('ajuste2').value).toBe('');
        expect(limpio(el().querySelector('[data-foco-ajustes]')?.textContent)).toContain('Se guardaron los ajustes');
        expect(el().querySelector('button.recargar')).not.toBeNull();

        // Guardar el artículo queda bloqueado: no manda nada hasta recargar.
        await escribir('descripcion', 'Otra');
        await enviar();
        expect(servicio.actualizar).not.toHaveBeenCalled();

        // Los ajustes siguientes usan la versión de la respuesta.
        await escribir('ajuste3', '2');
        botonAjustes().click();
        await refrescar();
        expect(servicio.guardarAjustes.mock.calls[1][1].version).toBe(4);
      });

      it('si falla solo la relectura del desglose, guardar() queda bloqueado y «Recargar» lo libera', async () => {
        await crearComponente(conVersion(3));
        const respuesta = articuloConLista({ version: 4, ajuste2: null, costo: 855, precioFinal: 1350 });
        servicio.guardarAjustes.mockReturnValue(of(respuesta));
        servicio.obtener.mockReturnValue(of(respuesta));
        servicio.desglose.mockReturnValue(throwError(() => errorHttp(500, 'Error interno')));
        await escribir('ajuste2', '');
        botonAjustes().click();
        await refrescar();

        expect(input('costo').value).toBe('855');
        await enviar();
        expect(servicio.actualizar).not.toHaveBeenCalled();

        servicio.desglose.mockReturnValue(of(desgloseDe({ ajuste2: null, costoGuardado: 855, costoDeLista: 855, precioFinal: 1350 })));
        el().querySelector<HTMLButtonElement>('button.recargar')!.click();
        await refrescar();
        await enviar();

        expect(servicio.actualizar).toHaveBeenCalledTimes(1);
        expect(servicio.actualizar.mock.calls[0][1]).toEqual(expect.objectContaining({ costo: 855, version: 4 }));
      });
    });

    describe('el 409 de versión', () => {
      it('en el PUT del artículo muestra el aviso solo con el mensaje, no como errores de campo', async () => {
        await crearComponente(conVersion(3));
        servicio.actualizar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE_VERSION)));
        await escribir('descripcion', 'Mi cambio');

        await enviar();

        expect(limpio(aviso()?.textContent)).toBe(MENSAJE_VERSION);
        expect(el().querySelector('.errores-campos')).toBeNull();
        expect(navigate).not.toHaveBeenCalled();
      });

      it('conserva lo escrito y ofrece «Recargar»', async () => {
        await crearComponente(conVersion(3));
        servicio.actualizar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE_VERSION)));
        await escribir('descripcion', 'Mi cambio');

        await enviar();

        expect(input('descripcion').value).toBe('Mi cambio');
        expect(limpio(botonRecargar()?.textContent)).toBe('Recargar');
      });

      it('«Recargar» vuelve a pedir el artículo y el desglose y deja el formulario con lo del servidor', async () => {
        await crearComponente(conVersion(3));
        servicio.actualizar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE_VERSION)));
        await escribir('descripcion', 'Mi cambio');
        await enviar();
        const nuevo = articuloConLista({ version: 6, descripcion: 'Lo que dejó la importación', costo: 990, precioFinal: 1560 });
        servicio.obtener.mockReturnValue(of(nuevo));
        servicio.desglose.mockReturnValue(of(desgloseDe({ costoGuardado: 990, coincide: false, precioFinal: 1560 })));

        botonRecargar()!.click();
        await refrescar();

        expect(servicio.obtener).toHaveBeenCalledTimes(2);
        expect(servicio.desglose).toHaveBeenCalledTimes(2);
        expect(input('descripcion').value).toBe('Lo que dejó la importación');
        expect(input('costo').value).toBe('990');
        expect(botonRecargar()).toBeNull();
        expect(limpio(aviso()?.textContent)).toBe('Se recargó el artículo con los últimos cambios.');
        // y el próximo guardado ya manda la versión nueva
        await escribir('descripcion', 'Ahora sí');
        await enviar();
        expect(servicio.actualizar.mock.calls.at(-1)![1].version).toBe(6);
      });

      it('en el PUT de ajustes también: aviso, lo escrito se conserva y «Recargar»', async () => {
        await crearComponente(conVersion(3));
        servicio.guardarAjustes.mockReturnValue(throwError(() => errorHttp(409, MENSAJE_VERSION)));
        await escribir('ajuste2', '7');

        botonAjustes().click();
        await refrescar();

        expect(limpio(aviso()?.textContent)).toBe(MENSAJE_VERSION);
        expect(input('ajuste2').value).toBe('7');
        expect(botonRecargar()).not.toBeNull();
      });

      it('«Recargar» después de un 409 de ajustes trae los ajustes del servidor', async () => {
        await crearComponente(conVersion(3));
        servicio.guardarAjustes.mockReturnValue(throwError(() => errorHttp(409, MENSAJE_VERSION)));
        await escribir('ajuste2', '7');
        botonAjustes().click();
        await refrescar();
        const nuevo = articuloConLista({ version: 5, ajuste1: null, ajuste2: 10 });
        servicio.obtener.mockReturnValue(of(nuevo));
        servicio.desglose.mockReturnValue(of(desgloseDe({ ajuste1: null, ajuste2: 10 })));

        botonRecargar()!.click();
        await refrescar();

        expect(input('ajuste1').value).toBe('');
        expect(input('ajuste2').value).toBe('10');
      });

      it('si «Recargar» falla lo dice y deja el aviso para reintentar', async () => {
        await crearComponente(conVersion(3));
        servicio.actualizar.mockReturnValue(throwError(() => errorHttp(409, MENSAJE_VERSION)));
        await enviar();
        servicio.obtener.mockReturnValue(throwError(() => errorHttp(500, 'Error interno')));

        botonRecargar()!.click();
        await refrescar();

        expect(limpio(aviso()?.textContent)).toBe('No se pudo recargar el artículo.');
        expect(botonRecargar()).not.toBeNull();
      });

      it('otro 409 (códigos repetidos, sin cotización) no ofrece «Recargar»', async () => {
        await crearComponente(conVersion(3));
        servicio.actualizar.mockReturnValue(throwError(() => errorHttp(409, 'Ya existe un artículo activo con el código TOR-001.')));

        await enviar();

        expect(botonRecargar()).toBeNull();
      });

      it('el aviso se anuncia: role alert y recibe el foco', async () => {
        await crearComponente(conVersion(3));
        servicio.guardarAjustes.mockReturnValue(throwError(() => errorHttp(409, MENSAJE_VERSION)));
        await escribir('ajuste2', '7');

        botonAjustes().click();
        await refrescar();

        expect(aviso()?.getAttribute('role')).toBe('alert');
        expect(document.activeElement).toBe(aviso());
      });

      it('el Vendedor no tiene nada que recargar', async () => {
        await crearComponente({ ...conVersion(3), dueno: false });

        expect(botonRecargar()).toBeNull();
      });
    });
  });
  describe('arreglos de la revisión del lote 6', () => {
    const limpio = (texto: string | null | undefined) => (texto ?? '').replace(/[\s ]+/g, ' ').trim();
    const botonAjustes = () => el().querySelector<HTMLButtonElement>('button.guardar-ajustes')!;
    const botonRecargar = () => el().querySelector<HTMLButtonElement>('button.recargar');
    const conLista = (cambios: Partial<Articulo> = {}) => ({
      id: '5',
      articulo: articuloConLista({ version: 3, ...cambios }),
      desglose: desgloseDe(),
    });
    const componente = () =>
      fixture.componentInstance as unknown as { ajustes: { controls: Record<string, { setValue(v: number | null): void }> } };

    describe('reactivar toma la versión nueva', () => {
      it('después de reactivar, el próximo guardado manda la versión que devolvió el servidor', async () => {
        await crearComponente({ id: '5', articulo: articuloGuardado({ activo: false, version: 3 }) });
        servicio.reactivar.mockReturnValue(of(articuloGuardado({ activo: true, version: 4 })));

        el().querySelector<HTMLButtonElement>('app-barra-acciones button.reactivar')!.click();
        await refrescar();
        await escribir('descripcion', 'Reactivado y editado');
        await enviar();

        expect(servicio.actualizar.mock.calls[0][1]).toEqual(expect.objectContaining({ version: 4, activo: true }));
      });
    });

    describe('el precio en vivo de arriba sigue a los ajustes escritos (R-218)', () => {
      it('sin ajustes cambiados muestra el precio del costo del formulario', async () => {
        await crearComponente(conLista());

        expect(limpio(el().querySelector('.precio-en-vivo strong')?.textContent)).toBe('$ 1.390,00');
        expect(el().querySelector('.costo-en-vivo')).toBeNull();
      });

      it('con un ajuste escrito muestra el costo y el precio de la cadena: 855,00 y $ 1.350,00', async () => {
        await crearComponente(conLista());

        await escribir('ajuste2', '');

        expect(limpio(el().querySelector('.precio-en-vivo strong')?.textContent)).toBe('$ 1.350,00');
        expect(limpio(el().querySelector('.costo-en-vivo')?.textContent)).toContain('$ 855,00');
      });
    });

    describe('el costo del formulario no se pisa si está sucio', () => {
      it('con el campo costo editado, guardar ajustes no lo cambia', async () => {
        await crearComponente(conLista());
        await escribir('costo', '500');
        await escribir('ajuste2', '');
        const guardado = articuloConLista({ costo: 855, ajuste2: null, version: 4 });
        servicio.guardarAjustes.mockReturnValue(of(guardado));
        servicio.obtener.mockReturnValue(of(guardado));

        botonAjustes().click();
        await refrescar();

        expect(input('costo').value).toBe('500');
      });
    });

    describe('«Guardar ajustes» apagado dice por qué', () => {
      const motivo = () => limpio(el().querySelector('#motivo-ajustes')?.textContent);

      it('sin cambios: «Cambiá un ajuste para guardar», enlazado al botón', async () => {
        await crearComponente(conLista());

        expect(motivo()).toBe('Cambiá un ajuste para guardar');
        expect(botonAjustes().getAttribute('aria-describedby')).toContain('motivo-ajustes');
      });

      it('con un ajuste inválido: «Corregí el ajuste N»', async () => {
        await crearComponente(conLista());

        await escribir('ajuste2', '-100');

        expect(motivo()).toBe('Corregí el ajuste 2');
      });

      it('con un cambio válido el motivo desaparece', async () => {
        await crearComponente(conLista());

        await escribir('ajuste2', '');

        expect(el().querySelector('#motivo-ajustes')).toBeNull();
        expect(botonAjustes().hasAttribute('aria-describedby')).toBe(false);
      });

      it('sin lista conserva el motivo del servidor y no suma otro', async () => {
        await crearComponente({ id: '5', articulo: articuloGuardado(), desglose: desgloseSinLista() });

        expect(el().querySelector('#motivo-ajustes')).toBeNull();
        expect(botonAjustes().getAttribute('aria-describedby')).toBe('motivo-sin-lista');
      });
    });

    describe('«Recargar» también para el 400 de versión faltante', () => {
      it('se ofrece cuando el servidor dice que falta la versión', async () => {
        await crearComponente(conLista());
        servicio.actualizar.mockReturnValue(
          throwError(() => errorHttp(400, 'Falta la versión del artículo. Recargá y volvé a intentar.')),
        );

        await enviar();

        expect(botonRecargar()).not.toBeNull();
      });
    });

    describe('el aviso de pisar congela los ajustes', () => {
      it('lo que se envía al confirmar es lo que se vio en el aviso, aunque el campo cambie mientras tanto', async () => {
        await crearComponente({
          id: '5',
          articulo: articuloConLista({ version: 3, costo: 950, ajuste1: null, ajuste2: null }),
          desglose: desgloseDe({ ajuste1: null, ajuste2: null, costoDeLista: 900, costoGuardado: 950, coincide: false }),
        });
        await escribir('ajuste1', '-5');
        botonAjustes().click();
        await refrescar();
        expect(el().querySelector('app-confirmacion')).not.toBeNull();

        componente().ajustes.controls['ajuste1'].setValue(-7);
        el().querySelector<HTMLButtonElement>('app-confirmacion button.confirmar')!.click();
        await refrescar();

        expect(servicio.guardarAjustes.mock.calls[0][1]).toEqual(
          expect.objectContaining({ ajuste1: -5, confirmaPisarCosto: true }),
        );
      });
    });

    describe('no se guarda ni se recarga dos veces a la vez', () => {
      it('un segundo envío mientras guarda no repite el PUT', async () => {
        await crearComponente(conLista());
        servicio.actualizar.mockReturnValue(new Subject<Articulo>());

        await enviar();
        await enviar();

        expect(servicio.actualizar).toHaveBeenCalledTimes(1);
      });

      it('«Recargar» mientras se guardan ajustes no hace nada', async () => {
        await crearComponente(conLista());
        servicio.guardarAjustes.mockReturnValue(new Subject<Articulo>());
        await escribir('ajuste2', '');
        botonAjustes().click();
        await refrescar();
        servicio.obtener.mockClear();

        (fixture.componentInstance as unknown as { recargar: () => void }).recargar();

        expect(servicio.obtener).not.toHaveBeenCalled();
      });
    });
  });
});
