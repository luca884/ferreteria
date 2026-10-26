import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ReportesService } from './reportes.service';

describe('ReportesService', () => {
  let service: ReportesService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ReportesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('pide el resumen con GET /reportes/ventas/resumen y las dos fechas', () => {
    service.resumen('2026-10-01', '2026-10-22').subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/reportes/ventas/resumen'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('desde')).toBe('2026-10-01');
    expect(req.request.params.get('hasta')).toBe('2026-10-22');
    req.flush({});
  });

  it('pide un reporte en filas por su nombre', () => {
    service.filas('por-usuario', '2026-10-22', '2026-10-22').subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/reportes/ventas/por-usuario'));
    expect(req.request.params.has('agrupar')).toBe(false);
    req.flush({ desde: '2026-10-22', hasta: '2026-10-22', filas: [] });
  });

  it('el reporte por período manda cómo agrupar', () => {
    service.filas('por-periodo', '2026-01-01', '2026-10-22', 'MES').subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/reportes/ventas/por-periodo'));
    expect(req.request.params.get('agrupar')).toBe('MES');
    req.flush({ desde: '2026-01-01', hasta: '2026-10-22', filas: [] });
  });

  it('pide los movimientos de caja a /reportes/caja/movimientos y manda el tipo solo si se elige', () => {
    service.movimientosCaja('2026-10-01', '2026-10-22').subscribe();
    const todos = http.expectOne((r) => r.url.endsWith('/reportes/caja/movimientos'));
    expect(todos.request.method).toBe('GET');
    expect(todos.request.params.get('desde')).toBe('2026-10-01');
    expect(todos.request.params.get('hasta')).toBe('2026-10-22');
    expect(todos.request.params.has('tipo')).toBe(false);
    todos.flush({});

    service.movimientosCaja('2026-10-01', '2026-10-22', 'GASTO').subscribe();
    const gastos = http.expectOne((r) => r.url.endsWith('/reportes/caja/movimientos'));
    expect(gastos.request.params.get('tipo')).toBe('GASTO');
    gastos.flush({});
  });
});
