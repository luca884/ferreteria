import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  AplicarRequest,
  Conteos,
  Importacion,
  MapeoRequest,
  Muestra,
  VistaPrevia,
  VistaPreviaRequest,
} from './importacion.models';
import { ImportacionesService } from './importaciones.service';

describe('ImportacionesService', () => {
  let service: ImportacionesService;
  let http: HttpTestingController;

  const archivo = new File(['codigo;precio'], 'lista.csv', { type: 'text/csv' });
  const mapeo: MapeoRequest = {
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
    rubroId: null,
  };
  const conteos: Conteos = {
    suben: 1,
    bajan: 2,
    nuevos: 3,
    sinCambio: 4,
    noEstan: 5,
    conError: 6,
    cambianMoneda: 7,
    corrigen: 8,
    salteadas: 9,
  };
  const vistaPreviaVacia: VistaPrevia = {
    conteos,
    cambios: [],
    nuevos: [],
    errores: [],
    advertencias: [],
    noEstan: [],
    cambiosDeMoneda: [],
    correcciones: [],
    sinCambio: [],
  };

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

  describe('muestra', () => {
    const respuesta: Muestra = {
      hojas: [
        {
          indice: 0,
          nombre: 'Lista',
          columnas: 3,
          filaEncabezado: 1,
          filas: [{ numero: 1, celdas: ['Código', 'Descripción', 'Costo'] }],
        },
        { indice: 1, nombre: 'Oferta', columnas: 2, filaEncabezado: null, filas: [] },
      ],
      propuesta: {
        hoja: 0,
        filaDesde: 2,
        columnaCodigo: 0,
        columnaDescripcion: 1,
        columnaCosto: 2,
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
        hojas: [0],
      },
    };

    it('POST /importaciones/muestra con el archivo y nada más: ya no manda la hoja', () => {
      let recibida: Muestra | undefined;
      service.muestra(archivo).subscribe((m) => (recibida = m));

      const req = http.expectOne((r) => r.url.endsWith('/importaciones/muestra'));
      expect(req.request.method).toBe('POST');
      const body = req.request.body as FormData;
      expect(body).toBeInstanceOf(FormData);
      expect((body.get('archivo') as File).name).toBe('lista.csv');
      expect(body.has('hoja')).toBe(false);
      expect(Array.from(body.keys())).toEqual(['archivo']);
      expect(req.request.headers.has('Content-Type')).toBe(false);
      req.flush(respuesta);

      expect(recibida).toEqual(respuesta);
    });
  });

  describe('vistaPrevia', () => {
    it('POST /importaciones/vista-previa con archivo y datos como JSON', async () => {
      let recibida: VistaPrevia | undefined;
      service.vistaPrevia(archivo, datos).subscribe((v) => (recibida = v));

      const req = http.expectOne((r) => r.url.endsWith('/importaciones/vista-previa'));
      expect(req.request.method).toBe('POST');
      const body = req.request.body as FormData;
      expect((body.get('archivo') as File).name).toBe('lista.csv');
      expect(await leerDatos(body)).toEqual(datos);
      expect(req.request.headers.has('Content-Type')).toBe(false);
      req.flush(vistaPreviaVacia);

      expect(recibida).toEqual(vistaPreviaVacia);
    });

    it('manda el mapeo con los campos nuevos y el rubro de los artículos nuevos', async () => {
      const completo: VistaPreviaRequest = {
        ...datos,
        rubroId: 3,
        mapeo: {
          ...mapeo,
          hojas: [0, 2],
          columnaMoneda: 4,
          columnaSinIva: 5,
          columnaConIva: 6,
          columnaIva: 7,
          columnaAjuste1: 8,
          columnaAjuste2: 9,
          columnaAjuste3: 10,
          columnaCodigoBarras: 11,
          columnaUnidad: 12,
          columnaRubro: 13,
        },
      };
      service.vistaPrevia(archivo, completo).subscribe();

      const req = http.expectOne((r) => r.url.endsWith('/importaciones/vista-previa'));
      expect(await leerDatos(req.request.body as FormData)).toEqual(completo);
      req.flush(vistaPreviaVacia);
    });

    it('con moneda fija manda la moneda y no la columna', async () => {
      const fija: VistaPreviaRequest = { ...datos, mapeo: { ...mapeo, monedaFija: 'USD' } };
      service.vistaPrevia(archivo, fija).subscribe();

      const req = http.expectOne((r) => r.url.endsWith('/importaciones/vista-previa'));
      const enviado = (await leerDatos(req.request.body as FormData)) as { mapeo: MapeoRequest };
      expect(enviado.mapeo.monedaFija).toBe('USD');
      expect(enviado.mapeo.columnaMoneda).toBeUndefined();
      req.flush(vistaPreviaVacia);
    });
  });

  describe('aplicar', () => {
    const importacion: Importacion = {
      id: 9,
      proveedorId: 7,
      proveedorNombre: 'Sinteplast',
      usuarioId: 3,
      archivo: 'lista.csv',
      fecha: '2026-10-12T10:00:00',
      conteos,
      estado: 'APLICADA',
    };

    it('POST /importaciones con crearNuevos, rubroId, los conteos esperados y las filas elegidas', async () => {
      const aplicar: AplicarRequest = {
        ...datos,
        crearNuevos: true,
        rubroId: 3,
        conteosEsperados: conteos,
        filasElegidas: [
          { hoja: 0, fila: 2 },
          { hoja: 3, fila: 40 },
        ],
      };
      let recibida: Importacion | undefined;
      service.aplicar(archivo, aplicar).subscribe((i) => (recibida = i));

      const req = http.expectOne((r) => r.url.endsWith('/importaciones') && r.method === 'POST');
      const body = req.request.body as FormData;
      expect((body.get('archivo') as File).name).toBe('lista.csv');
      expect(await leerDatos(body)).toEqual(aplicar);
      expect(req.request.headers.has('Content-Type')).toBe(false);
      req.flush(importacion, { status: 201, statusText: 'Created' });

      expect(recibida).toEqual(importacion);
    });

    it('los contadores nuevos viajan en conteosEsperados', async () => {
      service
        .aplicar(archivo, { ...datos, crearNuevos: false, conteosEsperados: conteos })
        .subscribe();

      const req = http.expectOne((r) => r.url.endsWith('/importaciones') && r.method === 'POST');
      const enviado = (await leerDatos(req.request.body as FormData)) as {
        conteosEsperados: Conteos;
      };
      expect(enviado.conteosEsperados.cambianMoneda).toBe(7);
      expect(enviado.conteosEsperados.corrigen).toBe(8);
      expect(enviado.conteosEsperados.salteadas).toBe(9);
      req.flush(importacion, { status: 201, statusText: 'Created' });
    });

    it('sin filasElegidas no inventa el campo (cliente de antes de elegir filas)', async () => {
      service
        .aplicar(archivo, { ...datos, crearNuevos: false, conteosEsperados: conteos })
        .subscribe();

      const req = http.expectOne((r) => r.url.endsWith('/importaciones') && r.method === 'POST');
      const enviado = (await leerDatos(req.request.body as FormData)) as object;
      expect('filasElegidas' in enviado).toBe(false);
      req.flush(importacion, { status: 201, statusText: 'Created' });
    });
  });

  describe('listar', () => {
    const lista: Importacion[] = [
      {
        id: 9,
        proveedorId: 7,
        proveedorNombre: 'Sinteplast',
        usuarioId: 3,
        archivo: 'lista.csv',
        fecha: '2026-10-12T10:00:00',
        conteos,
        estado: 'APLICADA',
      },
    ];

    it('sin proveedor hace GET /importaciones sin parámetros', () => {
      let recibida: Importacion[] | undefined;
      service.listar().subscribe((l) => (recibida = l));

      const req = http.expectOne((r) => r.url.endsWith('/importaciones') && r.method === 'GET');
      expect(req.request.params.keys()).toEqual([]);
      req.flush(lista);

      expect(recibida).toEqual(lista);
    });

    it('con proveedor manda proveedorId', () => {
      service.listar(7).subscribe();

      const req = http.expectOne((r) => r.url.endsWith('/importaciones') && r.method === 'GET');
      expect(req.request.params.get('proveedorId')).toBe('7');
      req.flush(lista);
    });
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
