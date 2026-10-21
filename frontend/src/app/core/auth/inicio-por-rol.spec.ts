import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { AuthService } from './auth.service';
import { Rol, Sesion } from './auth.models';
import { inicioPorRol } from './inicio-por-rol';

describe('inicioPorRol', () => {
  const sesion = (rol: Rol): Sesion => ({
    userId: 1,
    nombre: 'Test',
    email: 'test@demo.local',
    rol,
    ferreteriaId: rol === 'SUPER_ADMIN' ? null : 1,
    ferreteriaNombre: null,
  });

  function destino(actual: Sesion | null) {
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: { sesion: signal(actual) } }],
    });
    return TestBed.runInInjectionContext(() => (inicioPorRol as (...args: never[]) => string)());
  }

  it.each<Rol>(['VENDEDOR', 'DUENO'])('%s aterriza en el mostrador', (rol) => {
    expect(destino(sesion(rol))).toBe('mostrador');
  });

  it('el administrador (sin ferretería) aterriza en la pantalla de ferreterías', () => {
    expect(destino(sesion('SUPER_ADMIN'))).toBe('admin/ferreterias');
  });

  it('sin sesión va directo al login (sin returnUrl: no quería ir a ningún lado en particular)', () => {
    expect(destino(null)).toBe('/login');
  });
});
