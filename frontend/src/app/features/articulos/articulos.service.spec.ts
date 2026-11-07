import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PageResponse } from '../../core/api/page.models';
import {
  AjustesRequest,
  Articulo,
  ArticuloRequest,
  DesglosePrecio,
  MENSAJE_ARTICULO_MODIFICADO,
} from './articulo.models';
import { ArticulosService } from './articulos.service';

describe('ArticulosService', () => {
  let service: ArticulosService;
  let http: HttpTestingController;

  const tornillo: Articulo = {
    id: 1,
    codigoInterno: 'TOR-001',
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: 'Tornillo hexagonal 1/4',
    rubroId: null,
    proveedorId: null,
    costo: 100,
    margenPorcentaje: null,
    alicuotaIva: 'IVA_21',
    moneda: 'ARS',
    precioFinal: 170,
    unidad: 'UNIDAD',
    stockActual: 0,
    stockMinimo: 0,
    ubicacion: null,
    activo: true,
  };

  const pagina: PageResponse<Articulo> = {
    items: [tornillo],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
  };

  const request: ArticuloRequest = {
    codigoInterno: 'TOR-001',
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: 'Tornillo hexagonal 1/4',
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
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ArticulosService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('exporta con GET /articulos/exportacion la búsqueda sin espacios e inactivos, y trae el archivo con sus encabezados', () => {
    let nombre: string | null | undefined;
    service.exportar('  torni ', true).subscribe((r) => (nombre = r.headers.get('Content-Disposition')));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/exportacion'));
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    expect(req.request.params.get('q')).toBe('torni');
    expect(req.request.params.get('incluirInactivos')).toBe('true');
    req.flush(new Blob(['csv']), { headers: { 'Content-Disposition': 'attachment; filename="articulos.csv"' } });

    expect(nombre).toBe('attachment; filename="articulos.csv"');
  });

  it('exporta sin q cuando la búsqueda está vacía', () => {
    service.exportar('   ', false).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/articulos/exportacion'));
    expect(req.request.params.has('q')).toBe(false);
    expect(req.request.params.get('incluirInactivos')).toBe('false');
    expect(req.request.params.has('enOferta')).toBe(false);
    req.flush(new Blob(['']));
  });

  it('exporta con enOferta=true cuando la lista tiene «Solo en oferta» (bugs de la guía, S19)', () => {
    service.exportar('torni', false, true).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/articulos/exportacion'));
    expect(req.request.params.get('enOferta')).toBe('true');
    expect(req.request.params.get('q')).toBe('torni');
    expect(req.request.params.get('incluirInactivos')).toBe('false');
    req.flush(new Blob(['']));
  });

  it('busca con GET /articulos mandando q, page, size e incluirInactivos', () => {
    let recibida: PageResponse<Articulo> | undefined;
    service.buscar('torni', 2, 10, true).subscribe((p) => (recibida = p));

    const req = http.expectOne((r) => r.url.endsWith('/articulos'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('torni');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.params.get('incluirInactivos')).toBe('true');
    req.flush(pagina);

    expect(recibida).toEqual(pagina);
  });

  it('no manda q cuando la búsqueda está vacía o en blanco', () => {
    service.buscar('   ', 0, 20, false).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/articulos'));
    expect(req.request.params.has('q')).toBe(false);
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    expect(req.request.params.get('incluirInactivos')).toBe('false');
    req.flush(pagina);
  });

  it('manda q sin espacios sobrantes', () => {
    service.buscar('  mart-0 ', 0, 20, false).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/articulos'));
    expect(req.request.params.get('q')).toBe('mart-0');
    req.flush(pagina);
  });

  it('obtiene un artículo con GET /articulos/{id}', () => {
    let obtenido: Articulo | undefined;
    service.obtener(1).subscribe((a) => (obtenido = a));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/1'));
    expect(req.request.method).toBe('GET');
    req.flush(tornillo);

    expect(obtenido).toEqual(tornillo);
  });

  it('crea un artículo con POST /articulos', () => {
    let creado: Articulo | undefined;
    service.crear(request).subscribe((a) => (creado = a));

    const req = http.expectOne((r) => r.url.endsWith('/articulos'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(tornillo);

    expect(creado).toEqual(tornillo);
  });

  it('actualiza un artículo con PUT /articulos/{id}', () => {
    let actualizado: Articulo | undefined;
    service.actualizar(1, { ...request, costo: 200 }).subscribe((a) => (actualizado = a));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/1'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ ...request, costo: 200 });
    req.flush({ ...tornillo, costo: 200, precioFinal: 330 });

    expect(actualizado?.precioFinal).toBe(330);
  });

  it('reactiva un artículo con POST /articulos/{id}/reactivacion', () => {
    let reactivado: Articulo | undefined;
    service.reactivar(1).subscribe((a) => (reactivado = a));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/1/reactivacion'));
    expect(req.request.method).toBe('POST');
    req.flush(tornillo);

    expect(reactivado).toEqual(tornillo);
  });

  it('ajusta el stock con POST /articulos/{id}/ajustes-stock mandando el stock real y el motivo', () => {
    const respuesta = { articuloId: 1, stockAnterior: 0, diferencia: 50, stockActual: 50 };
    let ajuste: unknown;
    service
      .ajustarStock(1, { stockReal: 50, motivo: 'INGRESO_MERCADERIA', detalle: 'remito 12' })
      .subscribe((r) => (ajuste = r));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/1/ajustes-stock'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ stockReal: 50, motivo: 'INGRESO_MERCADERIA', detalle: 'remito 12' });
    req.flush(respuesta);

    expect(ajuste).toEqual(respuesta);
  });

  it('lee la configuración de precios con GET /configuracion-precios', () => {
    let configuracion: unknown;
    service.configuracionPrecios().subscribe((c) => (configuracion = c));

    const req = http.expectOne((r) => r.url.endsWith('/configuracion-precios'));
    expect(req.request.method).toBe('GET');
    req.flush({ margenDefectoPorcentaje: 30, redondeoMultiplo: 10 });

    expect(configuracion).toEqual({ margenDefectoPorcentaje: 30, redondeoMultiplo: 10 });
  });

  it('el listado y el detalle traen la moneda de cada artículo', () => {
    const enDolares: Articulo = { ...tornillo, id: 2, costo: 10, moneda: 'USD', precioFinal: 15730 };
    let recibida: PageResponse<Articulo> | undefined;
    let detalle: Articulo | undefined;
    service.buscar('', 0, 20, false).subscribe((p) => (recibida = p));
    http.expectOne((r) => r.url.endsWith('/articulos')).flush({ ...pagina, items: [tornillo, enDolares] });
    service.obtener(2).subscribe((a) => (detalle = a));
    http.expectOne((r) => r.url.endsWith('/articulos/2')).flush(enDolares);

    expect(recibida?.items.map((a) => a.moneda)).toEqual(['ARS', 'USD']);
    expect(detalle?.moneda).toBe('USD');
  });

  it('crea un artículo en dólares mandando la moneda en el POST', () => {
    service.crear({ ...request, costo: 10, moneda: 'USD' }).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/articulos'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body.moneda).toBe('USD');
    expect(req.request.body.costo).toBe(10);
    req.flush({ ...tornillo, moneda: 'USD' });
  });

  it('actualiza la moneda de un artículo mandándola en el PUT', () => {
    service.actualizar(1, { ...request, costo: 12, moneda: 'USD' }).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/articulos/1'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ ...request, costo: 12, moneda: 'USD' });
    req.flush({ ...tornillo, moneda: 'USD' });
  });

  it('desactiva un artículo con DELETE /articulos/{id}', () => {
    let terminado = false;
    service.desactivar(1).subscribe(() => (terminado = true));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/1'));
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(terminado).toBe(true);
  });
  describe('desglose y ajustes (R-216, R-218)', () => {
    const desglose: DesglosePrecio = {
      articuloId: 1,
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
      pasos: [
        { tipo: 'LISTA', porcentaje: null, importe: 1000 },
        { tipo: 'COSTO', porcentaje: null, importe: 880.65 },
      ],
      costoDeLista: 880.65,
      costoGuardado: 880.65,
      coincide: true,
      margen: 30,
      origenMargen: 'PROPIO',
      cotizacion: null,
      cotizacionCongelada: false,
      multiplo: 10,
      precioFinal: 1390,
    };

    it('pide el desglose con GET /articulos/{id}/desglose', () => {
      let recibido: DesglosePrecio | undefined;
      service.desglose(1).subscribe((d) => (recibido = d));

      const req = http.expectOne((r) => r.url.endsWith('/articulos/1/desglose'));
      expect(req.request.method).toBe('GET');
      req.flush(desglose);

      expect(recibido).toEqual(desglose);
    });

    it('guarda los ajustes con PUT /articulos/{id}/ajustes, sin costo', () => {
      const pedido: AjustesRequest = { ajuste1: -5, ajuste2: null, ajuste3: null, confirmaPisarCosto: false, version: 3 };
      let guardado: Articulo | undefined;
      service.guardarAjustes(1, pedido).subscribe((a) => (guardado = a));

      const req = http.expectOne((r) => r.url.endsWith('/articulos/1/ajustes'));
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({
        ajuste1: -5,
        ajuste2: null,
        ajuste3: null,
        confirmaPisarCosto: false,
        version: 3,
      });
      expect('costo' in req.request.body).toBe(false);
      req.flush({ ...tornillo, costo: 855 });

      expect(guardado?.costo).toBe(855);
    });

    it('el PUT del artículo no manda ajustes', () => {
      service.actualizar(1, request).subscribe();

      const req = http.expectOne((r) => r.url.endsWith('/articulos/1'));
      const cuerpo = req.request.body as Record<string, unknown>;
      expect(Object.keys(cuerpo).filter((k) => k.startsWith('ajuste') || k.startsWith('bonificacion'))).toEqual([]);
      req.flush(tornillo);
    });

    it('la respuesta trae el precio de lista, los ajustes, las bonificaciones y si la lista incluye IVA', () => {
      const conAjustes: Articulo = {
        ...tornillo,
        precioListaProveedor: 1000,
        ajuste1: -5,
        ajuste2: 3,
        ajuste3: null,
        bonificacion1: 10,
        bonificacion2: null,
        bonificacion3: null,
        listaIncluyeIva: false,
      };
      let recibido: Articulo | undefined;
      service.obtener(1).subscribe((a) => (recibido = a));
      http.expectOne((r) => r.url.endsWith('/articulos/1')).flush(conAjustes);

      expect(recibido?.ajuste1).toBe(-5);
      expect(recibido?.bonificacion1).toBe(10);
      expect(recibido?.listaIncluyeIva).toBe(false);
      expect(recibido?.precioListaProveedor).toBe(1000);
    });

    it('una respuesta sin esos campos (cliente viejo) no rompe', () => {
      let recibido: Articulo | undefined;
      service.obtener(1).subscribe((a) => (recibido = a));
      http.expectOne((r) => r.url.endsWith('/articulos/1')).flush(tornillo);

      expect(recibido?.ajuste1).toBeUndefined();
      expect(recibido?.costo).toBe(100);
    });
  });
  describe('versión del artículo (R-224, R-225)', () => {
    it('la respuesta trae la versión', () => {
      let recibido: Articulo | undefined;
      service.obtener(1).subscribe((a) => (recibido = a));
      http.expectOne((r) => r.url.endsWith('/articulos/1')).flush({ ...tornillo, version: 3 });

      expect(recibido?.version).toBe(3);
    });

    it('una respuesta sin versión (cliente viejo) no rompe', () => {
      let recibido: Articulo | undefined;
      service.obtener(1).subscribe((a) => (recibido = a));
      http.expectOne((r) => r.url.endsWith('/articulos/1')).flush(tornillo);

      expect(recibido?.version).toBeUndefined();
    });

    it('el PUT del artículo manda la versión que se leyó', () => {
      service.actualizar(1, { ...request, version: 3 }).subscribe();

      const req = http.expectOne((r) => r.url.endsWith('/articulos/1'));
      expect(req.request.body).toEqual({ ...request, version: 3 });
      req.flush({ ...tornillo, version: 4 });
    });

    it('el alta no manda versión', () => {
      service.crear(request).subscribe();

      const req = http.expectOne((r) => r.url.endsWith('/articulos'));
      expect(req.request.body).not.toHaveProperty('version');
      req.flush(tornillo);
    });

    it('el mensaje del 409 de versión es el del servidor', () => {
      expect(MENSAJE_ARTICULO_MODIFICADO).toBe('Otro cambio modificó este artículo. Recargá y volvé a intentar.');
    });
  });
});

describe('ArticulosService › movimientos de stock', () => {
  let service: ArticulosService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ArticulosService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('pide GET /articulos/{id}/movimientos-stock con la página y solo los filtros cargados', () => {
    let total: number | undefined;
    service.movimientos(7, { page: 2, desde: '2026-10-01' }).subscribe((p) => (total = p.totalElements));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/7/movimientos-stock'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('20');
    expect(req.request.params.get('desde')).toBe('2026-10-01');
    expect(req.request.params.has('hasta')).toBe(false);
    req.flush({ items: [], page: 2, size: 20, totalElements: 41, totalPages: 3 });

    expect(total).toBe(41);
  });
});

describe('ArticulosService › clientes que compraron el artículo', () => {
  let service: ArticulosService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ArticulosService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('pide GET /articulos/{id}/clientes con la página y 20 por página', () => {
    let sinCliente: number | undefined;
    service.clientes(7, 1).subscribe((p) => (sinCliente = p.ventasSinCliente));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/7/clientes'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('20');
    req.flush({ items: [], page: 1, size: 20, totalElements: 21, totalPages: 2, ventasSinCliente: 4 });

    expect(sinCliente).toBe(4);
  });
});
