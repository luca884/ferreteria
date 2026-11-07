import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Data, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { routes } from '../../app.routes';
import { Rol, Sesion } from './auth.models';
import { Avisos } from '../avisos/avisos';
import { AuthService } from './auth.service';
import { contrasenaGuard } from './contrasena.guard';
import { DUENO_Y_VENDEDOR, SOLO_DUENO, SOLO_SUPER_ADMIN, conRoles, rolGuard } from './rol.guard';

describe('rolGuard', () => {
  function correr(rol: Rol | null, data: Data, path = 'pantalla', title?: string, debeCambiarContrasena = false) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            sesion: signal(rol ? ({ rol } as Sesion) : null),
            debeCambiarContrasena: signal(debeCambiarContrasena),
          },
        },
      ],
    });
    const resultado = TestBed.runInInjectionContext(() =>
      rolGuard({ data, routeConfig: { path, title } } as unknown as ActivatedRouteSnapshot, {
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

  it('el Vendedor que escribe una pantalla solo del Dueño vuelve al Mostrador con «No tenés acceso a …» de advertencia', () => {
    expect(correr('VENDEDOR', conRoles(SOLO_DUENO), 'ventas', 'Ventas · Ferretería')).toBe('/mostrador');
    expect(TestBed.inject(Avisos).tomar()).toEqual({ texto: 'No tenés acceso a Ventas.', tipo: 'advertencia' });
  });

  it('con la ruta real de Ventas el aviso nombra la pantalla', () => {
    const ventas = routes.flatMap((r) => r.children ?? []).find((r) => r.path === 'ventas')!;

    expect(correr('VENDEDOR', ventas.data!, 'ventas', ventas.title as string)).toBe('/mostrador');
    expect(TestBed.inject(Avisos).tomar()?.texto).toBe('No tenés acceso a Ventas.');
  });

  it('una ruta sin título dice «esa pantalla»', () => {
    expect(correr('VENDEDOR', conRoles(SOLO_DUENO))).toBe('/mostrador');
    expect(TestBed.inject(Avisos).tomar()?.texto).toBe('No tenés acceso a esa pantalla.');
  });

  it('con la contraseña por cambiar no deja aviso: el guard de contraseña lo manda a cambiarla, no a su inicio', () => {
    expect(correr('VENDEDOR', conRoles(SOLO_DUENO), 'ventas', 'Ventas · Ferretería', true)).toBe('/mostrador');
    expect(TestBed.inject(Avisos).tomar()).toBeNull();
  });

  it('quien entra no deja aviso', () => {
    expect(correr('VENDEDOR', conRoles(DUENO_Y_VENDEDOR), 'caja', 'Caja · Ferretería')).toBe(true);
    expect(TestBed.inject(Avisos).tomar()).toBeNull();
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

  it('un rol que la app no conoce va al login, sin loop entre su inicio y el guard, y sin aviso', () => {
    expect(correr('CAJERO' as Rol, conRoles(DUENO_Y_VENDEDOR))).toBe('/login');
    expect(TestBed.inject(Avisos).tomar()).toBeNull();
  });

  it('sin sesión va al login, sin aviso', () => {
    expect(correr(null, conRoles(DUENO_Y_VENDEDOR))).toBe('/login');
    expect(TestBed.inject(Avisos).tomar()).toBeNull();
  });

  it('el shell lo aplica a todas sus rutas hijas, después del cambio obligatorio de contraseña', () => {
    const shell = routes.find((r) => r.path === '' && r.children);

    expect(shell?.canActivateChild).toEqual([contrasenaGuard, rolGuard]);
  });
});
