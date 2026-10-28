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
});
