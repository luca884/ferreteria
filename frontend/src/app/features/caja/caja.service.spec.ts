import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CajaService } from './caja.service';

describe('CajaService', () => {
  let service: CajaService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CajaService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lee la caja vigente con GET /cajas/actual', () => {
    service.actual().subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/cajas/actual'));
    expect(req.request.method).toBe('GET');
    req.flush({ estado: 'SIN_CAJA', caja: null, efectivoPropuesto: 0 });
  });

  it('abre con POST /cajas y el efectivo inicial', () => {
    service.abrir(1500).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/cajas'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ efectivoInicial: 1500 });
    req.flush({});
  });

  it('carga un movimiento con POST /cajas/{id}/movimientos', () => {
    service.registrarMovimiento(4, { tipo: 'GASTO', importe: 300, motivo: 'Cadete' }).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/cajas/4/movimientos'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ tipo: 'GASTO', importe: 300, motivo: 'Cadete' });
    req.flush({});
  });

  it('cierra con POST /cajas/{id}/cierre mandando solo el contado (cierre ciego)', () => {
    service.cerrar(4, 5650).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/cajas/4/cierre'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ efectivoContado: 5650 });
    req.flush({});
  });

  it('trae el detalle con GET /cajas/{id} y el historial paginado con GET /cajas', () => {
    service.detalle(4).subscribe();
    http.expectOne((r) => r.url.endsWith('/cajas/4')).flush({});

    service.historial(2, 20).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/cajas'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('20');
    req.flush({ items: [], page: 2, size: 20, totalElements: 0, totalPages: 0 });
  });
});
