import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { Sesion } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';
import { Shell } from './shell';

describe('Shell', () => {
  async function render(dueno: boolean, sesion: Sesion | null = null): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: { esDueno: signal(dueno), sesion: signal(sesion), logout: () => undefined },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const enlaces = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('nav a')).map((a) => a.textContent?.trim());

  it('el Dueño ve "Importar lista" después de Proveedores', async () => {
    const el = await render(true);

    expect(enlaces(el)).toEqual([
      'Mostrador',
      'Rubros',
      'Artículos',
      'Proveedores',
      'Importar lista',
      'Actualizar precios',
    ]);
    expect(el.querySelector('a[href="/importaciones"]')).not.toBeNull();
  });

  it('el Dueño ve "Actualizar precios", que lleva a /actualizacion-masiva, después de "Importar lista"', async () => {
    const el = await render(true);

    const nombres = enlaces(el);
    expect(nombres.indexOf('Actualizar precios')).toBe(nombres.indexOf('Importar lista') + 1);
    const enlace = el.querySelector('nav a[href="/actualizacion-masiva"]');
    expect(enlace).not.toBeNull();
    expect(enlace!.textContent?.trim()).toBe('Actualizar precios');
  });

  it('el Vendedor no ve "Importar lista" ni "Actualizar precios"', async () => {
    const el = await render(false);

    expect(enlaces(el)).toEqual(['Mostrador', 'Rubros', 'Artículos', 'Proveedores']);
    expect(el.querySelector('a[href="/actualizacion-masiva"]')).toBeNull();
  });

  it('muestra la ferretería, el usuario y su rol', async () => {
    const el = await render(false, {
      userId: 2,
      nombre: 'Juan Pérez',
      email: 'vendedor@tornillo.demo',
      rol: 'VENDEDOR',
      ferreteriaId: 1,
      ferreteriaNombre: 'El Tornillo',
    });

    expect(el.querySelector('.ferreteria')?.textContent?.trim()).toBe('El Tornillo');
    expect(el.querySelector('.usuario-nombre')?.textContent?.trim()).toBe('Juan Pérez');
    expect(el.querySelector('.usuario-rol')?.textContent?.trim()).toBe('Vendedor');
  });

  it.each([
    ['DUENO', 'Dueño'],
    ['SUPER_ADMIN', 'Administrador'],
  ] as const)('el rol %s se muestra como «%s»', async (rol, etiqueta) => {
    const el = await render(rol === 'DUENO', {
      userId: 1,
      nombre: 'Ana',
      email: 'a@demo',
      rol,
      ferreteriaId: rol === 'DUENO' ? 1 : null,
      ferreteriaNombre: rol === 'DUENO' ? 'El Tornillo' : null,
    });

    expect(el.querySelector('.usuario-rol')?.textContent?.trim()).toBe(etiqueta);
  });

  it('sin ferretería (administrador) dice «Panel de administración»', async () => {
    const el = await render(false, {
      userId: 1,
      nombre: 'Admin',
      email: 'admin@demo.local',
      rol: 'SUPER_ADMIN',
      ferreteriaId: null,
      ferreteriaNombre: null,
    });

    expect(el.querySelector('.ferreteria')?.textContent?.trim()).toBe('Panel de administración');
  });

  it('"Mostrador" es el primer enlace y lleva a /mostrador, para el Vendedor y para el Dueño', async () => {
    for (const dueno of [false, true]) {
      TestBed.resetTestingModule();
      const el = await render(dueno);

      const primero = el.querySelector('nav a');
      expect(primero?.textContent?.trim()).toBe('Mostrador');
      expect(primero?.getAttribute('href')).toBe('/mostrador');
    }
  });
});
