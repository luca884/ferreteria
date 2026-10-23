import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ConfiguracionPreciosService } from './configuracion-precios.service';

describe('ConfiguracionPreciosService', () => {
  let service: ConfiguracionPreciosService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ConfiguracionPreciosService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lee la configuración con GET /configuracion-precios', () => {
    let margen: number | undefined;
    service.obtener().subscribe((c) => (margen = c.margenDefectoPorcentaje));

    const req = http.expectOne((r) => r.url.endsWith('/configuracion-precios'));
    expect(req.request.method).toBe('GET');
    req.flush({ margenDefectoPorcentaje: 30, redondeoMultiplo: 0 });

    expect(margen).toBe(30);
  });

  it('cuenta los artículos que cambian con los valores nuevos en la consulta', () => {
    let cantidad: number | undefined;
    service.articulosQueCambian({ margenDefectoPorcentaje: 35, redondeoMultiplo: 0.5 }).subscribe((n) => (cantidad = n));

    const req = http.expectOne((r) => r.url.endsWith('/configuracion-precios/articulos-que-cambian'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('margenDefectoPorcentaje')).toBe('35');
    expect(req.request.params.get('redondeoMultiplo')).toBe('0.5');
    req.flush({ cantidad: 12 });

    expect(cantidad).toBe(12);
  });

  it('guarda con PUT /configuracion-precios y devuelve cuántos precios cambiaron', () => {
    let recalculados: number | undefined;
    service
      .guardar({ margenDefectoPorcentaje: 35, redondeoMultiplo: 0.5 })
      .subscribe((g) => (recalculados = g.articulosRecalculados));

    const req = http.expectOne((r) => r.url.endsWith('/configuracion-precios'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ margenDefectoPorcentaje: 35, redondeoMultiplo: 0.5 });
    req.flush({ margenDefectoPorcentaje: 35, redondeoMultiplo: 0.5, articulosRecalculados: 4, avisos: [] });

    expect(recalculados).toBe(4);
  });
});
