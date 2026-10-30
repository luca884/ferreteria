import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DatosFiscales } from '../../features/datos-fiscales/datos-fiscales.service';
import { EmisorService } from './emisor.service';

describe('EmisorService', () => {
  it('lee el emisor con GET /emisor', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const datos: DatosFiscales = {
      razonSocial: 'Tuerca SRL',
      cuit: '30-71234567-8',
      condicionIva: 'RESPONSABLE_INSCRIPTO',
      domicilioComercial: 'Av. Luro 3200',
      inicioActividades: '2015-03-01',
      ingresosBrutos: '901-123456-7',
    };
    let leidos: DatosFiscales | undefined;

    TestBed.inject(EmisorService)
      .obtener()
      .subscribe((d) => (leidos = d));

    const req = http.expectOne((r) => r.url.endsWith('/emisor'));
    expect(req.request.method).toBe('GET');
    req.flush(datos);
    expect(leidos).toEqual(datos);
    http.verify();
  });
});
