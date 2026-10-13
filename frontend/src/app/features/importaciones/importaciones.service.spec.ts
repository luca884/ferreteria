import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  AplicarRequest,
  Importacion,
  Muestra,
  VistaPrevia,
  VistaPreviaRequest,
} from './importacion.models';
import { ImportacionesService } from './importaciones.service';

describe('ImportacionesService', () => {
  let service: ImportacionesService;
  let http: HttpTestingController;

  const archivo = new File(['codigo;precio'], 'lista.csv', { type: 'text/csv' });
  const mapeo = {
    hoja: 0,
    filaDesde: 2,
    columnaCodigo: 0,
    columnaDescripcion: 1,
    columnaCosto: 2,
  };
  const datos: VistaPreviaRequest = {
    proveedorId: 7,
    mapeo,
    listaIncluyeIva: true,
    bonificacion1: 40,
    bonificacion2: 10,
    bonificacion3: null,
  };
  const conteos = { suben: 1, bajan: 2, nuevos: 3, sinCambio: 4, noEstan: 5, conError: 6 };

  async function leerDatos(body: FormData): Promise<unknown> {
    const parte = body.get('datos') as Blob;
    expect(parte.type).toBe('application/json');
    return JSON.parse(await blobATexto(parte));
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ImportacionesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('muestra: POST /importaciones/muestra con archivo y hoja como campos del formulario', () => {
    const respuesta: Muestra = { hojas: ['Hoja1'], hoja: 0, columnas: 2, filas: [] };
    let recibida: Muestra | undefined;
    service.muestra(archivo).subscribe((m) => (recibida = m));

    const req = http.expectOne((r) => r.url.endsWith('/importaciones/muestra'));
    expect(req.request.method).toBe('POST');
    const body = req.request.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect((body.get('archivo') as File).name).toBe('lista.csv');
    expect(body.get('hoja')).toBe('0');
    expect(req.request.headers.has('Content-Type')).toBe(false);
    req.flush(respuesta);

    expect(recibida).toEqual(respuesta);
  });

  it('muestra: manda la hoja pedida', () => {
    service.muestra(archivo, 2).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/importaciones/muestra'));
    expect((req.request.body as FormData).get('hoja')).toBe('2');
    req.flush({ hojas: [], hoja: 2, columnas: 0, filas: [] });
  });

  it('vistaPrevia: POST /importaciones/vista-previa con archivo y datos como JSON', async () => {
    const respuesta: VistaPrevia = {
      conteos,
      cambios: [],
      nuevos: [],
      errores: [],
      advertencias: [],
      noEstan: [],
      detalleRecortado: false,
    };
    let recibida: VistaPrevia | undefined;
    service.vistaPrevia(archivo, datos).subscribe((v) => (recibida = v));

    const req = http.expectOne((r) => r.url.endsWith('/importaciones/vista-previa'));
    expect(req.request.method).toBe('POST');
    const body = req.request.body as FormData;
    expect((body.get('archivo') as File).name).toBe('lista.csv');
    expect(await leerDatos(body)).toEqual(datos);
    expect(req.request.headers.has('Content-Type')).toBe(false);
    req.flush(respuesta);

    expect(recibida).toEqual(respuesta);
  });

  it('aplicar: POST /importaciones con crearNuevos, rubroId y los conteos esperados', async () => {
    const aplicar: AplicarRequest = {
      ...datos,
      crearNuevos: true,
      rubroId: 3,
      conteosEsperados: conteos,
    };
    const respuesta: Importacion = {
      id: 9,
      proveedorId: 7,
      archivo: 'lista.csv',
      fecha: '2026-10-12T10:00:00',
      conteos,
      estado: 'APLICADA',
    };
    let recibida: Importacion | undefined;
    service.aplicar(archivo, aplicar).subscribe((i) => (recibida = i));

    const req = http.expectOne((r) => r.url.endsWith('/importaciones'));
    expect(req.request.method).toBe('POST');
    const body = req.request.body as FormData;
    expect((body.get('archivo') as File).name).toBe('lista.csv');
    expect(await leerDatos(body)).toEqual(aplicar);
    expect(req.request.headers.has('Content-Type')).toBe(false);
    req.flush(respuesta, { status: 201, statusText: 'Created' });

    expect(recibida).toEqual(respuesta);
  });
});

function blobATexto(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const lector = new FileReader();
    lector.onload = () => resolve(lector.result as string);
    lector.onerror = () => reject(lector.error);
    lector.readAsText(blob);
  });
}
