import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PageResponse } from '../../core/api/page.models';
import { Cliente, ClienteRequest, CuentaCliente, documentoLegible } from './cliente.models';
import { ClientesService } from './clientes.service';

describe('ClientesService', () => {
  let service: ClientesService;
  let http: HttpTestingController;

  const ana: Cliente = {
    id: 4,
    razonSocial: 'Ana Gómez',
    tipoDocumento: 'DNI',
    numeroDocumento: '12345678',
    condicionIva: 'CONSUMIDOR_FINAL',
    activo: true,
    cuentaCorrienteHabilitada: false,
  };
  const pagina: PageResponse<Cliente> = { items: [ana], page: 0, size: 20, totalElements: 1, totalPages: 1 };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ClientesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lista con GET /clientes y sin parámetros si no hay filtros', () => {
    let recibida: PageResponse<Cliente> | undefined;
    service.buscar().subscribe((p) => (recibida = p));

    const req = http.expectOne((r) => r.url.endsWith('/clientes') && r.params.keys().length === 0);
    expect(req.request.method).toBe('GET');
    req.flush(pagina);

    expect(recibida).toEqual(pagina);
  });

  it('manda q, activos, page y size solo cuando están', () => {
    service.buscar({ q: '  perez ', soloActivos: true, page: 2, size: 10 }).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/clientes'));
    expect(req.request.params.get('q')).toBe('perez');
    expect(req.request.params.get('activos')).toBe('true');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('10');
    req.flush(pagina);
  });

  it('un q en blanco no se manda', () => {
    service.buscar({ q: '   ' }).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/clientes'));
    expect(req.request.params.has('q')).toBe(false);
    req.flush(pagina);
  });

  it('crea con POST /clientes', () => {
    const request: ClienteRequest = { razonSocial: 'Ana Gómez', condicionIva: 'CONSUMIDOR_FINAL' };
    let creado: Cliente | undefined;
    service.crear(request).subscribe((c) => (creado = c));

    const req = http.expectOne((r) => r.url.endsWith('/clientes'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(ana);

    expect(creado).toEqual(ana);
  });

  it('edita con PUT /clientes/{id}', () => {
    const request: ClienteRequest = { razonSocial: 'Ana G.', condicionIva: 'MONOTRIBUTISTA' };
    service.actualizar(4, request).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/clientes/4'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(request);
    req.flush({ ...ana, razonSocial: 'Ana G.' });
  });

  it('desactiva y reactiva con PATCH /clientes/{id}/activo', () => {
    service.cambiarActivo(4, false).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/clientes/4/activo'));
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ activo: false });
    req.flush({ ...ana, activo: false });
  });

  describe('cuenta corriente (CC-018, CC-019)', () => {
    it('consulta la cuenta con GET /clientes/{id}/cuenta-corriente sin importe: no manda el parámetro', () => {
      let cuenta: CuentaCliente | undefined;
      service.cuenta(4).subscribe((c) => (cuenta = c));

      const req = http.expectOne((r) => r.url.endsWith('/clientes/4/cuenta-corriente') && r.params.keys().length === 0);
      expect(req.request.method).toBe('GET');
      req.flush({ habilitada: true });

      expect(cuenta).toEqual({ habilitada: true });
    });

    it('con importe manda ?importe= y trae alcanza; el Dueño recibe además límite, saldo y disponible', () => {
      let cuenta: CuentaCliente | undefined;
      service.cuenta(4, 2500).subscribe((c) => (cuenta = c));

      const req = http.expectOne((r) => r.url.endsWith('/clientes/4/cuenta-corriente'));
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('importe')).toBe('2500');
      req.flush({ habilitada: true, alcanza: false, limite: 5000, saldo: 4000, disponible: 1000 });

      expect(cuenta?.alcanza).toBe(false);
      expect(cuenta?.disponible).toBe(1000);
    });

    it('un importe 0 también se manda (no se confunde con «sin importe»)', () => {
      service.cuenta(4, 0).subscribe();

      const req = http.expectOne((r) => r.url.endsWith('/clientes/4/cuenta-corriente'));
      expect(req.request.params.get('importe')).toBe('0');
      req.flush({ habilitada: true, alcanza: true });
    });

    it('fija el límite con PUT /clientes/{id}/cuenta-corriente/limite y el cuerpo {limite}', () => {
      let cuenta: CuentaCliente | undefined;
      service.cambiarLimite(4, 2500.5).subscribe((c) => (cuenta = c));

      const req = http.expectOne((r) => r.url.endsWith('/clientes/4/cuenta-corriente/limite'));
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ limite: 2500.5 });
      req.flush({ habilitada: true, limite: 2500.5, saldo: 0, disponible: 2500.5 });

      expect(cuenta?.limite).toBe(2500.5);
    });

    it('con 0 manda {limite: 0} (habilitada sin crédito)', () => {
      service.cambiarLimite(4, 0).subscribe();

      const req = http.expectOne((r) => r.url.endsWith('/clientes/4/cuenta-corriente/limite'));
      expect(req.request.body).toEqual({ limite: 0 });
      req.flush({ habilitada: true, limite: 0, saldo: 0, disponible: 0 });
    });

    it('con null manda {limite: null} (la clave siempre viaja: deshabilita la cuenta)', () => {
      service.cambiarLimite(4, null).subscribe();

      const req = http.expectOne((r) => r.url.endsWith('/clientes/4/cuenta-corriente/limite'));
      expect(req.request.body).toEqual({ limite: null });
      expect(JSON.stringify(req.request.body)).toBe('{"limite":null}');
      req.flush({ habilitada: false });
    });
  });

  it('trae uno con GET /clientes/{id}', () => {
    let leido: Cliente | undefined;
    service.obtener(4).subscribe((c) => (leido = c));

    const req = http.expectOne((r) => r.url.endsWith('/clientes/4'));
    expect(req.request.method).toBe('GET');
    req.flush(ana);

    expect(leido).toEqual(ana);
  });
});

describe('documentoLegible', () => {
  it('muestra el CUIT con guiones y el DNI con puntos', () => {
    expect(documentoLegible({ tipoDocumento: 'CUIT', numeroDocumento: '30712345671' })).toBe('CUIT 30-71234567-1');
    expect(documentoLegible({ tipoDocumento: 'CUIL', numeroDocumento: '20123456786' })).toBe('CUIL 20-12345678-6');
    expect(documentoLegible({ tipoDocumento: 'DNI', numeroDocumento: '12345678' })).toBe('DNI 12.345.678');
    expect(documentoLegible({ tipoDocumento: 'DNI', numeroDocumento: '1234567' })).toBe('DNI 1.234.567');
  });

  it('es vacío si el cliente no tiene documento', () => {
    expect(documentoLegible({})).toBe('');
  });
});
