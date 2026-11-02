import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Login } from './login';

describe('Login', () => {
  let fixture: ComponentFixture<Login>;
  let navegarA: ReturnType<typeof vi.spyOn>;

  async function crear(returnUrl?: string) {
    await TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { login: vi.fn(() => of({})) } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: convertToParamMap(returnUrl === undefined ? {} : { returnUrl }) },
          },
        },
      ],
    }).compileComponents();
    navegarA = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function ingresar() {
    const el = fixture.nativeElement as HTMLElement;
    for (const [id, valor] of [
      ['email', 'dueno@tornillo.demo'],
      ['password', 'demo1234'],
    ]) {
      const input = el.querySelector<HTMLInputElement>(`#${id}`)!;
      input.value = valor;
      input.dispatchEvent(new Event('input'));
    }
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  describe('accesibilidad del formulario', () => {
    const el = () => fixture.nativeElement as HTMLElement;
    const campo = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
    const descripciones = (input: HTMLElement) =>
      (input.getAttribute('aria-describedby') ?? '')
        .split(' ')
        .filter(Boolean)
        .map((id) => el().querySelector(`[id="${id}"]`)?.textContent?.trim());

    async function enviar() {
      el().querySelector('form')!.dispatchEvent(new Event('submit'));
      fixture.detectChanges();
      await fixture.whenStable();
    }

    it('los campos tienen name y el email no corrige ortografía', async () => {
      await crear();

      expect(campo('email').getAttribute('name')).toBe('email');
      expect(campo('email').getAttribute('spellcheck')).toBe('false');
      expect(campo('password').getAttribute('name')).toBe('password');
      expect(campo('email').hasAttribute('aria-invalid')).toBe(false);
      expect(campo('email').hasAttribute('aria-describedby')).toBe(false);
    });

    it('al enviar vacío marca los campos inválidos, los enlaza a su error y enfoca el primero', async () => {
      await crear();
      el().querySelector<HTMLButtonElement>('button[type="submit"]')!.focus();

      await enviar();

      expect(campo('email').getAttribute('aria-invalid')).toBe('true');
      expect(descripciones(campo('email'))).toEqual(['Ingresá un email válido.']);
      expect(campo('password').getAttribute('aria-invalid')).toBe('true');
      expect(descripciones(campo('password'))).toEqual(['Ingresá tu contraseña.']);
      expect(document.activeElement).toBe(campo('email'));
    });

    it('con el email bien y sin contraseña enfoca la contraseña', async () => {
      await crear();
      campo('email').value = 'dueno@tornillo.demo';
      campo('email').dispatchEvent(new Event('input'));

      await enviar();

      expect(campo('email').hasAttribute('aria-invalid')).toBe(false);
      expect(document.activeElement).toBe(campo('password'));
    });

    it('el error del servidor queda enlazado a los dos campos', async () => {
      await crear();
      const auth = TestBed.inject(AuthService) as unknown as { login: ReturnType<typeof vi.fn> };
      auth.login.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));

      await ingresar();

      expect(descripciones(campo('email'))).toEqual(['Email o contraseña incorrectos.']);
      expect(descripciones(campo('password'))).toEqual(['Email o contraseña incorrectos.']);
    });

    it('un 401 con el mensaje genérico del servidor muestra el texto de siempre', async () => {
      await crear();
      const auth = TestBed.inject(AuthService) as unknown as { login: ReturnType<typeof vi.fn> };
      auth.login.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 401, error: { message: 'Email o contraseña incorrectos' } })),
      );

      await ingresar();

      expect(descripciones(campo('email'))).toEqual(['Email o contraseña incorrectos.']);
    });

    it('un 429 muestra el aviso del servidor con los minutos que faltan', async () => {
      await crear();
      const auth = TestBed.inject(AuthService) as unknown as { login: ReturnType<typeof vi.fn> };
      const mensaje = 'Demasiados intentos. Probá de nuevo en 5 minutos.';
      auth.login.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 429, error: { message: mensaje } })));

      await ingresar();

      expect(descripciones(campo('email'))).toEqual([mensaje]);
      expect(descripciones(campo('password'))).toEqual([mensaje]);
    });

    it('un 429 sin mensaje igual avisa que hubo demasiados intentos', async () => {
      await crear();
      const auth = TestBed.inject(AuthService) as unknown as { login: ReturnType<typeof vi.fn> };
      auth.login.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 429 })));

      await ingresar();

      expect(descripciones(campo('email'))).toEqual(['Demasiados intentos. Probá de nuevo en unos minutos.']);
    });

    it('un 401 de ferretería suspendida muestra el mensaje del servidor', async () => {
      await crear();
      const auth = TestBed.inject(AuthService) as unknown as { login: ReturnType<typeof vi.fn> };
      const mensaje = 'Tu ferretería está suspendida. Contactá al proveedor.';
      auth.login.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401, error: { message: mensaje } })));

      await ingresar();

      expect(descripciones(campo('email'))).toEqual([mensaje]);
      expect(descripciones(campo('password'))).toEqual([mensaje]);
    });
  });

  it('sin returnUrl entra al inicio de su rol', async () => {
    await crear();

    await ingresar();

    expect(navegarA).toHaveBeenCalledWith('/');
  });

  it('vuelve a la URL protegida a la que había entrado sin sesión', async () => {
    await crear('/articulos/5?x=1');

    await ingresar();

    expect(navegarA).toHaveBeenCalledWith('/articulos/5?x=1');
  });

  it.each(['https://otro.sitio', '//otro.sitio/x', 'articulos', '/login'])(
    'ignora un returnUrl que no es una ruta propia (%s)',
    async (returnUrl) => {
      await crear(returnUrl);

      await ingresar();

      expect(navegarA).toHaveBeenCalledWith('/');
    },
  );
});
