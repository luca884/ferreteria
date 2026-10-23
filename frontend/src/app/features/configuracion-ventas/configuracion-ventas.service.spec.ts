import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ConfiguracionVentasService } from './configuracion-ventas.service';

describe('ConfiguracionVentasService', () => {
  let service: ConfiguracionVentasService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ConfiguracionVentasService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('obtener pide GET /configuracion-ventas', () => {
    let tope: number | undefined;
    service.obtener().subscribe((r) => (tope = r.topeConsumidorFinal));

    const req = http.expectOne((r) => r.url.endsWith('/configuracion-ventas'));
    expect(req.request.method).toBe('GET');
    req.flush({ topeConsumidorFinal: 10000000 });

    expect(tope).toBe(10000000);
  });

  it('guardar manda PUT /configuracion-ventas con el tope', () => {
    service.guardar({ topeConsumidorFinal: 250000.5 }).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/configuracion-ventas'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ topeConsumidorFinal: 250000.5 });
    req.flush({ topeConsumidorFinal: 250000.5 });
  });
});
