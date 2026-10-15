import { registerLocaleData } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { Rubro } from '../rubros/rubro.models';
import { RubrosService } from '../rubros/rubros.service';
import { Articulo } from './articulo.models';
import { ArticuloForm } from './articulo-form';
import { ArticulosService } from './articulos.service';

registerLocaleData(localeEsAr);

const NBSP = '\u00a0';

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

  let fixture: ComponentFixture<ArticuloForm>;
  let servicio: {
    obtener: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    actualizar: ReturnType<typeof vi.fn>;
  };
  let navigate: ReturnType<typeof vi.spyOn>;

  const errorHttp = (status: number, message: string) =>
    new HttpErrorResponse({ status, error: { status, message } });

  async function crearComponente(
    opciones: { dueno?: boolean; id?: string | null; articulo?: Articulo | HttpErrorResponse } = {},
  ) {
    const { dueno = true, id = null, articulo = articuloGuardado() } = opciones;
    servicio = {
      obtener: vi.fn(() =>
        articulo instanceof HttpErrorResponse ? throwError(() => articulo) : of(articulo),
      ),
      crear: vi.fn(() => of(articuloGuardado())),
      actualizar: vi.fn(() => of(articuloGuardado())),
    };
    await TestBed.configureTestingModule({
      imports: [ArticuloForm],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ArticulosService, useValue: servicio },
        { provide: RubrosService, useValue: { listar: vi.fn(() => of(rubros)) } },
        { provide: ProveedoresService, useValue: { listar: vi.fn(() => of(proveedores)) } },
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

      expect(el().querySelector('h1')?.textContent).toContain('Nuevo artículo');
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

      expect(navigate).toHaveBeenCalledWith(['/articulos']);
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
      expect(navigate).toHaveBeenCalledWith(['/articulos']);
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
      expect(el().querySelector('h1')?.textContent).toContain('Editar artículo');
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

      expect(el().querySelector('.stock-actual')?.textContent).toContain('12,5');
      expect(el().querySelector('.stock-actual')?.textContent).toContain('Metro');
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
        margenPorcentaje: 35,
        alicuotaIva: 'IVA_10_5',
        unidad: 'METRO',
        stockMinimo: 3,
        ubicacion: 'Pasillo 2',
        activo: true,
      });
      expect(enviado).not.toHaveProperty('stockActual');
      expect(enviado).not.toHaveProperty('precioFinal');
      expect(navigate).toHaveBeenCalledWith(['/articulos']);
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

      expect(el().querySelector('h1')?.textContent).toContain('Artículo');
      expect(el().querySelector('h1')?.textContent).not.toContain('Editar');
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
});
