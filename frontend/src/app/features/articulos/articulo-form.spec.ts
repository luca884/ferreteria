import { registerLocaleData } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
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
import { Articulo } from './articulo.models';
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

  let fixture: ComponentFixture<ArticuloForm>;
  let servicio: {
    obtener: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    actualizar: ReturnType<typeof vi.fn>;
    reactivar: ReturnType<typeof vi.fn>;
    ajustarStock: ReturnType<typeof vi.fn>;
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
      sinConfiguracion?: boolean;
      /** Lo que responde GET /cotizacion; 'falla' si no se puede leer. */
      cotizacion?: Cotizacion | 'falla';
    } = {},
  ) {
    const {
      dueno = true,
      id = null,
      articulo = articuloGuardado(),
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
});
