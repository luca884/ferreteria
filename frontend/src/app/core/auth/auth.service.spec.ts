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

  it('esSuperAdmin es verdadero solo con el rol SUPER_ADMIN', () => {
    expect(service.esSuperAdmin()).toBe(false);

    service.login('dueno@tornillo.demo', 'demo1234').subscribe();
    http.expectOne((r) => r.url.endsWith('/auth/login')).flush(respuesta);
    expect(service.esSuperAdmin()).toBe(false);

    service.logout();
    service.login('admin@demo.local', 'demo1234').subscribe();
    http
      .expectOne((r) => r.url.endsWith('/auth/login'))
      .flush({
        ...respuesta,
        sesion: { ...respuesta.sesion, rol: 'SUPER_ADMIN', ferreteriaId: null, ferreteriaNombre: null },
      });
    expect(service.esSuperAdmin()).toBe(true);
    expect(service.esDueno()).toBe(false);
  });

  it('al salir borra todo', () => {
    service.login('dueno@tornillo.demo', 'demo1234').subscribe();
    http.expectOne((r) => r.url.endsWith('/auth/login')).flush(respuesta);

    service.logout();

    expect(service.autenticado()).toBe(false);
    expect(service.sesion()).toBeNull();
    expect(localStorage.getItem('ferreteria.token')).toBeNull();
  });

  describe('marca de cambio obligatorio de contraseña', () => {
    const conMarca: LoginResponse = { ...respuesta, sesion: { ...respuesta.sesion, debeCambiarContrasena: true } };

    it('sale de la sesión: el login la prende y una sesión sin el dato (guardada antes) no la tiene', () => {
      expect(service.debeCambiarContrasena()).toBe(false);

      service.login('dueno@tornillo.demo', 'demo1234').subscribe();
      http.expectOne((r) => r.url.endsWith('/auth/login')).flush(conMarca);
      expect(service.debeCambiarContrasena()).toBe(true);

      service.logout();
      service.login('dueno@tornillo.demo', 'demo1234').subscribe();
      http.expectOne((r) => r.url.endsWith('/auth/login')).flush(respuesta);
      expect(service.debeCambiarContrasena()).toBe(false);
    });

    it('al cambiar la contraseña se apaga, en el signal y en la sesión guardada', () => {
      service.login('dueno@tornillo.demo', 'demo1234').subscribe();
      http.expectOne((r) => r.url.endsWith('/auth/login')).flush(conMarca);

      service.cambiarContrasena('vieja1234', 'nueva1234').subscribe();
      http.expectOne((r) => r.url.endsWith('/auth/password')).flush(null, { status: 204, statusText: 'No Content' });

      expect(service.debeCambiarContrasena()).toBe(false);
      expect(JSON.parse(localStorage.getItem('ferreteria.sesion')!).debeCambiarContrasena).toBe(false);
      expect(service.sesion()?.nombre).toBe('Dueno El Tornillo');
    });

    it('si el cambio falla, la marca sigue prendida', () => {
      service.login('dueno@tornillo.demo', 'demo1234').subscribe();
      http.expectOne((r) => r.url.endsWith('/auth/login')).flush(conMarca);

      service.cambiarContrasena('mala', 'nueva1234').subscribe({ error: () => undefined });
      http
        .expectOne((r) => r.url.endsWith('/auth/password'))
        .flush({ errors: { passwordActual: 'x' } }, { status: 400, statusText: 'Bad Request' });

      expect(service.debeCambiarContrasena()).toBe(true);
    });
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
