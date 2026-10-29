import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AvisosFacturacion } from './avisos-facturacion.models';
import { AvisosFacturacionService } from './avisos-facturacion.service';

describe('AvisosFacturacionService', () => {
  let service: AvisosFacturacionService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AvisosFacturacionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lee con GET /facturacion/avisos-mostrador lo que el Dueño tiene que revisar (C4)', () => {
    const cuerpo: AvisosFacturacion = { rechazadas: 2, pendientesViejas: 1, enRevision: 0, bloqueo: null };
    let leidos: AvisosFacturacion | undefined;
    service.leer().subscribe((a) => (leidos = a));
    const req = http.expectOne((r) => r.url.endsWith('/facturacion/avisos-mostrador'));
    expect(req.request.method).toBe('GET');
    req.flush(cuerpo);
    expect(leidos).toEqual(cuerpo);
  });
});
