import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { erroresDeCampos } from '../../core/api/api-error';
import { ConfiguracionCotizacion, ConfiguracionCotizacionRequest } from './configuracion-cotizacion.models';
import { ConfiguracionCotizacionService } from './configuracion-cotizacion.service';

describe('ConfiguracionCotizacionService', () => {
  let service: ConfiguracionCotizacionService;
  let http: HttpTestingController;

  const configuracion: ConfiguracionCotizacion = {
    activa: true,
    dolarPrecios: 'USD:oficial',
    ladoPrecios: 'VENTA',
    visibles: ['USD:oficial', 'EUR:oficial'],
    horaRecalculo: '08:00',
    vigente: 1000,
    vigenteDesde: '2026-10-16T11:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ConfiguracionCotizacionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lee la configuración con GET /configuracion-cotizacion', () => {
    let leida: ConfiguracionCotizacion | undefined;
    service.obtener().subscribe((c) => (leida = c));

    const req = http.expectOne((r) => r.url.endsWith('/configuracion-cotizacion'));
    expect(req.request.method).toBe('GET');
    req.flush(configuracion);

    expect(leida).toEqual(configuracion);
  });

  it('guarda con PUT /configuracion-cotizacion el pedido completo, sin el vigente (lo fija el recálculo)', () => {
    const pedido: ConfiguracionCotizacionRequest = {
      activa: false,
      dolarPrecios: 'USD:bolsa',
      ladoPrecios: 'COMPRA',
      visibles: ['USD:bolsa'],
      horaRecalculo: '09:30',
    };
    let guardada: ConfiguracionCotizacion | undefined;
    service.guardar(pedido).subscribe((c) => (guardada = c));

    const req = http.expectOne((r) => r.url.endsWith('/configuracion-cotizacion'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(pedido);
    req.flush({ ...pedido, vigente: 1000, vigenteDesde: '2026-10-16T11:00:00Z' });

    expect(guardada?.dolarPrecios).toBe('USD:bolsa');
    expect(guardada?.ladoPrecios).toBe('COMPRA');
    expect(guardada?.vigente).toBe(1000);
  });

  it('un 400 llega con el mensaje de cada campo del backend', () => {
    let error: unknown;
    service
      .guardar({ activa: true, dolarPrecios: 'EUR:oficial', ladoPrecios: 'VENTA', visibles: [], horaRecalculo: '25:00' })
      .subscribe({ error: (e: unknown) => (error = e) });

    http.expectOne((r) => r.url.endsWith('/configuracion-cotizacion')).flush(
      {
        message: 'Datos inválidos',
        errors: {
          dolarPrecios: 'El dólar de los precios tiene que ser un tipo de dólar. El euro y el real son solo para mostrar.',
          horaRecalculo: 'La hora tiene que tener el formato HH:mm, por ejemplo 08:00.',
        },
      },
      { status: 400, statusText: 'Bad Request' },
    );

    expect(error).toBeInstanceOf(HttpErrorResponse);
    expect(erroresDeCampos(error)).toEqual([
      'El dólar de los precios tiene que ser un tipo de dólar. El euro y el real son solo para mostrar.',
      'La hora tiene que tener el formato HH:mm, por ejemplo 08:00.',
    ]);
  });

  it('un 403 del Vendedor llega como error HTTP', () => {
    let error: unknown;
    service.obtener().subscribe({ error: (e: unknown) => (error = e) });

    http.expectOne((r) => r.url.endsWith('/configuracion-cotizacion')).flush(null, { status: 403, statusText: 'Forbidden' });

    expect((error as HttpErrorResponse).status).toBe(403);
  });
});
