import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DatosFiscales, DatosFiscalesService } from './datos-fiscales.service';

describe('DatosFiscalesService', () => {
  let service: DatosFiscalesService;
  let http: HttpTestingController;

  const datos: DatosFiscales = {
    razonSocial: 'Tuerca SRL',
    cuit: '30-71234567-1',
    condicionIva: 'RESPONSABLE_INSCRIPTO',
    domicilioComercial: 'Av. Luro 3200, Mar del Plata',
    inicioActividades: '2015-03-01',
    ingresosBrutos: '901-123456-7',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(DatosFiscalesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lee los datos con GET /datos-fiscales', () => {
    let leidos: DatosFiscales | undefined;
    service.obtener().subscribe((d) => (leidos = d));

    const req = http.expectOne((r) => r.url.endsWith('/datos-fiscales'));
    expect(req.request.method).toBe('GET');
    req.flush(datos);

    expect(leidos).toEqual(datos);
  });

  it('guarda con PUT /datos-fiscales, sin el CUIT', () => {
    const { cuit: _cuit, ...pedido } = datos;
    service.guardar(pedido).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/datos-fiscales'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(pedido);
    expect(req.request.body).not.toHaveProperty('cuit');
    req.flush(datos);
  });
});
