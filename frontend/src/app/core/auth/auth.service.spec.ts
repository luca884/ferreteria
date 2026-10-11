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
});
