import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ConfirmarVentaRequest, VentaCobrada, VentaRechazada, VentaResponse } from './venta.models';
import { VentaService, invalidaDe, rechazoDe } from './venta.service';

describe('VentaService', () => {
  let service: VentaService;
  let http: HttpTestingController;

  const pedido: ConfirmarVentaRequest = {
    clave: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
    renglones: [{ articuloId: 1, cantidad: 3, precioUnitario: 170 }],
    totalMostrado: 510,
    medioPago: 'EFECTIVO',
  };

  const venta: VentaResponse = {
    ventaId: 31,
    numero: 12,
    total: 510,
    fecha: '2026-10-15T14:03:00',
    medioPago: 'EFECTIVO',
    renglones: [
      {
        renglon: 1,
        articuloId: 1,
        descripcion: 'Tornillo',
        unidad: 'UNIDAD',
        cantidad: 3,
        precioUnitario: 170,
        alicuotaIva: 'IVA_21',
        total: 510,
        neto: 421.49,
        iva: 88.51,
      },
    ],
    sinStock: [],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(VentaService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('hace POST a /ventas con el cuerpo del contrato, sin campos de más', () => {
    service.cobrar(pedido).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/ventas'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(pedido);
    expect(Object.keys(req.request.body).sort()).toEqual(['clave', 'medioPago', 'renglones', 'totalMostrado']);
    req.flush(venta, { status: 201, statusText: 'Created' });
  });

  it('201 es una venta nueva', () => {
    let r: VentaCobrada | undefined;
    service.cobrar(pedido).subscribe((x) => (r = x));
    http.expectOne((q) => q.url.endsWith('/ventas')).flush(venta, { status: 201, statusText: 'Created' });
    expect(r).toEqual({ venta, repetida: false });
  });

  it('200 es una venta repetida (misma clave y contenido)', () => {
    let r: VentaCobrada | undefined;
    service.cobrar(pedido).subscribe((x) => (r = x));
    http.expectOne((q) => q.url.endsWith('/ventas')).flush(venta, { status: 200, statusText: 'OK' });
    expect(r).toEqual({ venta, repetida: true });
  });

  it('expone el 404 con su cuerpo', () => {
    const cuerpo: VentaRechazada = {
      status: 404,
      message: 'Hay un artículo del ticket que ya no está disponible',
      timestamp: '2026-10-15T14:03:00Z',
      motivo: 'ARTICULO_INEXISTENTE',
      articulos: [{ articuloId: 99 }],
    };
    let err: unknown;
    service.cobrar(pedido).subscribe({ error: (e) => (err = e) });
    http.expectOne((q) => q.url.endsWith('/ventas')).flush(cuerpo, { status: 404, statusText: 'Not Found' });
    expect(err).toBeInstanceOf(HttpErrorResponse);
    expect((err as HttpErrorResponse).status).toBe(404);
    expect(rechazoDe(err)).toEqual(cuerpo);
  });

  it('expone el 409 de precios con total y precios por artículo', () => {
    const cuerpo: VentaRechazada = {
      status: 409,
      message: 'Cambió el precio de 1 artículo. Revisá el total y confirmá de nuevo.',
      timestamp: '2026-10-15T14:03:00Z',
      motivo: 'PRECIOS_CAMBIADOS',
      total: 540,
      articulos: [
        { articuloId: 1, descripcion: 'Tornillo', precioMostrado: 170, precioActual: 180, totalRenglon: 540 },
      ],
    };
    let err: unknown;
    service.cobrar(pedido).subscribe({ error: (e) => (err = e) });
    http.expectOne((q) => q.url.endsWith('/ventas')).flush(cuerpo, { status: 409, statusText: 'Conflict' });
    expect(rechazoDe(err)?.motivo).toBe('PRECIOS_CAMBIADOS');
    expect(rechazoDe(err)?.total).toBe(540);
  });

  it('expone el 422 de baja y el de clave con otro contenido (articulos vacío)', () => {
    const baja: VentaRechazada = {
      status: 422,
      message: '"Martillo" fue dado de baja. Sacalo del ticket para seguir.',
      timestamp: 't',
      motivo: 'ARTICULO_DADO_DE_BAJA',
      articulos: [{ articuloId: 5, descripcion: 'Martillo' }],
    };
    const otro: VentaRechazada = {
      status: 422,
      message: 'Esta venta ya se registró con otros datos',
      timestamp: 't',
      motivo: 'CLAVE_CON_OTRO_CONTENIDO',
      articulos: [],
    };
    let e1: unknown;
    service.cobrar(pedido).subscribe({ error: (e) => (e1 = e) });
    http.expectOne((q) => q.url.endsWith('/ventas')).flush(baja, { status: 422, statusText: 'x' });
    expect(rechazoDe(e1)).toEqual(baja);

    let e2: unknown;
    service.cobrar(pedido).subscribe({ error: (e) => (e2 = e) });
    http.expectOne((q) => q.url.endsWith('/ventas')).flush(otro, { status: 422, statusText: 'x' });
    expect(rechazoDe(e2)).toEqual(otro);
  });

  describe('rechazoDe', () => {
    it('reconoce los rechazos por caja (CU-10): sin caja abierta y caja de otro día', () => {
      for (const motivo of ['CAJA_CERRADA', 'CAJA_DE_OTRO_DIA'] as const) {
        const cuerpo = { status: 422, message: 'Abrí la caja para vender.', timestamp: 't', motivo, articulos: [] };
        expect(rechazoDe(new HttpErrorResponse({ status: 422, error: cuerpo }))).toEqual(cuerpo);
      }
    });

    it('devuelve null para errores que no son un rechazo de venta', () => {
      expect(rechazoDe(new Error('x'))).toBeNull();
      expect(rechazoDe(new HttpErrorResponse({ status: 500, error: { message: 'Error interno' } }))).toBeNull();
      expect(rechazoDe(new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') }))).toBeNull();
      expect(
        rechazoDe(new HttpErrorResponse({ status: 400, error: { status: 400, message: 'Datos inválidos' } })),
      ).toBeNull();
      expect(rechazoDe(new HttpErrorResponse({ status: 404, error: 'texto' }))).toBeNull();
    });
  });

  describe('invalidaDe', () => {
    it('devuelve el cuerpo de un 400 con message y errors por campo', () => {
      const cuerpo = { status: 400, message: 'Datos inválidos', errors: { 'renglones[0].cantidad': 'Mal' } };
      expect(invalidaDe(new HttpErrorResponse({ status: 400, error: cuerpo }))).toEqual(cuerpo);
    });

    it('acepta un 400 sin errors', () => {
      const cuerpo = { status: 400, message: 'El cuerpo del pedido no es válido' };
      expect(invalidaDe(new HttpErrorResponse({ status: 400, error: cuerpo }))).toEqual(cuerpo);
    });

    it('devuelve null si no es un 400 con cuerpo reconocible', () => {
      expect(invalidaDe(new Error('x'))).toBeNull();
      expect(invalidaDe(new HttpErrorResponse({ status: 500, error: { message: 'x' } }))).toBeNull();
      expect(invalidaDe(new HttpErrorResponse({ status: 400, error: null }))).toBeNull();
      expect(invalidaDe(new HttpErrorResponse({ status: 400, error: 'texto' }))).toBeNull();
      expect(invalidaDe(new HttpErrorResponse({ status: 400, error: { status: 400 } }))).toBeNull();
      expect(
        invalidaDe(new HttpErrorResponse({ status: 400, error: { message: 'x', errors: 'mal' } })),
      ).toBeNull();
    });
  });
});
