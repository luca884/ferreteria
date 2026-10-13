import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { Shell } from './shell';

describe('Shell', () => {
  async function render(dueno: boolean): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: { esDueno: signal(dueno), sesion: signal(null), logout: () => undefined },
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
