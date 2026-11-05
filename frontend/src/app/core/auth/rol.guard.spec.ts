import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Data, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { routes } from '../../app.routes';
import { Rol, Sesion } from './auth.models';
import { AuthService } from './auth.service';
import { contrasenaGuard } from './contrasena.guard';
import { DUENO_Y_VENDEDOR, SOLO_DUENO, SOLO_SUPER_ADMIN, conRoles, rolGuard } from './rol.guard';

describe('rolGuard', () => {
  function correr(rol: Rol | null, data: Data, path = 'pantalla') {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { sesion: signal(rol ? ({ rol } as Sesion) : null) } },
      ],
    });
    const resultado = TestBed.runInInjectionContext(() =>
      rolGuard({ data, routeConfig: { path } } as unknown as ActivatedRouteSnapshot, {
        url: `/${path}`,
      } as RouterStateSnapshot),
    );
    return resultado instanceof UrlTree ? TestBed.inject(Router).serializeUrl(resultado) : resultado;
  }

  it('el Dueño entra a una pantalla solo del Dueño', () => {
    expect(correr('DUENO', conRoles(SOLO_DUENO))).toBe(true);
  });

  it('el Vendedor entra a una pantalla del Dueño y del Vendedor', () => {
    expect(correr('VENDEDOR', conRoles(DUENO_Y_VENDEDOR))).toBe(true);
  });

  it('el Vendedor que escribe una pantalla solo del Dueño vuelve al Mostrador, sin aviso', () => {
    expect(correr('VENDEDOR', conRoles(SOLO_DUENO))).toBe('/mostrador');
  });

  it('el Vendedor y el Dueño que escriben la pantalla del super admin vuelven al Mostrador', () => {
    expect(correr('VENDEDOR', conRoles(SOLO_SUPER_ADMIN))).toBe('/mostrador');
    expect(correr('DUENO', conRoles(SOLO_SUPER_ADMIN))).toBe('/mostrador');
  });

  it('el super admin no entra a las pantallas de una ferretería: vuelve a Ferreterías', () => {
    expect(correr('SUPER_ADMIN', conRoles(DUENO_Y_VENDEDOR))).toBe('/admin/ferreterias');
    expect(correr('SUPER_ADMIN', conRoles(SOLO_DUENO))).toBe('/admin/ferreterias');
  });

  it('una ruta sin roles declarados está cerrada para todos', () => {
    expect(correr('DUENO', {})).toBe('/mostrador');
    expect(correr('VENDEDOR', {})).toBe('/mostrador');
    expect(correr('SUPER_ADMIN', {})).toBe('/admin/ferreterias');
  });

  it('un rol que la app no conoce va al login, sin loop entre su inicio y el guard', () => {
    expect(correr('CAJERO' as Rol, conRoles(DUENO_Y_VENDEDOR))).toBe('/login');
  });

  it('sin sesión va al login', () => {
    expect(correr(null, conRoles(DUENO_Y_VENDEDOR))).toBe('/login');
  });

  it('el shell lo aplica a todas sus rutas hijas, después del cambio obligatorio de contraseña', () => {
    const shell = routes.find((r) => r.path === '' && r.children);

    expect(shell?.canActivateChild).toEqual([contrasenaGuard, rolGuard]);
  });
});
