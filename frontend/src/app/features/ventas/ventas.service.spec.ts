import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { VentasService } from './ventas.service';

describe('VentasService', () => {
  let service: VentasService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(VentasService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lista con GET /ventas, la página y el tamaño, sin los filtros que no se eligieron', () => {
    service.listar({ page: 0 }).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/ventas'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    expect(req.request.params.keys().sort()).toEqual(['page', 'size']);
    req.flush({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  });

  it('manda número, cliente y fechas cuando se eligen', () => {
    service.listar({ page: 2, numero: 15, cliente: 'Pérez', desde: '2026-10-01', hasta: '2026-10-22' }).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/ventas'));
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('numero')).toBe('15');
    expect(req.request.params.get('cliente')).toBe('Pérez');
    expect(req.request.params.get('desde')).toBe('2026-10-01');
    expect(req.request.params.get('hasta')).toBe('2026-10-22');
    req.flush({ items: [], page: 2, size: 20, totalElements: 0, totalPages: 0 });
  });

  it('manda el número de factura como `numeroFiscal`, tal cual se escribió (CU-16, S15)', () => {
    service.listar({ page: 0, numeroFiscal: '00001-00000012' }).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/ventas'));
    expect(req.request.params.get('numeroFiscal')).toBe('00001-00000012');
    expect(req.request.params.keys().sort()).toEqual(['numeroFiscal', 'page', 'size']);
    req.flush({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  });

  it('trae una venta con GET /ventas/{id}', () => {
    service.obtener(31).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/ventas/31'));
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('manda la bandeja como `fiscal` (C1): PENDIENTES o RECHAZADAS', () => {
    for (const fiscal of ['PENDIENTES', 'RECHAZADAS'] as const) {
      service.listar({ page: 0, fiscal }).subscribe();
      const req = http.expectOne((r) => r.url.endsWith('/ventas'));
      expect(req.request.params.get('fiscal')).toBe(fiscal);
      expect(req.request.params.keys().sort()).toEqual(['fiscal', 'page', 'size']);
      req.flush({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    }
  });

  it('«Reintentar» es POST /ventas/{id}/factura/reintentar con `fechaDeHoy` (C2)', () => {
    service.reintentar(31, false).subscribe();
    const sin = http.expectOne((r) => r.url.endsWith('/ventas/31/factura/reintentar'));
    expect(sin.request.method).toBe('POST');
    expect(sin.request.body).toEqual({ fechaDeHoy: false });
    sin.flush({});

    service.reintentar(31, true).subscribe();
    const con = http.expectOne((r) => r.url.endsWith('/ventas/31/factura/reintentar'));
    expect(con.request.body).toEqual({ fechaDeHoy: true });
    con.flush({});
  });

  it('la factura de una venta es GET /ventas/{id}/factura (C7: el Vendedor solo la de sus ventas de la caja abierta)', () => {
    service.factura(31).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/ventas/31/factura'));
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('«Dejar sin factura» es POST /ventas/{id}/factura/sin-factura con el `motivo` (C3)', () => {
    service.dejarSinFactura(31, 'El cliente no quiere factura').subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/ventas/31/factura/sin-factura'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ motivo: 'El cliente no quiere factura' });
    req.flush({});
  });

  it('«Anular» es POST /ventas/{id}/anular: con el `motivo` en la anulación interna y sin él en la nota de crédito (CU-08)', () => {
    service.anular(31, 'El cliente devolvió todo').subscribe();
    const interna = http.expectOne((r) => r.url.endsWith('/ventas/31/anular'));
    expect(interna.request.method).toBe('POST');
    expect(interna.request.body).toEqual({ motivo: 'El cliente devolvió todo' });
    interna.flush({});

    service.anular(31, null).subscribe();
    const nota = http.expectOne((r) => r.url.endsWith('/ventas/31/anular'));
    expect(nota.request.body).toEqual({});
    nota.flush({});
  });
});
