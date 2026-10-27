import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { routes } from '../../app.routes';
import { AuthService } from './auth.service';
import { contrasenaGuard } from './contrasena.guard';

describe('contrasenaGuard', () => {
  function correr(debeCambiar: boolean, path: string) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { debeCambiarContrasena: signal(debeCambiar) } },
      ],
    });
    const resultado = TestBed.runInInjectionContext(() =>
      contrasenaGuard({ routeConfig: { path } } as ActivatedRouteSnapshot, { url: `/${path}` } as RouterStateSnapshot),
    );
    return resultado instanceof UrlTree ? TestBed.inject(Router).serializeUrl(resultado) : resultado;
  }

  it.each(['mostrador', 'articulos', 'usuarios', 'admin/ferreterias'])(
    'con la marca prendida, /%s lleva a /mi-contrasena',
    (path) => {
      expect(correr(true, path)).toBe('/mi-contrasena');
    },
  );

  it('con la marca prendida, /mi-contrasena se deja abrir', () => {
    expect(correr(true, 'mi-contrasena')).toBe(true);
  });

  it('sin la marca, cualquier ruta se deja abrir', () => {
    expect(correr(false, 'mostrador')).toBe(true);
  });

  it('el shell lo aplica a todas sus rutas hijas', () => {
    const shell = routes.find((r) => r.path === '' && r.children);

    expect(shell?.canActivateChild).toContain(contrasenaGuard);
  });
});
