import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { esOcupado, mensajeDe } from '../../../core/api/api-error';
import { CobranzaRechazada, CobranzaRequest, PendientesCuenta, Recibo, ReciboCobrado } from './cobranza.models';
import { CobranzaService, GENERAR_CLAVE_COBRO, esTransitorio, invalidaDe, rechazoDe } from './cobranza.service';

/**
 * Los cuerpos de estos mocks están copiados de los DTO reales del backend: PendientesResponse, CobranzaRequest,
 * ReciboResponse y CobranzaRechazadaResponse (ver CCT3-511).
 */
describe('CobranzaService', () => {
  let service: CobranzaService;
  let http: HttpTestingController;

  const pedido: CobranzaRequest = {
    clave: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
    clienteId: 8,
    medio: 'EFECTIVO',
    total: 1500,
    imputaciones: [{ comprobanteId: 31, importe: 1000 }],
  };

  const recibo: Recibo = {
    id: 5,
    numero: 12,
    fecha: '2026-10-19T10:30:00',
    clienteId: 8,
    clienteNombre: 'Ana Gómez',
    medio: 'EFECTIVO',
    total: 1500,
    aCuenta: 500,
    cobradoPor: 'Dueño Tornillo',
    imputaciones: [{ comprobanteId: 31, referencia: 'Venta N.º 7', importe: 1000 }],
    estado: 'EMITIDO',
    fechaAnulacion: null,
    anuladoPor: null,
    motivoAnulacion: null,
  };

  /** Lo que devuelve POST /recibos/{id}/anulacion: el mismo recibo con su auditoría (verify C6, respuesta real). */
  const reciboAnulado: Recibo = {
    ...recibo,
    estado: 'ANULADO',
    fechaAnulacion: '2026-10-20T19:49:39.487205',
    anuladoPor: 'Dueño Tornillo',
    motivoAnulacion: 'Cliente equivocado',
  };

  const pendientes: PendientesCuenta = {
    saldo: 1000,
    aCuenta: 0,
    comprobantes: [
      { comprobanteId: 31, referencia: 'Venta N.º 7', fecha: '2026-10-10T09:00:00', importe: 1000, imputado: 0, pendiente: 1000 },
    ],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(CobranzaService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('pendientes(id) hace GET a /clientes/{id}/cuenta-corriente/pendientes y devuelve el cuerpo', () => {
    let r: PendientesCuenta | undefined;
    service.pendientes(8).subscribe((x) => (r = x));
    const req = http.expectOne((q) => q.url.endsWith('/clientes/8/cuenta-corriente/pendientes'));
    expect(req.request.method).toBe('GET');
    req.flush(pendientes);
    expect(r).toEqual(pendientes);
  });

  it('cobrar manda POST a /cobranzas con el cuerpo del contrato, sin campos de más', () => {
    service.cobrar(pedido).subscribe();
    const req = http.expectOne((q) => q.url.endsWith('/cobranzas'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(pedido);
    expect(Object.keys(req.request.body).sort()).toEqual(['clave', 'clienteId', 'imputaciones', 'medio', 'total']);
    req.flush(recibo, { status: 201, statusText: 'Created' });
  });

  it('201 es un cobro nuevo y 200 es el mismo recibo de una clave ya registrada: los dos traen el mismo Recibo', () => {
    const resultados: ReciboCobrado[] = [];
    service.cobrar(pedido).subscribe((x) => resultados.push(x));
    http.expectOne((q) => q.url.endsWith('/cobranzas')).flush(recibo, { status: 201, statusText: 'Created' });
    service.cobrar(pedido).subscribe((x) => resultados.push(x));
    http.expectOne((q) => q.url.endsWith('/cobranzas')).flush(recibo, { status: 200, statusText: 'OK' });

    expect(resultados).toEqual([
      { recibo, repetida: false },
      { recibo, repetida: true },
    ]);
  });

  it('recibo(id) hace GET a /recibos/{id} (reimprimir)', () => {
    let r: Recibo | undefined;
    service.recibo(5).subscribe((x) => (r = x));
    const req = http.expectOne((q) => q.url.endsWith('/recibos/5'));
    expect(req.request.method).toBe('GET');
    req.flush(recibo);
    expect(r).toEqual(recibo);
  });

  it('la clave de un cobro es un UUID nuevo cada vez (crypto.randomUUID)', () => {
    const generar = TestBed.inject(GENERAR_CLAVE_COBRO);
    const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

    expect(generar()).toMatch(UUID);
    expect(generar()).not.toBe(generar());
  });

  describe('anular (CC-055..CC-060, CC-069)', () => {
    it('hace POST a /recibos/{id}/anulacion con {motivo} y nada más, y devuelve el recibo ANULADO', () => {
      let r: Recibo | undefined;
      service.anular(5, 'Cliente equivocado').subscribe((x) => (r = x));

      const req = http.expectOne((q) => q.url.endsWith('/recibos/5/anulacion'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ motivo: 'Cliente equivocado' });
      expect(Object.keys(req.request.body)).toEqual(['motivo']);
      req.flush(reciboAnulado);

      expect(r).toEqual(reciboAnulado);
      expect(r?.estado).toBe('ANULADO');
      expect(r?.anuladoPor).toBe('Dueño Tornillo');
      expect(r?.motivoAnulacion).toBe('Cliente equivocado');
    });

    it('manda el motivo tal como lo escribió el usuario (recortarlo es cosa del backend)', () => {
      service.anular(5, '  con espacios  ').subscribe();
      const req = http.expectOne((q) => q.url.endsWith('/recibos/5/anulacion'));
      expect(req.request.body).toEqual({ motivo: '  con espacios  ' });
      req.flush(reciboAnulado);
    });

    const anularConError = (status: number, error: unknown): unknown => {
      let err: unknown;
      service.anular(5, 'Cliente equivocado').subscribe({ error: (e) => (err = e) });
      const req = http.expectOne((q) => q.url.endsWith('/recibos/5/anulacion'));
      if (status === 0) req.error(new ProgressEvent('error'));
      else req.flush(error as object | null, { status, statusText: 'x' });
      return err;
    };

    it('409 RECIBO_YA_ANULADO se reconoce con quién y cuándo anuló', () => {
      const cuerpo = {
        status: 409,
        message: 'Este recibo ya fue anulado por Ana el 20/10/2026 19:49.',
        timestamp: '2026-10-20T22:45:31Z',
        motivo: 'RECIBO_YA_ANULADO',
        anuladoPor: 'Ana',
        fechaAnulacion: '2026-10-20T19:49:39.487205',
      };
      const err = anularConError(409, cuerpo);

      const rechazo = rechazoDe(err);
      expect(rechazo?.motivo).toBe('RECIBO_YA_ANULADO');
      expect(rechazo?.anuladoPor).toBe('Ana');
      expect(rechazo?.fechaAnulacion).toBe('2026-10-20T19:49:39.487205');
      expect(rechazo?.message).toBe('Este recibo ya fue anulado por Ana el 20/10/2026 19:49.');
      expect(esTransitorio(err)).toBe(false);
    });

    it.each([
      ['CAJA_CERRADA', 'Abrí la caja para anular.'],
      ['CAJA_DE_OTRO_DIA', 'La caja del 19/10 sigue abierta. Cerrala antes de anular.'],
    ] as const)('422 %s se reconoce y trae el message para mostrar', (motivo, message) => {
      const rechazo = rechazoDe(anularConError(422, { status: 422, message, timestamp: '2026-10-20T22:45:31Z', motivo }));

      expect(rechazo?.motivo).toBe(motivo);
      expect(rechazo?.message).toBe(message);
      expect(rechazo?.anuladoPor).toBeUndefined();
    });

    it('422 «El cliente está desactivado.» no trae motivo: no es un rechazo tipado, se muestra su message', () => {
      const err = anularConError(422, { status: 422, message: 'El cliente está desactivado.', timestamp: 'x' });

      expect(rechazoDe(err)).toBeNull();
      expect(mensajeDe(err, 'otro')).toBe('El cliente está desactivado.');
      expect(esTransitorio(err)).toBe(false);
    });

    it('400 de motivo trae errors.motivo y se lee como validación, no como rechazo', () => {
      const cuerpo = {
        status: 400,
        message: 'Datos inválidos',
        errors: { motivo: 'Escribí el motivo de la anulación (al menos 5 letras).' },
      };
      const err = anularConError(400, cuerpo);

      expect(rechazoDe(err)).toBeNull();
      expect(invalidaDe(err)?.errors?.['motivo']).toBe('Escribí el motivo de la anulación (al menos 5 letras).');
    });

    it('503 «El sistema está ocupado, reintentá.» se reconoce como ocupado (y es transitorio)', () => {
      const err = anularConError(503, { status: 503, message: 'El sistema está ocupado, reintentá.' });

      expect(esOcupado(err)).toBe(true);
      expect(mensajeDe(err, 'otro')).toBe('El sistema está ocupado, reintentá.');
      expect(rechazoDe(err)).toBeNull();
      expect(esTransitorio(err)).toBe(true);
    });

    it('red caída y 500 no son un rechazo ni «ocupado»: son transitorios y se reintenta', () => {
      for (const err of [anularConError(0, null), anularConError(500, { status: 500, message: 'Error interno' })]) {
        expect(rechazoDe(err)).toBeNull();
        expect(esOcupado(err)).toBe(false);
        expect(esTransitorio(err)).toBe(true);
      }
    });

    it('403 y 404 no son rechazos tipados ni transitorios', () => {
      for (const status of [403, 404]) {
        const err = anularConError(status, { status, message: 'No.' });
        expect(rechazoDe(err)).toBeNull();
        expect(esOcupado(err)).toBe(false);
        expect(esTransitorio(err)).toBe(false);
      }
    });

    it('un error que no es HTTP no es «ocupado»', () => {
      expect(esOcupado(new Error('x'))).toBe(false);
    });
  });

  describe('errores', () => {
    const cobrarConError = (status: number, error: unknown): unknown => {
      let err: unknown;
      service.cobrar(pedido).subscribe({ error: (e) => (err = e) });
      const req = http.expectOne((q) => q.url.endsWith('/cobranzas'));
      if (status === 0) req.error(new ProgressEvent('error'));
      else req.flush(error as object | null, { status, statusText: 'x' });
      return err;
    };

    it('409 IMPUTACION_EXCEDE_DEUDA trae la deuda actual de los comprobantes afectados', () => {
      const cuerpo: CobranzaRechazada = {
        status: 409,
        message: 'La deuda de algún comprobante cambió. Revisá la imputación y confirmá de nuevo.',
        timestamp: '2026-10-19T13:30:00Z',
        motivo: 'IMPUTACION_EXCEDE_DEUDA',
        comprobantes: [{ comprobanteId: 31, referencia: 'Venta N.º 7', pendiente: 0 }],
      };
      const err = cobrarConError(409, cuerpo);

      expect(rechazoDe(err)).toEqual(cuerpo);
      expect(esTransitorio(err)).toBe(false);
    });

    it.each(['CAJA_CERRADA', 'CAJA_DE_OTRO_DIA', 'CLAVE_CON_OTRO_CONTENIDO'] as const)('422 %s se reconoce', (motivo) => {
      const cuerpo = { status: 422, message: 'Abrí la caja para cobrar.', timestamp: '2026-10-19T13:30:00Z', motivo };
      const rechazo = rechazoDe(cobrarConError(422, cuerpo));

      expect(rechazo?.motivo).toBe(motivo);
      expect(rechazo?.message).toBe('Abrí la caja para cobrar.');
      expect(rechazo?.comprobantes).toBeUndefined();
    });

    it('422 «El cliente está desactivado.» no trae motivo: no es un rechazo tipado, la pantalla muestra su mensaje', () => {
      const err = cobrarConError(422, { status: 422, message: 'El cliente está desactivado.', timestamp: '2026-10-19T13:30:00Z' });

      expect(rechazoDe(err)).toBeNull();
      expect(esTransitorio(err)).toBe(false);
    });

    it('404 (comprobante o cliente fuera de la cuenta) y 403 (Vendedor) no son rechazos tipados ni transitorios', () => {
      for (const status of [403, 404]) {
        const err = cobrarConError(status, { status, message: 'No.' });
        expect(rechazoDe(err)).toBeNull();
        expect(esTransitorio(err)).toBe(false);
      }
    });

    it('400 trae message y errors por campo: «imputaciones» (conjunto) e «imputaciones[0].importe» (renglón)', () => {
      const cuerpo = {
        status: 400,
        message: 'Hay datos inválidos',
        errors: { imputaciones: 'Lo imputado supera el total del recibo.', 'imputaciones[0].importe': 'El importe admite hasta 2 decimales.' },
      };
      const err = cobrarConError(400, cuerpo);

      expect(rechazoDe(err)).toBeNull();
      expect(invalidaDe(err)).toEqual(cuerpo);
      expect(esTransitorio(err)).toBe(false);
    });

    it('un 400 sin cuerpo reconocible no es una validación tipada', () => {
      expect(invalidaDe(cobrarConError(400, 'texto'))).toBeNull();
      expect(invalidaDe(new Error('x'))).toBeNull();
    });

    it('una falla de red (status 0) o un 5xx no son un rechazo: se reintenta con la misma clave', () => {
      const red = cobrarConError(0, null);
      const interno = cobrarConError(500, { status: 500, message: 'Error interno' });

      expect(rechazoDe(red)).toBeNull();
      expect(rechazoDe(interno)).toBeNull();
      expect(esTransitorio(red)).toBe(true);
      expect(esTransitorio(interno)).toBe(true);
      expect(esTransitorio(new HttpErrorResponse({ status: 503 }))).toBe(true);
    });

    it('un error que no es HTTP se trata como transitorio y no como rechazo', () => {
      expect(rechazoDe(new Error('x'))).toBeNull();
      expect(esTransitorio(new Error('x'))).toBe(true);
    });

    it('un cuerpo 409 con otro motivo no se confunde con un rechazo de cobranza', () => {
      const err = new HttpErrorResponse({ status: 409, error: { status: 409, message: 'x', motivo: 'PRECIOS_CAMBIADOS' } });
      expect(rechazoDe(err)).toBeNull();
    });
  });
});
