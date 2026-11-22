import { TestBed } from '@angular/core/testing';
import { AuthService } from '../core/auth/auth.service';
import { MenuActual } from './menu-actual';

// CU-17 T1 (S5, S6): con la disposición original, el menú que sale de `MenuActual` es el de siempre para cada rol.
describe('MenuActual (CU-17 T1)', () => {
  function menuDe(dueno: boolean): MenuActual {
    TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { esDueno: () => dueno } }] });
    return TestBed.inject(MenuActual);
  }

  it('el Vendedor ve sus grupos y el Dueño los cinco', () => {
    expect(menuDe(false).grupos().map((g) => g.id)).toEqual(['ventas', 'articulos', 'compras']);
    TestBed.resetTestingModule();
    expect(menuDe(true).grupos().map((g) => g.id)).toEqual([
      'ventas',
      'articulos',
      'compras',
      'precios',
      'configuracion',
    ]);
  });

  it('ubica una pantalla por su grupo y su tono aunque el rol no pueda abrirla', () => {
    const menu = menuDe(false);
    expect(menu.ubicar('/configuracion/precios')).toEqual({ id: 'precios', tono: 'precios' });
    expect(menu.ubicar('/usuarios')).toEqual({ id: 'configuracion', tono: 'configuracion' });
    expect(menu.ubicar('/mostrador')).toEqual({ id: 'mostrador', tono: 'mostrador' });
    expect(menu.ubicar('/area-ventas')).toEqual({ id: 'ventas', tono: 'ventas' });
    expect(menu.ubicar('/mi-contrasena')).toBeNull();
  });
});
