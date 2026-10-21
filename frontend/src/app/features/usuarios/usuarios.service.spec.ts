import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EdicionUsuarioRequest, Usuario, UsuarioRequest } from './usuario.models';
import { UsuariosService } from './usuarios.service';

describe('UsuariosService', () => {
  let service: UsuariosService;
  let http: HttpTestingController;

  const lucia: Usuario = { id: 7, nombre: 'Lucía', email: 'lucia@tornillo.demo', rol: 'VENDEDOR', activo: true };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(UsuariosService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lista los activos con GET /usuarios sin parámetros', () => {
    let lista: Usuario[] | undefined;
    service.listar(false).subscribe((l) => (lista = l));

    const req = http.expectOne((r) => r.url.endsWith('/usuarios') && r.params.keys().length === 0);
    expect(req.request.method).toBe('GET');
    req.flush([lucia]);

    expect(lista).toEqual([lucia]);
  });

  it('lista también los inactivos con GET /usuarios?incluirInactivos=true', () => {
    service.listar(true).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/usuarios') && r.params.get('incluirInactivos') === 'true');
    expect(req.request.method).toBe('GET');
    req.flush([lucia]);
  });

  it('crea con POST /usuarios', () => {
    const request: UsuarioRequest = {
      nombre: 'Lucía',
      email: 'lucia@tornillo.demo',
      rol: 'VENDEDOR',
      password: 'clave1234',
    };
    let creado: Usuario | undefined;
    service.crear(request).subscribe((u) => (creado = u));

    const req = http.expectOne((r) => r.url.endsWith('/usuarios'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(lucia);

    expect(creado?.id).toBe(7);
  });

  it('actualiza con PUT /usuarios/{id}', () => {
    const request: EdicionUsuarioRequest = { nombre: 'Lucía P.', email: 'lucia@tornillo.demo', rol: 'DUENO' };
    service.actualizar(7, request).subscribe();

    const req = http.expectOne((r) => r.url.endsWith('/usuarios/7'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(request);
    req.flush({ ...lucia, ...request });
  });

  it('desactiva o reactiva con PATCH /usuarios/{id}/activo', () => {
    let devuelto: Usuario | undefined;
    service.cambiarActivo(7, false).subscribe((u) => (devuelto = u));

    const req = http.expectOne((r) => r.url.endsWith('/usuarios/7/activo'));
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ activo: false });
    req.flush({ ...lucia, activo: false });

    expect(devuelto?.activo).toBe(false);
  });

  it('resetea la contraseña con PUT /usuarios/{id}/password', () => {
    let listo = false;
    service.resetearContrasena(7, 'nueva1234').subscribe(() => (listo = true));

    const req = http.expectOne((r) => r.url.endsWith('/usuarios/7/password'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ password: 'nueva1234' });
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(listo).toBe(true);
  });
});
