import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Sesion } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { Login } from './login';

describe('Login', () => {
  let fixture: ComponentFixture<Login>;
  let navegarA: ReturnType<typeof vi.spyOn>;

  async function crear(returnUrl?: string, otros: Record<string, string> = {}) {
    await TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { login: vi.fn(() => of({})) } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: convertToParamMap({ ...(returnUrl === undefined ? {} : { returnUrl }), ...otros }) },
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

  // Bugs de la guía, S15: al suspender la ferretería, la sesión abierta del Dueño volvía al ingreso sin decir nada.
  describe('la sesión que cerró el servidor', () => {
    const aviso = () => (fixture.nativeElement as HTMLElement).querySelector('.sesion-cerrada');

    it('con sesion=cerrada avisa «Tu sesión se cerró. Ingresá de nuevo.»', async () => {
      await crear('/mostrador', { sesion: 'cerrada' });

      expect(aviso()?.textContent?.trim()).toBe('Tu sesión se cerró. Ingresá de nuevo.');
      expect(aviso()?.getAttribute('role')).toBe('status');
    });

    // Guía al día: si el reintento fallaba (la ferretería sigue suspendida), quedaban los dos avisos a la vez.
    it('si el reintento falla, queda solo el error del ingreso', async () => {
      await crear('/mostrador', { sesion: 'cerrada' });
      const auth = TestBed.inject(AuthService) as unknown as { login: ReturnType<typeof vi.fn> };
      auth.login.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));

      await ingresar();

      expect(aviso()).toBeNull();
      expect((fixture.nativeElement as HTMLElement).querySelector('#login-error')?.textContent?.trim()).toBe(
        'Email o contraseña incorrectos.',
      );
    });

    it('al entrar sin ese parámetro no dice nada', async () => {
      await crear('/mostrador');

      expect(aviso()).toBeNull();
    });

    it('el aviso no cambia a dónde vuelve después de ingresar', async () => {
      await crear('/caja', { sesion: 'cerrada' });

      await ingresar();

      expect(navegarA).toHaveBeenCalledWith('/caja');
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

// totp-super-admin, S1, S2, S6 y S8: con el AuthService de verdad, contra el contrato del backend.
describe('Login: el código de la app del super admin', () => {
  const VENCIDO = 'Pasaron más de 5 minutos. Volvé a ingresar tu email y contraseña.';
  const INCORRECTO = 'El código no es correcto. Fijate en la app y probá de nuevo.';
  const sesionAdmin: Sesion = {
    userId: 1,
    nombre: 'Administrador',
    email: 'admin@demo.local',
    rol: 'SUPER_ADMIN',
    ferreteriaId: null,
    ferreteriaNombre: null,
  };

  let fixture: ComponentFixture<Login>;
  let http: HttpTestingController;
  let navegarA: ReturnType<typeof vi.spyOn>;
  const el = () => fixture.nativeElement as HTMLElement;
  const campo = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`);
  const texto = (selector: string) => el().querySelector(selector)?.textContent?.trim();
  const boton = (nombre: string) =>
    [...el().querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === nombre);
  const pedido = (ruta: string) => http.expectOne((r) => r.url.endsWith(ruta));
  const fallo = (status: number, message: string, motivo?: string) => [
    { status, message, errors: motivo ? { motivo } : null, timestamp: '2026-11-09T10:00:00' },
    { status, statusText: 'x' },
  ] as const;

  async function crear(returnUrl?: string) {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(returnUrl ? { returnUrl } : {}) } },
        },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    navegarA = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(Login);
    await estable();
  }

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  async function estable() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function escribir(id: string, valor: string) {
    const input = campo(id)!;
    input.value = valor;
    input.dispatchEvent(new Event('input'));
    await estable();
  }

  async function enviar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await estable();
  }

  /** Email y contraseña bien del super admin: el servidor pide el segundo paso. */
  async function ingresarComoAdmin(segundoPaso: 'ALTA' | 'CODIGO') {
    await escribir('email', 'admin@demo.local');
    await escribir('password', 'demo1234');
    await enviar();
    pedido('/auth/login').flush({ token: null, sesion: null, segundoPaso, tokenTotp: 'totp-1' });
    await estable();
  }

  it('S8: Dueño y Vendedor (segundoPaso y tokenTotp en null) entran como siempre, sin pedir código', async () => {
    await crear('/mostrador');
    await escribir('email', 'dueno@tornillo.demo');
    await escribir('password', 'demo1234');
    await enviar();

    const login = pedido('/auth/login');
    expect(login.request.body).toEqual({ email: 'dueno@tornillo.demo', password: 'demo1234' });
    login.flush({
      token: 'jwt-dueno',
      sesion: { ...sesionAdmin, rol: 'DUENO', ferreteriaId: 1, ferreteriaNombre: 'El Tornillo' },
      segundoPaso: null,
      tokenTotp: null,
    });
    await estable();

    expect(localStorage.getItem('ferreteria.token')).toBe('jwt-dueno');
    expect(navegarA).toHaveBeenCalledWith('/mostrador');
    expect(campo('codigo')).toBeNull();
  });

  it('S1: con CODIGO pide el código de la app, enfocado, y con el código bien guarda la sesión y entra', async () => {
    await crear('/admin/ferreterias');
    await ingresarComoAdmin('CODIGO');

    expect(texto('h2')).toBe('Código de la app');
    expect(texto('label[for="codigo"]')).toBe(
      'Abrí Google Authenticator y escribí el código de 6 números que muestra para Ferretería.',
    );
    expect(campo('codigo')!.getAttribute('inputmode')).toBe('numeric');
    expect(campo('codigo')!.getAttribute('autocomplete')).toBe('one-time-code');
    expect(document.activeElement).toBe(campo('codigo'));
    expect(texto('button[type="submit"]')).toBe('Ingresar');
    expect(boton('Volver')).toBeDefined();
    expect(campo('email')).toBeNull();
    expect(el().querySelector('svg.qr')).toBeNull();
    expect(localStorage.getItem('ferreteria.token')).toBeNull();
    expect(navegarA).not.toHaveBeenCalled();

    await escribir('codigo', '123456');
    await enviar();
    const verificar = pedido('/auth/totp/verificar');
    expect(verificar.request.body).toEqual({ tokenTotp: 'totp-1', codigo: '123456' });
    verificar.flush({ token: 'jwt-admin', sesion: sesionAdmin, segundoPaso: null, tokenTotp: null });
    await estable();

    expect(localStorage.getItem('ferreteria.token')).toBe('jwt-admin');
    expect(JSON.parse(localStorage.getItem('ferreteria.sesion')!)).toEqual(sesionAdmin);
    expect(navegarA).toHaveBeenCalledWith('/admin/ferreterias');
  });

  it('S2: con ALTA dibuja el QR con margen, muestra la clave de a 4 y el primer código lo activa', async () => {
    await crear();
    await ingresarComoAdmin('ALTA');

    const alta = pedido('/auth/totp/alta');
    expect(alta.request.method).toBe('POST');
    expect(alta.request.body).toEqual({ tokenTotp: 'totp-1' });
    alta.flush({ clave: 'JBSWY3DPEHPK3PXPJBSW', uri: 'otpauth://totp/x', qr: ['1101', '0110', '1011', '0001'] });
    await estable();

    expect(texto('h2')).toBe('Activá el código de la app');
    const parrafos = [...el().querySelectorAll('p')].map((p) => p.textContent?.trim());
    expect(parrafos).toContain('1. Escaneá este código QR con Google Authenticator. Se muestra solo esta vez.');
    expect(parrafos).toContain('¿No podés escanearlo? Cargá esta clave a mano:');
    expect(texto('.clave')).toBe('JBSW Y3DP EHPK 3PXP JBSW');
    expect(texto('label[for="codigo"]')).toBe('2. Escribí el código de 6 números que te muestra la app.');
    expect(texto('button[type="submit"]')).toBe('Activar e ingresar');
    expect(document.activeElement).toBe(campo('codigo'));

    // 4 módulos y 4 de silencio de cada lado: 12 de lado, con píxeles enteros por módulo y cerca de 200 px.
    const svg = el().querySelector('svg.qr')!;
    expect(svg.getAttribute('viewBox')).toBe('-4 -4 12 12');
    expect(svg.getAttribute('shape-rendering')).toBe('crispEdges');
    const lado = Number(svg.getAttribute('width'));
    expect(svg.getAttribute('height')).toBe(String(lado));
    expect(lado % 12).toBe(0);
    expect(Math.abs(lado - 200)).toBeLessThanOrEqual(20);
    const modulos = [...svg.querySelectorAll('rect')].map((r) => ['x', 'y', 'width'].map((a) => r.getAttribute(a)));
    expect(modulos).toEqual([
      ['0', '0', '2'],
      ['3', '0', '1'],
      ['1', '1', '2'],
      ['0', '2', '1'],
      ['2', '2', '2'],
      ['3', '3', '1'],
    ]);

    await escribir('codigo', '654321');
    await enviar();
    const verificar = pedido('/auth/totp/verificar');
    expect(verificar.request.body).toEqual({ tokenTotp: 'totp-1', codigo: '654321' });
    verificar.flush({ token: 'jwt-admin', sesion: sesionAdmin, segundoPaso: null, tokenTotp: null });
    await estable();

    expect(localStorage.getItem('ferreteria.token')).toBe('jwt-admin');
    expect(navegarA).toHaveBeenCalledWith('/');
  });

  it('el código acepta solo 6 números y, si no son 6, no lo manda', async () => {
    await crear();
    await ingresarComoAdmin('CODIGO');

    await escribir('codigo', '12 34a5678');
    expect(campo('codigo')!.value).toBe('123456');

    await escribir('codigo', '123');
    await enviar();

    http.expectNone((r) => r.url.endsWith('/auth/totp/verificar'));
    expect(campo('codigo')!.getAttribute('aria-invalid')).toBe('true');
    expect(texto('#codigo-error')).toBe('El código tiene 6 números.');
    expect(document.activeElement).toBe(campo('codigo'));
  });

  it.each([
    ['CODIGO', '/auth/totp/verificar'],
    ['ALTA', '/auth/totp/alta'],
  ] as const)(
    'con %s y el tokenTotp vencido (%s) vuelve a email y contraseña con el aviso del servidor',
    async (segundoPaso, ruta) => {
      await crear();
      await ingresarComoAdmin(segundoPaso);
      if (segundoPaso === 'CODIGO') {
        await escribir('codigo', '123456');
        await enviar();
      }

      pedido(ruta).flush(...fallo(401, VENCIDO, 'TOTP_VENCIDO'));
      await estable();

      expect(campo('codigo')).toBeNull();
      expect(campo('email')!.value).toBe('admin@demo.local');
      expect(texto('#login-error')).toBe(VENCIDO);
      expect(localStorage.getItem('ferreteria.token')).toBeNull();
      expect(navegarA).not.toHaveBeenCalled();
    },
  );

  it('con el código incorrecto se queda, vacía el campo, lo enfoca y muestra el aviso del servidor', async () => {
    await crear();
    await ingresarComoAdmin('CODIGO');
    await escribir('codigo', '111111');
    await enviar();

    pedido('/auth/totp/verificar').flush(...fallo(401, INCORRECTO, 'CODIGO_INCORRECTO'));
    await estable();

    expect(texto('h2')).toBe('Código de la app');
    expect(campo('codigo')!.value).toBe('');
    expect(document.activeElement).toBe(campo('codigo'));
    expect(texto('#login-error')).toBe(INCORRECTO);
    expect(campo('codigo')!.getAttribute('aria-describedby')).toBe('login-error');
    expect((el().querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(false);
    expect(localStorage.getItem('ferreteria.token')).toBeNull();
    expect(navegarA).not.toHaveBeenCalled();
  });

  it.each([
    [429, 'Demasiados intentos. Probá de nuevo en 5 minutos.'],
    [400, 'El código tiene 6 números.'],
    [409, 'Primero escaneá el código QR.'],
  ])('un %s muestra el mensaje del servidor y sigue en el código', async (status, mensaje) => {
    await crear();
    await ingresarComoAdmin('CODIGO');
    await escribir('codigo', '123456');
    await enviar();

    pedido('/auth/totp/verificar').flush(...fallo(status, mensaje));
    await estable();

    expect(texto('#login-error')).toBe(mensaje);
    expect(texto('h2')).toBe('Código de la app');
    expect(campo('codigo')!.value).toBe('123456');
    expect(navegarA).not.toHaveBeenCalled();
  });

  it('un 409 al pedir el alta (ya activado) muestra el mensaje del servidor', async () => {
    await crear();
    await ingresarComoAdmin('ALTA');

    pedido('/auth/totp/alta').flush(...fallo(409, 'El código de la app ya está activado.'));
    await estable();

    expect(texto('#login-error')).toBe('El código de la app ya está activado.');
    expect(el().querySelector('svg.qr')).toBeNull();
  });

  it('«Volver» lleva a email y contraseña, sin avisos, y el próximo ingreso usa el tokenTotp nuevo', async () => {
    await crear();
    await ingresarComoAdmin('CODIGO');
    await escribir('codigo', '111111');
    await enviar();
    pedido('/auth/totp/verificar').flush(...fallo(401, INCORRECTO, 'CODIGO_INCORRECTO'));
    await estable();

    boton('Volver')!.click();
    await estable();

    expect(campo('codigo')).toBeNull();
    expect(texto('#login-error')).toBeUndefined();
    expect(document.activeElement).toBe(campo('email'));

    await enviar();
    pedido('/auth/login').flush({ token: null, sesion: null, segundoPaso: 'CODIGO', tokenTotp: 'totp-2' });
    await estable();
    expect(campo('codigo')!.value).toBe('');
    await escribir('codigo', '222222');
    await enviar();
    const verificar = pedido('/auth/totp/verificar');
    expect(verificar.request.body).toEqual({ tokenTotp: 'totp-2', codigo: '222222' });
    verificar.flush({ token: 'jwt-admin', sesion: sesionAdmin });
  });
});
