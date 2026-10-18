import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FormaPago, FormaPagoRequest } from './forma-pago.models';
import { FormasPagoService } from './formas-pago.service';

describe('FormasPagoService', () => {
  let service: FormasPagoService;
  let http: HttpTestingController;

  const credito: FormaPago = {
    id: 3,
    nombre: 'Crédito',
    medio: 'CREDITO',
    recargoPorcentaje: 0,
    activa: true,
    orden: 3,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(FormasPagoService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lista todas con GET /formas-pago', () => {
    let lista: FormaPago[] | undefined;
    service.listar().subscribe((l) => (lista = l));

    const req = http.expectOne((r) => r.url.endsWith('/formas-pago') && r.params.keys().length === 0);
    expect(req.request.method).toBe('GET');
    req.flush([credito]);

    expect(lista).toEqual([credito]);
  });

  it('lista solo las activas con GET /formas-pago?activas=true', () => {
    let lista: FormaPago[] | undefined;
    service.listar(true).subscribe((l) => (lista = l));

    const req = http.expectOne((r) => r.url.endsWith('/formas-pago') && r.params.get('activas') === 'true');
    expect(req.request.method).toBe('GET');
    req.flush([credito]);

    expect(lista).toEqual([credito]);
  });

  it('crea con POST /formas-pago', () => {
    const request: FormaPagoRequest = {
      nombre: 'Visa 6 cuotas',
      medio: 'CREDITO',
      recargoPorcentaje: 18.5,
      activa: true,
    };
    let creada: FormaPago | undefined;
    service.crear(request).subscribe((f) => (creada = f));

    const req = http.expectOne((r) => r.url.endsWith('/formas-pago'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush({ id: 5, orden: 5, ...request });

    expect(creada?.id).toBe(5);
  });

  it('actualiza con PUT /formas-pago/{id}', () => {
    const request: FormaPagoRequest = { nombre: 'Crédito', medio: 'CREDITO', recargoPorcentaje: 10, activa: false };
    let actualizada: FormaPago | undefined;
    service.actualizar(3, request).subscribe((f) => (actualizada = f));

    const req = http.expectOne((r) => r.url.endsWith('/formas-pago/3'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(request);
    req.flush({ ...credito, ...request });

    expect(actualizada?.activa).toBe(false);
  });
});
