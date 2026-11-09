import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { vi } from 'vitest';
import { AL_CERRAR_SESION } from './al-cerrar-sesion';
import { AuthService } from './auth.service';
import { AltaTotp, LoginResponse, Sesion } from './auth.models';

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

  // totp-super-admin, S1, S2 y S8: el super admin entra en dos pasos; Dueño y Vendedor, como siempre.
  describe('segundo paso del super admin (TOTP)', () => {
    const sesionAdmin: Sesion = {
      userId: 1,
      nombre: 'Administrador',
      email: 'admin@demo.local',
      rol: 'SUPER_ADMIN',
      ferreteriaId: null,
      ferreteriaNombre: null,
    };

    it('si el login pide el segundo paso no guarda nada: ni token ni sesión, en el signal ni en localStorage', () => {
      let devuelta: LoginResponse | undefined;
      service.login('admin@demo.local', 'demo1234').subscribe((r) => (devuelta = r));
      http
        .expectOne((r) => r.url.endsWith('/auth/login'))
        .flush({ token: null, sesion: null, segundoPaso: 'CODIGO', tokenTotp: 'totp-123' });

      expect(devuelta?.segundoPaso).toBe('CODIGO');
      expect(devuelta?.tokenTotp).toBe('totp-123');
      expect(service.autenticado()).toBe(false);
      expect(service.token()).toBeNull();
      expect(service.sesion()).toBeNull();
      expect(localStorage.getItem('ferreteria.token')).toBeNull();
      expect(localStorage.getItem('ferreteria.sesion')).toBeNull();
    });

    it('con segundoPaso y tokenTotp en null (Dueño, Vendedor) guarda el token y la sesión como siempre', () => {
      service.login('dueno@tornillo.demo', 'demo1234').subscribe();
      http
        .expectOne((r) => r.url.endsWith('/auth/login'))
        .flush({ ...respuesta, segundoPaso: null, tokenTotp: null });

      expect(service.autenticado()).toBe(true);
      expect(service.sesion()).toEqual(respuesta.sesion);
      expect(localStorage.getItem('ferreteria.token')).toBe('jwt-de-prueba');
      expect(JSON.parse(localStorage.getItem('ferreteria.sesion')!)).toEqual(respuesta.sesion);
    });

    it('altaTotp hace POST /auth/totp/alta con el tokenTotp, devuelve la clave y el QR, y no guarda nada', () => {
      let alta: AltaTotp | undefined;
      service.altaTotp('totp-123').subscribe((a) => (alta = a));

      const req = http.expectOne((r) => r.url.endsWith('/auth/totp/alta'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ tokenTotp: 'totp-123' });
      req.flush({ clave: 'JBSWY3DPEHPK3PXP', uri: 'otpauth://totp/x', qr: ['101', '010', '101'] });

      expect(alta).toEqual({ clave: 'JBSWY3DPEHPK3PXP', uri: 'otpauth://totp/x', qr: ['101', '010', '101'] });
      expect(service.autenticado()).toBe(false);
      expect(localStorage.getItem('ferreteria.token')).toBeNull();
    });

    it('verificarTotp hace POST /auth/totp/verificar y, con el código bien, guarda todo como el login', () => {
      service.verificarTotp('totp-123', '123456').subscribe();

      const req = http.expectOne((r) => r.url.endsWith('/auth/totp/verificar'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ tokenTotp: 'totp-123', codigo: '123456' });
      req.flush({ token: 'jwt-admin', sesion: sesionAdmin, segundoPaso: null, tokenTotp: null });

      expect(service.autenticado()).toBe(true);
      expect(service.esSuperAdmin()).toBe(true);
      expect(service.token()).toBe('jwt-admin');
      expect(localStorage.getItem('ferreteria.token')).toBe('jwt-admin');
      expect(JSON.parse(localStorage.getItem('ferreteria.sesion')!)).toEqual(sesionAdmin);
    });

    it('si verificarTotp falla no guarda nada', () => {
      service.verificarTotp('totp-123', '111111').subscribe({ error: () => undefined });

      http
        .expectOne((r) => r.url.endsWith('/auth/totp/verificar'))
        .flush(
          { status: 401, message: 'El código no es correcto.', errors: { motivo: 'CODIGO_INCORRECTO' } },
          { status: 401, statusText: 'Unauthorized' },
        );

      expect(service.autenticado()).toBe(false);
      expect(localStorage.getItem('ferreteria.token')).toBeNull();
      expect(localStorage.getItem('ferreteria.sesion')).toBeNull();
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

describe('AuthService al cerrar la sesión', () => {
  const sesion: Sesion = {
    userId: 2,
    nombre: 'Dueno El Tornillo',
    email: 'dueno@tornillo.demo',
    rol: 'DUENO',
    ferreteriaId: 1,
    ferreteriaNombre: 'Ferreteria El Tornillo',
  };

  function crear(...pasos: Array<(s: Sesion) => void>): AuthService {
    localStorage.clear();
    localStorage.setItem('ferreteria.token', 'jwt');
    localStorage.setItem('ferreteria.sesion', JSON.stringify(sesion));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        ...pasos.map((paso) => ({ provide: AL_CERRAR_SESION, multi: true, useValue: paso })),
      ],
    });
    return TestBed.inject(AuthService);
  }

  afterEach(() => localStorage.clear());

  it('corre cada paso de AL_CERRAR_SESION con la sesión que se cierra, y después borra todo', () => {
    const vistas: Array<Sesion | null> = [];
    const otro = vi.fn();
    const service = crear((s) => vistas.push({ ...s }), otro);

    service.logout();

    expect(vistas).toEqual([sesion]);
    expect(otro).toHaveBeenCalledWith(sesion);
    expect(service.sesion()).toBeNull();
    expect(localStorage.getItem('ferreteria.token')).toBeNull();
  });

  it('sin sesión (un segundo 401) no corre los pasos', () => {
    const paso = vi.fn();
    const service = crear(paso);
    service.logout();
    paso.mockClear();

    service.logout();

    expect(paso).not.toHaveBeenCalled();
  });

  it('un paso que falla no frena el cierre de la sesión', () => {
    const despues = vi.fn();
    const service = crear(() => {
      throw new Error('storage bloqueado');
    }, despues);

    service.logout();

    expect(despues).toHaveBeenCalled();
    expect(service.autenticado()).toBe(false);
    expect(localStorage.getItem('ferreteria.sesion')).toBeNull();
  });
});
