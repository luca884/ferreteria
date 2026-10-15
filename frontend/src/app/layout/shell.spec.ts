import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, signal } from '@angular/core';
import { Router, Routes, provideRouter } from '@angular/router';
import { Sesion } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';
import { Shell } from './shell';

@Component({ template: '' })
class Vacia {}

describe('Shell', () => {
  let fixture: ComponentFixture<Shell>;

  async function render(
    dueno: boolean,
    sesion: Sesion | null = null,
    rutas: Routes = [],
  ): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter(rutas),
        {
          provide: AuthService,
          useValue: { esDueno: signal(dueno), sesion: signal(sesion), logout: () => undefined },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const enlaces = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('nav a')).map((a) => a.textContent?.trim());

  it('las secciones van en el menú lateral', async () => {
    const el = await render(true);

    const nav = el.querySelector('aside nav[aria-label="Secciones"]');
    expect(nav).not.toBeNull();
    expect(nav!.querySelectorAll('a').length).toBe(6);
  });

  it('el Dueño ve Mostrador, Artículos, Rubros, Proveedores y, bajo «Precios», Importar lista y Actualizar precios', async () => {
    const el = await render(true);

    expect(enlaces(el)).toEqual([
      'Mostrador',
      'Artículos',
      'Rubros',
      'Proveedores',
      'Importar lista',
      'Actualizar precios',
    ]);
    expect(el.querySelector('nav .grupo')?.textContent?.trim()).toBe('Precios');
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

  it('el Vendedor no ve "Importar lista", "Actualizar precios" ni el grupo «Precios»', async () => {
    const el = await render(false);

    expect(enlaces(el)).toEqual(['Mostrador', 'Artículos', 'Rubros', 'Proveedores']);
    expect(el.querySelector('a[href="/actualizacion-masiva"]')).toBeNull();
    expect(el.querySelector('nav .grupo')).toBeNull();
  });

  it('la sección abierta se marca con aria-current="page"', async () => {
    const el = await render(true, null, [{ path: 'rubros', component: Vacia }]);

    await TestBed.inject(Router).navigateByUrl('/rubros');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(el.querySelector('nav a[href="/rubros"]')!.getAttribute('aria-current')).toBe('page');
    expect(el.querySelector('nav a[href="/mostrador"]')!.hasAttribute('aria-current')).toBe(false);
  });

  it('muestra la ferretería, el usuario, sus iniciales y su rol', async () => {
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
    const avatar = el.querySelector('.avatar');
    expect(avatar?.textContent?.trim()).toBe('JP');
    expect(avatar?.getAttribute('aria-hidden')).toBe('true');
  });

  it('con un solo nombre las iniciales son una letra', async () => {
    const el = await render(false, {
      userId: 1,
      nombre: 'ana',
      email: 'a@demo',
      rol: 'VENDEDOR',
      ferreteriaId: 1,
      ferreteriaNombre: 'El Tornillo',
    });

    expect(el.querySelector('.avatar')?.textContent?.trim()).toBe('A');
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
