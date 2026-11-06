import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  ConfirmarVentaRequest,
  FacturacionDelMostrador,
  ProvisoriaAutorizada,
  VentaCobrada,
  VentaRechazada,
  VentaResponse,
} from './venta.models';
import { VentaService, invalidaDe, rechazoDe } from './venta.service';

describe('VentaService', () => {
  let service: VentaService;
  let http: HttpTestingController;

  const pedido: ConfirmarVentaRequest = {
    clave: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
    renglones: [{ articuloId: 1, cantidad: 3, precioUnitario: 170 }],
    totalMostrado: 510,
    formaPagoId: 1,
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
        bruto: 510,
        total: 510,
        neto: 421.49,
        iva: 88.51,
      },
    ],
    sinStock: [],
    facturable: true,
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
    expect(Object.keys(req.request.body).sort()).toEqual(['clave', 'formaPagoId', 'renglones', 'totalMostrado']);
    req.flush(venta, { status: 201, statusText: 'Created' });
  });

  it('manda autorizaExceso solo cuando el pedido lo trae (CC-013)', () => {
    service.cobrar({ ...pedido, autorizaExceso: true, clienteId: 8 }).subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/ventas'));
    expect(req.request.body.autorizaExceso).toBe(true);
    expect(req.request.body.clienteId).toBe(8);
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

  it('lee con GET /ventas/facturacion qué comprobante sale y con qué letra (C3)', () => {
    const cuerpo: FacturacionDelMostrador = {
      factura: true,
      ambiente: 'SIMULADOR',
      letras: { CONSUMIDOR_FINAL: 'B', RESPONSABLE_INSCRIPTO: 'A', MONOTRIBUTISTA: 'A', EXENTO: 'B' },
    };
    let leida: FacturacionDelMostrador | undefined;
    service.facturacion().subscribe((f) => (leida = f));
    const req = http.expectOne((r) => r.url.endsWith('/ventas/facturacion'));
    expect(req.request.method).toBe('GET');
    req.flush(cuerpo);
    expect(leida).toEqual(cuerpo);
  });

  it('lee con GET /ventas/provisorias-autorizadas las ventas de la caja abierta a las que les llegó el CAE (C7)', () => {
    const cuerpo: ProvisoriaAutorizada[] = [{ ventaId: 1, numero: 12, factura: 'Factura B 0001-00000045' }];
    let leidas: ProvisoriaAutorizada[] | undefined;
    service.provisoriasAutorizadas().subscribe((l) => (leidas = l));
    const req = http.expectOne((r) => r.url.endsWith('/ventas/provisorias-autorizadas'));
    expect(req.request.method).toBe('GET');
    req.flush(cuerpo);
    expect(leidas).toEqual(cuerpo);
  });

  describe('rechazoDe', () => {
    it('reconoce los rechazos por caja (CU-10): sin caja abierta y caja de otro día', () => {
      for (const motivo of ['CAJA_CERRADA', 'CAJA_DE_OTRO_DIA'] as const) {
        const cuerpo = { status: 422, message: 'Abrí la caja para vender.', timestamp: 't', motivo, articulos: [] };
        expect(rechazoDe(new HttpErrorResponse({ status: 422, error: cuerpo }))).toEqual(cuerpo);
      }
    });

    it('reconoce el rechazo por límite de cuenta corriente y expone el objeto cuentaCorriente del Dueño (CC-013)', () => {
      const cuerpo: VentaRechazada = {
        status: 422,
        message: 'Esta venta supera el límite de cuenta corriente del cliente. Autorizala para seguir.',
        timestamp: 't',
        motivo: 'LIMITE_CUENTA_CORRIENTE',
        articulos: [],
        cuentaCorriente: { puedeAutorizar: true, saldo: 4000, limite: 5000, disponible: 1000, importe: 2500, exceso: 1500 },
      };
      const rechazo = rechazoDe(new HttpErrorResponse({ status: 422, error: cuerpo }));
      expect(rechazo).toEqual(cuerpo);
      expect(rechazo?.cuentaCorriente?.exceso).toBe(1500);
    });

    it('el rechazo por límite del Vendedor trae puedeAutorizar false y ninguna cifra', () => {
      const cuerpo: VentaRechazada = {
        status: 422,
        message: 'Esta venta supera el límite de cuenta corriente del cliente. Pedile al Dueño que la autorice.',
        timestamp: 't',
        motivo: 'LIMITE_CUENTA_CORRIENTE',
        articulos: [],
        cuentaCorriente: { puedeAutorizar: false },
      };
      const rechazo = rechazoDe(new HttpErrorResponse({ status: 422, error: cuerpo }));
      expect(rechazo?.cuentaCorriente).toEqual({ puedeAutorizar: false });
    });

    it('reconoce el rechazo por cuenta corriente no habilitada', () => {
      const cuerpo: VentaRechazada = {
        status: 422,
        message: 'Ana Gómez no tiene cuenta corriente habilitada.',
        timestamp: 't',
        motivo: 'CUENTA_CORRIENTE_NO_HABILITADA',
        articulos: [],
      };
      expect(rechazoDe(new HttpErrorResponse({ status: 422, error: cuerpo }))).toEqual(cuerpo);
    });

    it('reconoce el rechazo por Consumidor Final sin datos (GDS-32)', () => {
      const cuerpo: VentaRechazada = {
        status: 422,
        message: 'Desde $ 10.000.000,00 la venta a Consumidor Final necesita identificar al comprador.',
        timestamp: 't',
        motivo: 'CONSUMIDOR_FINAL_SIN_DATOS',
        articulos: [],
      };
      expect(rechazoDe(new HttpErrorResponse({ status: 422, error: cuerpo }))).toEqual(cuerpo);
    });

    it('reconoce el rechazo por cliente sin datos fiscales (D4)', () => {
      const cuerpo: VentaRechazada = {
        status: 422,
        message: 'Para facturarle a Ana Gómez falta su CUIT o su domicilio: completalos en Clientes.',
        timestamp: 't',
        motivo: 'CLIENTE_SIN_DATOS_FISCALES',
        articulos: [],
      };
      expect(rechazoDe(new HttpErrorResponse({ status: 422, error: cuerpo }))).toEqual(cuerpo);
    });

    it('un 403 (autorizar el exceso siendo Vendedor) NO es un rechazo de venta', () => {
      const cuerpo = { status: 403, message: 'Autorizar el exceso es solo del Dueño' };
      expect(rechazoDe(new HttpErrorResponse({ status: 403, error: cuerpo }))).toBeNull();
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
