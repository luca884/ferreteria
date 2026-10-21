import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { LoginResponse } from './auth.models';

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  const respuesta: LoginResponse = {
    token: 'jwt-de-prueba',
    sesion: {
      userId: 2,
      nombre: 'Dueno El Tornillo',
      email: 'dueno@tornillo.demo',
      rol: 'DUENO',
      ferreteriaId: 1,
      ferreteriaNombre: 'Ferreteria El Tornillo',
    },
  };

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('al loguearse guarda el token y la sesión', () => {
    service.login('dueno@tornillo.demo', 'demo1234').subscribe();
    http.expectOne((r) => r.url.endsWith('/auth/login')).flush(respuesta);

    expect(service.autenticado()).toBe(true);
    expect(service.esDueno()).toBe(true);
    expect(service.sesion()?.ferreteriaNombre).toBe('Ferreteria El Tornillo');
    expect(localStorage.getItem('ferreteria.token')).toBe('jwt-de-prueba');
  });

  it('al salir borra todo', () => {
    service.login('dueno@tornillo.demo', 'demo1234').subscribe();
    http.expectOne((r) => r.url.endsWith('/auth/login')).flush(respuesta);

    service.logout();

    expect(service.autenticado()).toBe(false);
    expect(service.sesion()).toBeNull();
    expect(localStorage.getItem('ferreteria.token')).toBeNull();
  });

  describe('cambiarContrasena', () => {
    it('hace PUT /auth/password con la actual y la nueva', () => {
      let listo = false;
      service.cambiarContrasena('vieja1234', 'nueva1234').subscribe(() => (listo = true));

      const req = http.expectOne((r) => r.url.endsWith('/auth/password'));
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ passwordActual: 'vieja1234', passwordNueva: 'nueva1234' });
      req.flush(null, { status: 204, statusText: 'No Content' });

      expect(listo).toBe(true);
    });
  });

  describe('refrescarSesion', () => {
    const nueva = { ...respuesta.sesion, nombre: 'Dueño Renombrado' };

    beforeEach(() => {
      service.login('dueno@tornillo.demo', 'demo1234').subscribe();
      http.expectOne((r) => r.url.endsWith('/auth/login')).flush(respuesta);
    });

    it('hace GET /auth/me, actualiza el signal y la sesión guardada', () => {
      let devuelta: unknown;
      service.refrescarSesion().subscribe((s) => (devuelta = s));

      const req = http.expectOne((r) => r.url.endsWith('/auth/me'));
      expect(req.request.method).toBe('GET');
      req.flush(nueva);

      expect(devuelta).toEqual(nueva);
      expect(service.sesion()?.nombre).toBe('Dueño Renombrado');
      expect(JSON.parse(localStorage.getItem('ferreteria.sesion')!).nombre).toBe('Dueño Renombrado');
      expect(service.token()).toBe('jwt-de-prueba');
    });

    it('si falla no pisa la sesión que había', () => {
      let fallo = false;
      service.refrescarSesion().subscribe({ error: () => (fallo = true) });

      http.expectOne((r) => r.url.endsWith('/auth/me')).flush({ message: 'x' }, { status: 500, statusText: 'Error' });

      expect(fallo).toBe(true);
      expect(service.sesion()?.nombre).toBe('Dueno El Tornillo');
      expect(JSON.parse(localStorage.getItem('ferreteria.sesion')!).nombre).toBe('Dueno El Tornillo');
    });
  });
});
