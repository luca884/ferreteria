import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

// Regresión de bugs de la guía, S15 (sondas del verificador): bordes del interceptor de todos los pedidos.
describe('authInterceptor en los bordes', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let router: { navigate: ReturnType<typeof vi.fn>; url: string };
  let token: string | null;
  let auth: { token: () => string | null; logout: ReturnType<typeof vi.fn>; refrescarSesion: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    token = 'jwt';
    router = { navigate: vi.fn(), url: '/mostrador' };
    auth = { token: () => token, logout: vi.fn(), refrescarSesion: vi.fn(() => of({})) };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
        { provide: AuthService, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
  });

  afterEach(() => ctrl.verify());

  const responder = (status: number, url = '/api/articulos') => {
    const errores: unknown[] = [];
    http.get(url).subscribe({ error: (e) => errores.push(e) });
    ctrl.expectOne(url).flush(null, { status, statusText: 'x' });
    return errores;
  };

  it('sin token y en una ruta que no es propia, va al login sin parámetros, como antes', () => {
    token = null;
    for (const url of ['/login?returnUrl=%2Fcaja', '//evil.com', '']) {
      router.navigate.mockClear();
      router.url = url;
      responder(401);
      expect(router.navigate).toHaveBeenCalledTimes(1);
      expect(router.navigate.mock.calls[0]).toEqual([['/login']]);
    }
  });

  it('sin token no manda Authorization', () => {
    token = null;
    http.get('/api/x').subscribe();
    const req = ctrl.expectOne('/api/x');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it.each([0, 400, 403, 404, 409, 500, 503])('un %s no desloguea ni navega', (status) => {
    responder(status);
    expect(auth.logout).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('el 401 del login con URL absoluta tampoco redirige', () => {
    http.post('http://localhost:8080/api/auth/login', {}).subscribe({ error: () => undefined });
    ctrl.expectOne('http://localhost:8080/api/auth/login').flush(null, { status: 401, statusText: 'x' });
    expect(router.navigate).not.toHaveBeenCalled();
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('con token, el 401 sigue llegando al que pidió', () => {
    const errores = responder(401);
    expect(errores).toHaveLength(1);
    expect((errores[0] as HttpErrorResponse).status).toBe(401);
    expect(auth.logout).toHaveBeenCalledTimes(1);
  });

  it('el token es el del pedido: si otro 401 ya cerró la sesión, el segundo igual avisa y no pierde la ruta', () => {
    router.url = '/caja';
    http.get('/api/a').subscribe({ error: () => undefined });
    http.get('/api/b').subscribe({ error: () => undefined });
    token = null; // el primer 401 desloguea
    ctrl.expectOne('/api/a').flush(null, { status: 401, statusText: 'x' });
    ctrl.expectOne('/api/b').flush(null, { status: 401, statusText: 'x' });
    expect(router.navigate.mock.calls).toEqual([
      [['/login'], { queryParams: { returnUrl: '/caja', sesion: 'cerrada' } }],
      [['/login'], { queryParams: { returnUrl: '/caja', sesion: 'cerrada' } }],
    ]);
  });

  it('en la raíz manda returnUrl=/ y el aviso', () => {
    router.url = '/';
    responder(401);
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { returnUrl: '/', sesion: 'cerrada' } });
  });
});
