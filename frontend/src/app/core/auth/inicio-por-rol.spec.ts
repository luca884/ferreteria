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

  it('el Vendedor aterriza en el mostrador', () => {
    expect(destino(sesion('VENDEDOR'))).toBe('mostrador');
  });

  it.each<Rol>(['DUENO', 'SUPER_ADMIN'])('%s aterriza en rubros', (rol) => {
    expect(destino(sesion(rol))).toBe('rubros');
  });

  it('sin sesión va a rubros', () => {
    expect(destino(null)).toBe('rubros');
  });
});
