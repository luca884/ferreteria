import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PageResponse } from '../../core/api/page.models';
import { EmitirRemitoRequest, RemitoEmitido, RemitoResponse, RemitoResumen } from './remito.models';
import { RemitosService } from './remitos.service';

describe('RemitosService', () => {
  let service: RemitosService;
  let http: HttpTestingController;

  const pedido: EmitirRemitoRequest = {
    clave: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
    renglones: [{ articuloId: 1, cantidad: 3, precioUnitario: 170 }],
    totalMostrado: 510,
    clienteNombre: 'Obra Perez',
  };

  const remito: RemitoResponse = {
    remitoId: 8,
    numero: 5,
    estado: 'EMITIDO',
    fecha: '2026-10-18T16:03:00',
    clienteNombre: 'Obra Perez',
    subtotal: 510,
    descuentoPorcentaje: 0,
    descuento: 0,
    neto: 421.49,
    iva: 88.51,
    total: 510,
    renglones: [
      {
        renglon: 1,
        articuloId: 1,
        descripcion: 'Tornillo',
        unidad: 'UNIDAD',
        cantidad: 3,
        precioUnitario: 170,
        alicuotaIva: 'IVA_21',
        bruto: 510,
        descuentoPorcentaje: 0,
        total: 510,
        neto: 421.49,
        iva: 88.51,
      },
    ],
    sinStock: [],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(RemitosService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('hace POST a /remitos con el cuerpo del contrato, sin campos de más', () => {
    service.emitir(pedido).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/remitos'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(pedido);
    expect(Object.keys(req.request.body).sort()).toEqual(['clave', 'clienteNombre', 'renglones', 'totalMostrado']);
    req.flush(remito, { status: 201, statusText: 'Created' });
  });

  it('201 es un remito nuevo', () => {
    let r: RemitoEmitido | undefined;
    service.emitir(pedido).subscribe((x) => (r = x));
    http.expectOne((q) => q.url.endsWith('/remitos')).flush(remito, { status: 201, statusText: 'Created' });
    expect(r).toEqual({ remito, repetido: false });
  });

  it('200 es un remito repetido (misma clave y contenido)', () => {
    let r: RemitoEmitido | undefined;
    service.emitir(pedido).subscribe((x) => (r = x));
    http.expectOne((q) => q.url.endsWith('/remitos')).flush(remito, { status: 200, statusText: 'OK' });
    expect(r).toEqual({ remito, repetido: true });
  });

  describe('listar', () => {
    const resumen: RemitoResumen = {
      remitoId: 8,
      numero: 5,
      estado: 'EMITIDO',
      fecha: '2026-10-18T16:03:00',
      clienteNombre: 'Obra Perez',
      total: 510,
    };
    const pagina: PageResponse<RemitoResumen> = { items: [resumen], page: 0, size: 20, totalElements: 1, totalPages: 1 };

    it('hace GET a /remitos con la página y sin filtros que no se eligieron', () => {
      let r: PageResponse<RemitoResumen> | undefined;
      service.listar({ page: 0 }).subscribe((x) => (r = x));

      const req = http.expectOne((q) => q.url.endsWith('/remitos'));
      expect(req.request.method).toBe('GET');
      expect(req.request.params.keys().sort()).toEqual(['page', 'size']);
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('20');
      req.flush(pagina);
      expect(r).toEqual(pagina);
    });

    it('manda el estado, el número y las fechas elegidos', () => {
      service.listar({ page: 2, size: 10, estado: 'ANULADO', numero: 5, desde: '2026-10-01', hasta: '2026-10-31' }).subscribe();

      const req = http.expectOne((q) => q.url.endsWith('/remitos'));
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.get('size')).toBe('10');
      expect(req.request.params.get('estado')).toBe('ANULADO');
      expect(req.request.params.get('numero')).toBe('5');
      expect(req.request.params.get('desde')).toBe('2026-10-01');
      expect(req.request.params.get('hasta')).toBe('2026-10-31');
      req.flush(pagina);
    });
  });

  it('obtener hace GET a /remitos/{id}', () => {
    let r: RemitoResponse | undefined;
    service.obtener(8).subscribe((x) => (r = x));

    const req = http.expectOne((q) => q.url.endsWith('/remitos/8'));
    expect(req.request.method).toBe('GET');
    req.flush(remito);
    expect(r).toEqual(remito);
  });

  it('anular hace PATCH a /remitos/{id}/anular sin cuerpo y devuelve el detalle', () => {
    let r: RemitoResponse | undefined;
    service.anular(8).subscribe((x) => (r = x));

    const req = http.expectOne((q) => q.url.endsWith('/remitos/8/anular'));
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toBeNull();
    req.flush({ ...remito, estado: 'ANULADO' });
    expect(r?.estado).toBe('ANULADO');
  });
});
