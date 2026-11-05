import { HttpErrorResponse } from '@angular/common/http';
import { WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { MiContrasena } from './mi-contrasena';

describe('MiContrasena', () => {
  let fixture: ComponentFixture<MiContrasena>;
  let auth: {
    cambiarContrasena: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
    debeCambiarContrasena: WritableSignal<boolean>;
  };
  let navegar: ReturnType<typeof vi.fn>;

  const errorHttp = (status: number, message: string, errors?: Record<string, string>) =>
    new HttpErrorResponse({ status, error: { status, message, errors } });

  async function abrir(debeCambiar = false) {
    auth = {
      cambiarContrasena: vi.fn(() => {
        // Como el servicio real: al cambiarla con éxito, la marca de la sesión se apaga.
        auth.debeCambiarContrasena.set(false);
        return of(undefined);
      }),
      logout: vi.fn(),
      debeCambiarContrasena: signal(debeCambiar),
    };
    navegar = vi.fn();
    await TestBed.configureTestingModule({
      imports: [MiContrasena],
      providers: [
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: { navigate: navegar, navigateByUrl: navegar } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MiContrasena);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;

  async function escribir(id: string, valor: string) {
    input(id).value = valor;
    input(id).dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function enviar(actual = 'vieja1234', nueva = 'nueva1234') {
    await escribir('actual', actual);
    await escribir('nueva', nueva);
    await escribir('confirmacion', nueva);
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  it('usa el formulario de contraseña pidiendo la actual', async () => {
    await abrir();

    expect(el().querySelector('h1')?.textContent).toContain('Cambiar mi contraseña');
    expect(el().querySelector('app-contrasena-form')).not.toBeNull();
    expect(input('actual')).not.toBeNull();
  });

  it('cambia la contraseña con la actual y la nueva, avisa y limpia el formulario', async () => {
    await abrir();

    await enviar();

    expect(auth.cambiarContrasena).toHaveBeenCalledWith('vieja1234', 'nueva1234');
    expect(el().querySelector('.alerta.exito')?.textContent).toContain('Contraseña actualizada.');
    expect(input('actual').value).toBe('');
    expect(input('nueva').value).toBe('');
    expect(input('confirmacion').value).toBe('');
  });

  it('una actual incorrecta (400 passwordActual) se muestra en el campo, no navega ni desloguea', async () => {
    await abrir();
    auth.cambiarContrasena.mockReturnValue(
      throwError(() =>
        errorHttp(400, 'Datos inválidos', { passwordActual: 'La contraseña actual no es correcta.' }),
      ),
    );

    await enviar();

    expect(el().querySelector('#actual-error')?.textContent).toContain('La contraseña actual no es correcta.');
    expect(el().querySelector('.alerta.exito')).toBeNull();
    expect(navegar).not.toHaveBeenCalled();
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('otro error de campo (nueva corta en el servidor) se muestra como error del formulario', async () => {
    await abrir();
    auth.cambiarContrasena.mockReturnValue(
      throwError(() =>
        errorHttp(400, 'Datos inválidos', { passwordNueva: 'La contraseña debe tener entre 8 y 72 caracteres.' }),
      ),
    );

    await enviar();

    expect(el().querySelector('app-contrasena-form [role="alert"]')?.textContent).toContain(
      'La contraseña debe tener entre 8 y 72 caracteres.',
    );
  });

  it('un error sin detalle avisa que no se pudo cambiar', async () => {
    await abrir();
    auth.cambiarContrasena.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    await enviar();

    expect(el().querySelector('app-contrasena-form [role="alert"]')?.textContent).toContain(
      'No se pudo cambiar la contraseña.',
    );
  });

  it('un 429 (demasiadas actuales incorrectas) muestra el aviso del servidor, no navega ni desloguea', async () => {
    await abrir();
    const mensaje = 'Demasiados intentos. Probá de nuevo en 5 minutos.';
    auth.cambiarContrasena.mockReturnValue(throwError(() => errorHttp(429, mensaje)));

    await enviar();

    expect(el().querySelector('app-contrasena-form [role="alert"]')?.textContent).toContain(mensaje);
    expect(navegar).not.toHaveBeenCalled();
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('un 429 sin mensaje igual avisa que hubo demasiados intentos', async () => {
    await abrir();
    auth.cambiarContrasena.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 429 })));

    await enviar();

    expect(el().querySelector('app-contrasena-form [role="alert"]')?.textContent).toContain(
      'Demasiados intentos. Probá de nuevo en unos minutos.',
    );
  });

  it('el aviso de éxito se va si se vuelve a intentar con un error', async () => {
    await abrir();
    await enviar();
    auth.cambiarContrasena.mockReturnValue(throwError(() => errorHttp(422, 'Algo falló.')));

    await enviar('otra12345', 'nueva5678');

    expect(el().querySelector('.alerta.exito')).toBeNull();
  });

  describe('con la contraseña elegida por otra persona', () => {
    const AVISO = 'Tu contraseña la eligió otra persona. Cambiala para seguir.';

    it('muestra el aviso', async () => {
      await abrir(true);

      expect(el().querySelector('.alerta.advertencia')?.textContent?.trim()).toBe(AVISO);
    });

    it('al cambiarla manda al inicio del rol (la raíz redirige con inicioPorRol)', async () => {
      await abrir(true);

      await enviar();

      expect(auth.cambiarContrasena).toHaveBeenCalledWith('vieja1234', 'nueva1234');
      expect(navegar).toHaveBeenCalledWith('/');
    });

    it('si el cambio falla no navega y el aviso sigue', async () => {
      await abrir(true);
      auth.cambiarContrasena.mockReturnValue(
        throwError(() => errorHttp(400, 'Datos inválidos', { passwordActual: 'La contraseña actual no es correcta.' })),
      );

      await enviar();

      expect(navegar).not.toHaveBeenCalled();
      expect(el().querySelector('.alerta.advertencia')?.textContent?.trim()).toBe(AVISO);
    });

    it('sin la marca no hay aviso ni redirección al cambiarla', async () => {
      await abrir(false);

      await enviar();

      expect(el().querySelector('.alerta.advertencia')).toBeNull();
      expect(navegar).not.toHaveBeenCalled();
    });
  });
});
