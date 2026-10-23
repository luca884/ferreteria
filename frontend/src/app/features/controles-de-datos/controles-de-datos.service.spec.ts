import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ControlesDeDatosService } from './controles-de-datos.service';

describe('ControlesDeDatosService', () => {
  let service: ControlesDeDatosService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ControlesDeDatosService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('pide GET /articulos/controles con el control', () => {
    let filas: number | undefined;
    service.listar('MARGEN_NEGATIVO').subscribe((r) => (filas = r.length));

    const req = http.expectOne((r) => r.url.endsWith('/articulos/controles'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('control')).toBe('MARGEN_NEGATIVO');
    req.flush([]);

    expect(filas).toBe(0);
  });
});
