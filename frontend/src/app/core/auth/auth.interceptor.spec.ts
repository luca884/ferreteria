import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let router: { navigate: ReturnType<typeof vi.fn>; url: string };
  let auth: { token: () => string | null; logout: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    router = { navigate: vi.fn(), url: '/mostrador' };
    auth = { token: () => 'jwt', logout: vi.fn() };
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

  const dar401 = (url = '/api/articulos') => {
    http.get(url).subscribe({ error: () => undefined });
    ctrl.expectOne(url).flush(null, { status: 401, statusText: 'Unauthorized' });
  };

  it('agrega el Bearer', () => {
    http.get('/api/x').subscribe();
    const req = ctrl.expectOne('/api/x');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jwt');
    req.flush({});
  });

  it('con 401 cierra la sesión y manda al login con la URL actual como returnUrl', () => {
    router.url = '/mostrador?q=tornillo';

    dar401();

    expect(auth.logout).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnUrl: '/mostrador?q=tornillo' },
    });
  });

  it('si ya está en el login no agrega returnUrl', () => {
    router.url = '/login?returnUrl=%2Fmostrador';

    dar401();

    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('una URL que no es ruta propia no se manda como returnUrl', () => {
    router.url = '//evil.com';

    dar401();

    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('un 401 del propio login no redirige', () => {
    http.post('/api/auth/login', {}).subscribe({ error: () => undefined });
    ctrl.expectOne('/api/auth/login').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(router.navigate).not.toHaveBeenCalled();
    expect(auth.logout).not.toHaveBeenCalled();
  });
});
