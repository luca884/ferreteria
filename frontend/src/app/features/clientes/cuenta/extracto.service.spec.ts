import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { mensajeDe } from '../../../core/api/api-error';
import { Extracto, FILTRO_VACIO, FiltroExtracto } from './extracto.models';
import { ExtractoService } from './extracto.service';

/** El cuerpo del mock está copiado de una respuesta real de ExtractoResponse (verify C6, foco 6). */
const extracto: Extracto = {
  clienteId: 8,
  clienteNombre: 'Ana Gómez',
  saldoActual: 0,
  desde: null,
  hasta: null,
  tipo: null,
  saldoInicial: 0,
  totalDebe: 10,
  totalHaber: 10,
  saldoFinal: 0,
  movimientos: {
    items: [
      {
        id: 2,
        fecha: '2026-10-20T19:49:39.487205',
        tipo: 'ANULACION',
        referencia: 'Anulación Recibo N.º 1',
        debe: 10,
        haber: 0,
        saldo: 0,
        usuario: 'Dueno CC',
        reciboId: 1,
        comprobanteId: null,
        motivo: 'Prueba de anulación',
        excedioLimite: false,
        autorizadoPor: null,
        anulado: false,
      },
    ],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
  },
  truncado: false,
};

describe('ExtractoService (CC-072, CC-099)', () => {
  let service: ExtractoService;
  let http: HttpTestingController;
  const filtro = (cambios: Partial<FiltroExtracto> = {}): FiltroExtracto => ({ ...FILTRO_VACIO, ...cambios });

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ExtractoService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('extracto() hace GET a /clientes/{id}/cuenta-corriente/extracto con page y size, y devuelve el cuerpo', () => {
    let r: Extracto | undefined;
    service.extracto(8, filtro(), 0).subscribe((x) => (r = x));

    const req = http.expectOne((q) => q.url.endsWith('/clientes/8/cuenta-corriente/extracto'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    expect(req.request.params.keys().sort()).toEqual(['page', 'size']);
    req.flush(extracto);
    expect(r).toEqual(extracto);
  });

  it('manda los filtros con valor y el número de página pedido; los vacíos no viajan', () => {
    service.extracto(8, filtro({ desde: '2026-10-01', tipo: 'COBRO' }), 3).subscribe();

    const req = http.expectOne((q) => q.url.endsWith('/clientes/8/cuenta-corriente/extracto'));
    expect(req.request.params.get('desde')).toBe('2026-10-01');
    expect(req.request.params.get('tipo')).toBe('COBRO');
    expect(req.request.params.get('page')).toBe('3');
    expect(req.request.params.has('hasta')).toBe(false);
    expect(req.request.params.has('impresion')).toBe(false);
    req.flush(extracto);
  });

  it('paraImprimir() hace UN GET con impresion=true y sin page ni size', () => {
    let r: Extracto | undefined;
    service.paraImprimir(8, filtro({ hasta: '2026-10-31' })).subscribe((x) => (r = x));

    const req = http.expectOne((q) => q.url.endsWith('/clientes/8/cuenta-corriente/extracto'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('impresion')).toBe('true');
    expect(req.request.params.get('hasta')).toBe('2026-10-31');
    expect(req.request.params.has('page')).toBe(false);
    expect(req.request.params.has('size')).toBe(false);
    req.flush({ ...extracto, truncado: true });
    expect(r?.truncado).toBe(true);
  });

  it.each([
    [400, 'Parámetro inválido: tipo'],
    [403, 'No tenés permiso para hacer esto'],
    [404, 'Cliente no encontrado'],
  ])('un %i se propaga como HttpErrorResponse con su message', (status, message) => {
    let err: unknown;
    service.extracto(8, filtro(), 0).subscribe({ error: (e) => (err = e) });

    http.expectOne((q) => q.url.endsWith('/extracto')).flush({ status, message }, { status, statusText: 'x' });

    expect(err).toBeInstanceOf(HttpErrorResponse);
    expect((err as HttpErrorResponse).status).toBe(status);
    expect(mensajeDe(err, 'otro')).toBe(message);
  });

  it.each([
    [400, 'Parámetro inválido: hasta'],
    [403, 'No tenés permiso para hacer esto'],
    [404, 'Cliente no encontrado'],
  ])('paraImprimir(): un %i se propaga como HttpErrorResponse con su message (no se traga ni se convierte)', (status, message) => {
    let err: unknown;
    let respuesta: Extracto | undefined;
    service.paraImprimir(8, filtro({ hasta: '2026-10-31' })).subscribe({ next: (x) => (respuesta = x), error: (e) => (err = e) });

    http.expectOne((q) => q.url.endsWith('/extracto')).flush({ status, message }, { status, statusText: 'x' });

    expect(respuesta).toBeUndefined();
    expect(err).toBeInstanceOf(HttpErrorResponse);
    expect((err as HttpErrorResponse).status).toBe(status);
    expect(mensajeDe(err, 'otro')).toBe(message);
  });

  it('el 400 de desde > hasta trae errors.desde (el front lo muestra tal cual)', () => {
    let err: HttpErrorResponse | undefined;
    service.extracto(8, filtro({ desde: '2026-10-10', hasta: '2026-10-01' }), 0).subscribe({ error: (e) => (err = e) });

    http
      .expectOne((q) => q.url.endsWith('/extracto'))
      .flush(
        { status: 400, message: 'Datos inválidos', errors: { desde: 'La fecha desde no puede ser posterior a hasta.' } },
        { status: 400, statusText: 'x' },
      );

    expect(err?.error.errors.desde).toBe('La fecha desde no puede ser posterior a hasta.');
  });
});
