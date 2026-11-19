import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BehaviorSubject, of } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { ImportacionesService } from '../importaciones/importaciones.service';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { RubrosService } from '../rubros/rubros.service';
import { ActualizacionMasiva } from './actualizacion-masiva';
import { CambioMasivo, VistaPreviaMasiva } from './actualizacion-masiva.models';

registerLocaleData(localeEsAr);
const ruta = '/api/actualizacion-masiva';
const cambio: CambioMasivo = {
  articuloId: 42, codigo: 'TOR-42', descripcion: 'Tornillo', costoAnterior: 100, costoNuevo: 112.5,
  margenAnterior: 30, margenNuevo: 30, margenHeredado: true, ivaPorcentaje: 21,
  precioAnterior: 157.3, precioNuevo: 176.96, advertencia: null,
};
const previa: VistaPreviaMasiva = {
  conteos: { total: 1, suben: 1, bajan: 0, sinCambioDePrecio: 0, conError: 0, pasanAMargenPropio: 0,
    conAdvertencia: 0, modificados: 1, yaEnElDestino: 0 },
  cambios: [cambio], errores: [], detalleRecortado: false, huella: 'plan-42',
};
const resultado = { conteos: previa.conteos, cambios: [cambio], entradasDeHistorial: 1, detalleRecortado: false };
const operaciones = [
  ['Cambiar el costo en %', '12,50', '"modo":"COSTO_PORCENTAJE","valor":12.5'],
  ['Fijar el margen', '35,25', '"modo":"MARGEN","valor":35.25'],
  ['Sumar o restar un monto al costo', '-12,50', '"modo":"COSTO_MONTO","valor":-12.5'],
  ['Cambiar el precio final en %', '-3,75', '"modo":"PRECIO_PORCENTAJE","valor":-3.75'],
  ['Sumar o restar un monto al precio final', '123,45', '"modo":"PRECIO_MONTO","valor":123.45'],
  ['Cambiar el rubro', null, '"modo":"CAMBIAR_RUBRO","rubroDestinoId":9'],
] as const;

describe.each([{ nombre: 'celular', ancho: 390 }, { nombre: 'tablet', ancho: 768 }])('$nombre: actualización masiva', ({ ancho }) => {
  let fixture: ComponentFixture<ActualizacionMasiva>;
  let http: HttpTestingController;
  const rubros = [{ id: 8, nombre: 'Origen' }, { id: 9, nombre: 'Destino' }];
  let listaRubros: BehaviorSubject<typeof rubros>;
  const cargarRubros = vi.fn(() => listaRubros);
  const el = () => fixture.nativeElement as HTMLElement;
  const buscar = <T extends HTMLElement = HTMLElement>(selector: string) => el().querySelector<T>(selector)!;
  const texto = (selector: string) => buscar(selector).textContent?.replace(/\s+/g, ' ').trim();
  const render = async () => { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
  const pulsar = async (selector: string) => { buscar(selector).click(); await render(); };
  const escribir = async (selector: string, valor: string) => {
    const input = buscar<HTMLInputElement>(selector);
    input.value = valor;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await render();
  };
  const elegir = async (selector: string, nombre: string) => {
    const select = buscar<HTMLSelectElement>(selector);
    select.value = Array.from(select.options).find((o) => o.textContent?.trim() === nombre)!.value;
    select.dispatchEvent(new Event('change'));
    await render();
  };
  // La misma acción de usuario funciona sobre el select de la base y sobre la hoja del celular.
  const operacion = async (nombre: string) => {
    if (buscar('select#modo')) return elegir('#modo', nombre);
    await pulsar('#modo');
    const fila = Array.from(el().querySelectorAll<HTMLElement>('.opcion-masiva')).find((b) => b.querySelector('strong')?.textContent === nombre)!;
    fila.click();
    await render();
  };
  const pedido = (url: string) => http.expectOne({ url, method: 'POST' });
  const sinPedidos = () => http.expectNone((r) => r.method === 'POST');
  const preparar = async () => {
    await escribir('#q', 'TOR-42');
    await operacion('Cambiar el costo en %');
    await escribir('#valor', '12,50');
  };
  const previsualizar = async (respuesta = previa) => {
    await pulsar('.generar');
    pedido(`${ruta}/vista-previa`).flush(respuesta);
    await render();
  };

  beforeEach(async () => {
    vi.stubGlobal('matchMedia', vi.fn((query: string) => ({ matches: ancho < 640 && query.includes('639.98') })));
    listaRubros = new BehaviorSubject(rubros);
    cargarRubros.mockClear();
    await TestBed.configureTestingModule({ imports: [ActualizacionMasiva], providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: LOCALE_ID, useValue: 'es-AR' },
      { provide: AuthService, useValue: { esDueno: () => true } },
      { provide: ProveedoresService, useValue: { listar: () => of([{ id: 7, nombre: 'Proveedor' }]) } },
      { provide: RubrosService, useValue: { listar: cargarRubros } },
      { provide: ImportacionesService, useValue: { listar: () => of([{ id: 6, proveedorId: 7, proveedorNombre: 'Proveedor', archivo: 'lista.csv', fecha: '2026-11-10T12:00:00Z' }]) } },
    ] }).compileComponents();
    fixture = TestBed.createComponent(ActualizacionMasiva);
    http = TestBed.inject(HttpTestingController);
    await render();
  });
  afterEach(() => { http.verify(); vi.unstubAllGlobals(); });

  it.each(operaciones)('manda los JSON exactos al elegir «%s»', async (nombre, valor, cambioJson) => {
    await elegir('#proveedor', 'Proveedor');
    await elegir('#rubro', 'Origen');
    buscar<HTMLSelectElement>('#importacion').selectedIndex = 1;
    buscar('#importacion').dispatchEvent(new Event('change'));
    await escribir('#q', ' TOR-42 ');
    await pulsar('#incluirInactivos');
    await operacion(nombre);
    if (valor === null) {
      await elegir('#rubroDestino', 'Destino');
      expect(texto('#rubro-destino-pista')).toBe('El precio se recalcula con el margen del rubro destino. Los de margen propio o precio manual cambian de rubro, pero no de precio.');
    } else {
      await escribir('#valor', valor);
      expect(buscar('app-campo-moneda').textContent?.includes('$')).toBe(nombre === 'Sumar o restar un monto al precio final');
    }
    const json = `{"proveedorId":7,"rubroId":8,"importacionId":6,"q":"TOR-42","incluirInactivos":true,${cambioJson}`;
    await pulsar('.generar');
    const p = pedido(`${ruta}/vista-previa`);
    expect(p.request.serializeBody()).toBe(`${json}}`);
    p.flush(previa);
    await render();
    await pulsar('.confirmar');
    expect(texto('.confirmar-si')).toBe('Sí, actualizar');
    sinPedidos();
    await pulsar('.confirmar-si');
    const a = pedido(ruta);
    expect(a.request.serializeBody()).toBe(`${json},"huella":"plan-42"}`);
    a.flush(resultado);
    await render();
    expect(buscar<HTMLInputElement>('#q').value).toBe(' TOR-42 ');
    expect(buscar<HTMLInputElement>('#valor').value).toBe('');
    expect(texto('#motivo-vista-previa')).toBe('Para ver la vista previa, elegí la operación.');
    expect(buscar('.vista-previa')).toBeNull();
  });

  it('aunque se incluya la baja, sin filtros el doble clic solo abre la confirmación de todo', async () => {
    await operacion('Cambiar el costo en %');
    await escribir('#valor', '12,5');
    await pulsar('#incluirInactivos');
    await previsualizar();
    buscar('.confirmar').click();
    await pulsar('.confirmar');
    sinPedidos();
    expect(texto('#detalle-confirmacion')).toContain('No elegiste ningún filtro: el cambio alcanza a todo el catálogo.');
    expect(texto('.confirmar-si')).toBe('Sí, modificar todo');
    expect(document.activeElement).toBe(buscar('.confirmacion'));
    await pulsar('.cancelar');
    expect(document.activeElement).toBe(buscar('.confirmar'));
    sinPedidos();
  });

  it('descarta la respuesta vieja, invalida una confirmación al editar y aplica solo el nuevo plan con su huella', async () => {
    await preparar();
    await pulsar('.generar');
    const vieja = pedido(`${ruta}/vista-previa`);
    await escribir('#q', 'TOR-43');
    vieja.flush(previa);
    await render();
    expect(buscar('.vista-previa')).toBeNull();
    await previsualizar();
    await pulsar('.confirmar');
    const si = buscar('.confirmar-si');
    await escribir('#q', 'TOR-44');
    expect(buscar('.vista-previa')).toBeNull();
    expect(buscar('.confirmacion')).toBeNull();
    si.click();
    sinPedidos();
    await elegir('#rubro', 'Origen');
    await previsualizar({ ...previa, huella: 'plan-44' });
    await pulsar('.confirmar');
    // Si llega una lista de selectores nueva, el rubro ausente se suelta sin emitir valueChanges.
    listaRubros.next([rubros[1]]);
    await render();
    expect(buscar<HTMLSelectElement>('#rubro').selectedIndex).toBe(0);
    await pulsar('.confirmar-si');
    const a = pedido(ruta);
    expect(a.request.serializeBody()).toBe('{"proveedorId":null,"rubroId":8,"importacionId":null,"q":"TOR-44","incluirInactivos":false,"modo":"COSTO_PORCENTAJE","valor":12.5,"huella":"plan-44"}');
    a.flush(resultado);
    await render();
  });

  it('un rechazo 404 conserva el formulario, recarga selectores y devuelve el foco', async () => {
    await preparar();
    await previsualizar();
    await pulsar('.confirmar');
    await pulsar('.confirmar-si');
    pedido(ruta).flush({ message: 'Ya no existe' }, { status: 404, statusText: 'Not Found' });
    await render();
    expect(texto('[role="alert"]')).toBe('Ya no existe');
    expect(cargarRubros).toHaveBeenCalledTimes(2);
    expect(buscar<HTMLInputElement>('#q').value).toBe('TOR-42');
    expect(buscar<HTMLInputElement>('#valor').value).toBe('12,50');
    expect(buscar('.vista-previa')).toBeNull();
    expect(buscar('#motivo-vista-previa')).toBeNull();
    expect(document.activeElement).toBe(buscar('.generar'));
    sinPedidos();
  });

  it('un doble toque en Sí manda un solo pedido y no se reabre mientras aplica', async () => {
    await preparar();
    await previsualizar();
    await pulsar('.confirmar');
    buscar('.confirmar-si').click();
    await pulsar('.confirmar-si');
    const a = pedido(ruta);
    const actualizar = buscar<HTMLButtonElement>('.confirmar');
    expect(actualizar.disabled).toBe(true);
    // Aunque llegue una activación ya encolada, la guarda también vale en el manejador.
    actualizar.dispatchEvent(new MouseEvent('click'));
    await render();
    expect(buscar('.confirmacion')).toBeNull();
    sinPedidos();
    a.flush(resultado);
    await render();
  });

  it('explica operación, destino, valor, rangos y cero cambios sin enviar pedidos', async () => {
    const motivo = async (esperado: string) => {
      expect(texto('#motivo-vista-previa')).toBe(esperado);
      expect(buscar('.generar').getAttribute('aria-disabled')).toBe('true');
      await pulsar('.generar');
      sinPedidos();
    };
    await motivo('Para ver la vista previa, elegí la operación.');
    await operacion('Cambiar el rubro');
    await motivo('Para ver la vista previa, elegí el rubro destino.');
    await operacion('Fijar el margen');
    await motivo('Para ver la vista previa, escribí el margen.');
    await escribir('#valor', '-1');
    await motivo('Para ver la vista previa, corregí el margen.');
    expect(texto('#valor-error')).toBe('El margen tiene que estar entre 0 y 1.000.');
    await operacion('Sumar o restar un monto al costo');
    await escribir('#valor', '0');
    await motivo('Para ver la vista previa, corregí el monto.');
    expect(texto('#valor-error')).toBe('El monto no puede ser cero.');
    await operacion('Cambiar el costo en %');
    await escribir('#valor', '-100');
    await motivo('Para ver la vista previa, corregí el porcentaje.');
    expect(texto('#valor-error')).toBe('El porcentaje tiene que estar entre -99,99 y 1.000.');
    await escribir('#valor', '5');
    await previsualizar({ ...previa, conteos: { ...previa.conteos, modificados: 0 } });
    expect(buscar('.confirmar').getAttribute('aria-disabled')).toBe('true');
    await pulsar('.confirmar');
    expect(texto('.motivo-accion')).toBe('Con esta lista no cambia ningún precio. Revisá el porcentaje o la lista elegida.');
    expect(buscar('.confirmacion')).toBeNull();
    sinPedidos();
  });

  it('cambiar la operación (hoja o select) con vista previa y confirmación abiertas las descarta y no aplica nada', async () => {
    await preparar();
    await previsualizar();
    await pulsar('.confirmar');
    expect(buscar('.confirmacion')).not.toBeNull();
    await operacion('Fijar el margen');
    expect(buscar('.vista-previa')).toBeNull();
    expect(buscar('.confirmacion')).toBeNull();
    sinPedidos();
    await previsualizar();
    expect(buscar('.confirmar')).not.toBeNull();
  });

  if (ancho < 640) {
    it('agrupa el formulario y deja el motivo junto al botón del pie', () => {
      expect(texto('form h2')).toBe('1 · Qué artículos');
      expect(texto('form h2.segundo')).toBe('2 · Qué cambio');
      expect(texto('.ficha-pie.en-pagina')).toBe('Ver vista previa Para ver la vista previa, elegí la operación.');
      expect(document.activeElement?.tagName).not.toBe('INPUT');
    });

    it('abre seis radios explicados y devuelve el foco al elegir o cerrar con Esc', async () => {
      expect(buscar('select#modo')).toBeNull();
      await pulsar('#modo');
      expect(document.activeElement).toBe(buscar('.hoja-inferior'));
      const filas = Array.from(el().querySelectorAll('.opcion-masiva'));
      expect(filas.map((fila) => fila.querySelector('strong')?.textContent)).toEqual(operaciones.map(([nombre]) => nombre));
      expect(filas.every((fila) => fila.querySelector('small')?.textContent && fila.querySelector('input[type="radio"]'))).toBe(true);
      await pulsar('.opcion-masiva:last-child input');
      expect(document.activeElement).toBe(buscar('#modo'));
      expect(buscar('#rubroDestino')).not.toBeNull();
      await pulsar('#modo');
      expect(buscar<HTMLInputElement>('.opcion-masiva:last-child input').checked).toBe(true);
      buscar('.hoja-inferior').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await render();
      expect(buscar('app-hoja-inferior')).toBeNull();
      expect(document.activeElement).toBe(buscar('#modo'));
    });

    it('muestra conteos, alerta de mezcla y una tarjeta con ambas cifras', async () => {
      await preparar();
      await previsualizar({ ...previa, enDolares: 1, advertenciaMezcla: 'Se aplicará en ambas monedas' });
      expect(texto('.tarjetas')).toContain('Artículos 1');
      expect(texto('.advertencia-mezcla')).toBe('Se aplicará en ambas monedas');
      expect(el().querySelectorAll('.cambio-celular')).toHaveLength(1);
      expect(texto('.cambio-celular')).toMatch(/Tornillo.*TOR-42.*Costo.*112,50.*Margen.*30.*176,96.*Antes.*157,30/i);
      expect(buscar('.vista-previa table.cambios')).toBeNull();
    });

    it('abre el resultado en pantalla completa, conserva el aviso y devuelve el foco a generar', async () => {
      await preparar();
      await previsualizar();
      await pulsar('.confirmar');
      expect(buscar('.confirmacion').hasAttribute('aria-modal')).toBe(false);
      await pulsar('.confirmar-si');
      pedido(ruta).flush({ ...resultado, detalleRecortado: true, cambios: Array.from({ length: 1000 }, (_, i) => ({ ...cambio, articuloId: i })) });
      await render();
      expect(buscar('app-dialogo-detalle')).toBeNull();
      expect(texto('app-detalle-pantalla h2')).toBe('Resultado de la actualización');
      expect(texto('app-detalle-pantalla h3')).toBe('Antes → ahora');
      expect(el().querySelectorAll('.resultado-articulo')).toHaveLength(1000);
      expect(texto('.resultado-recortado')).toBe('Se muestran solo los primeros 1.000 artículos.');
      expect(document.activeElement?.tagName).toBe('BUTTON');
      await pulsar('app-detalle-pantalla button[pie]');
      expect(buscar('app-detalle-pantalla')).toBeNull();
      expect(document.activeElement).toBe(buscar('.generar'));
    });
  }
});
