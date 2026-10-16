import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Cotizacion, RecalculoCotizacion } from './cotizacion.models';
import { CotizacionService } from './cotizacion.service';

describe('CotizacionService', () => {
  let service: CotizacionService;
  let http: HttpTestingController;

  const respuesta: Cotizacion = {
    activa: true,
    desactualizada: false,
    ultimaRespuesta: '2026-10-16T11:05:00Z',
    visibles: [
      {
        codigo: 'USD:oficial',
        nombre: 'Oficial',
        compra: 950,
        venta: 1000,
        fuente: 'DOLARAPI',
        actualizadaEn: '2026-10-16T11:00:00Z',
        obtenidaEn: '2026-10-16T11:05:00Z',
        desactualizada: false,
        esDePrecios: true,
      },
      {
        codigo: 'EUR:oficial',
        nombre: 'Euro',
        compra: null,
        venta: null,
        fuente: null,
        actualizadaEn: null,
        obtenidaEn: null,
        desactualizada: false,
        esDePrecios: false,
      },
    ],
    precios: { codigo: 'USD:oficial', lado: 'VENTA', valor: 1000, desde: '2026-10-16T11:00:00Z' },
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(CotizacionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lee la cotización con GET /cotizacion: activa, desactualizada, última respuesta, visibles y precios', () => {
    let leida: Cotizacion | undefined;
    service.obtener().subscribe((c) => (leida = c));

    const req = http.expectOne((r) => r.url.endsWith('/cotizacion'));
    expect(req.request.method).toBe('GET');
    req.flush(respuesta);

    expect(leida?.activa).toBe(true);
    expect(leida?.desactualizada).toBe(false);
    expect(leida?.ultimaRespuesta).toBe('2026-10-16T11:05:00Z');
    expect(leida?.visibles.map((v) => [v.codigo, v.compra, v.venta, v.esDePrecios])).toEqual([
      ['USD:oficial', 950, 1000, true],
      ['EUR:oficial', null, null, false],
    ]);
    expect(leida?.precios).toEqual({ codigo: 'USD:oficial', lado: 'VENTA', valor: 1000, desde: '2026-10-16T11:00:00Z' });
  });

  it('distingue una cotización apagada y desactualizada de una activa', () => {
    let leida: Cotizacion | undefined;
    service.obtener().subscribe((c) => (leida = c));

    http.expectOne((r) => r.url.endsWith('/cotizacion')).flush({ ...respuesta, activa: false, desactualizada: true });

    expect(leida?.activa).toBe(false);
    expect(leida?.desactualizada).toBe(true);
  });

  it('«Recalcular ahora» pide POST /cotizacion/recalculo sin cuerpo y devuelve la cotización usada y la cantidad', () => {
    let resultado: RecalculoCotizacion | undefined;
    service.recalcular().subscribe((r) => (resultado = r));

    const req = http.expectOne((r) => r.url.endsWith('/cotizacion/recalculo'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({ cotizacion: 1100, articulosRecalculados: 3 });

    expect(resultado).toEqual({ cotizacion: 1100, articulosRecalculados: 3 });
  });

  describe('aviso de refresco para el menú lateral', () => {
    let avisos: number;

    beforeEach(() => {
      avisos = 0;
      service.refrescos$.subscribe(() => avisos++);
    });

    it('refrescar() avisa una vez a quien escucha', () => {
      service.refrescar();

      expect(avisos).toBe(1);
    });

    it('un recálculo exitoso avisa, recién cuando llega la respuesta', () => {
      service.recalcular().subscribe();
      expect(avisos).toBe(0);

      http.expectOne((r) => r.url.endsWith('/cotizacion/recalculo')).flush({ cotizacion: 1100, articulosRecalculados: 3 });

      expect(avisos).toBe(1);
    });

    it('un recálculo que falla no avisa', () => {
      service.recalcular().subscribe({ error: () => undefined });

      http
        .expectOne((r) => r.url.endsWith('/cotizacion/recalculo'))
        .flush({ message: 'apagada' }, { status: 409, statusText: 'Conflict' });

      expect(avisos).toBe(0);
    });
  });

  it('un 409 al recalcular llega como error con el mensaje del backend', () => {
    let error: unknown;
    service.recalcular().subscribe({ error: (e: unknown) => (error = e) });

    http
      .expectOne((r) => r.url.endsWith('/cotizacion/recalculo'))
      .flush({ message: 'La cotización está apagada' }, { status: 409, statusText: 'Conflict' });

    expect(error).toBeInstanceOf(HttpErrorResponse);
    expect((error as HttpErrorResponse).status).toBe(409);
    expect((error as HttpErrorResponse).error.message).toBe('La cotización está apagada');
  });
});
