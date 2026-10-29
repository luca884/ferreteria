import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { AvisosFacturacion as Componente } from './avisos-facturacion';
import { AvisosFacturacion, INTERVALO_AVISOS_MS } from './avisos-facturacion.models';
import { AvisosFacturacionService } from './avisos-facturacion.service';

describe('AvisosFacturacion (avisos del Dueño en el mostrador, CU-16 punto 8, C4, S11)', () => {
  let fixture: ComponentFixture<Componente>;
  /** Lo que responde el servidor; null: la lectura falla. */
  let respuesta: AvisosFacturacion | null;
  let leer: ReturnType<typeof vi.fn>;

  const sinNada: AvisosFacturacion = { rechazadas: 0, pendientesViejas: 0, enRevision: 0, bloqueo: null };
  const BLOQUEO =
    'ARCA rechazó el certificado: Certificado expirado (cms.cert.expired). La facturación está frenada hasta que cambies el certificado o pruebes la conexión.';

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const cuentas = () =>
    Array.from(el().querySelectorAll<HTMLAnchorElement>('.aviso-facturacion.cuentas a')).map((a) => [
      limpio(a.textContent),
      a.getAttribute('href'),
    ]);
  const bloqueo = () => el().querySelector<HTMLElement>('.aviso-facturacion.bloqueo');

  async function crear(rol: 'DUENO' | 'VENDEDOR' = 'DUENO') {
    leer = vi.fn(() => (respuesta ? of(respuesta) : throwError(() => new HttpErrorResponse({ status: 500 }))));
    const sesion = signal({ userId: 7, nombre: 'Ana', email: 'a@x.com', rol, ferreteriaId: 3, ferreteriaNombre: 'Tornillo' });
    await TestBed.configureTestingModule({
      imports: [Componente],
      providers: [
        provideRouter([]),
        { provide: AvisosFacturacionService, useValue: { leer } },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Componente);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function avanzar(ms: number) {
    vi.advanceTimersByTime(ms);
    await refrescar();
  }

  beforeEach(() => {
    vi.useFakeTimers();
    respuesta = sinNada;
  });
  afterEach(() => vi.useRealTimers());

  it('al abrir lee los avisos y muestra solo lo que no es cero, cada cuenta con su enlace a Ventas filtrada', async () => {
    respuesta = { rechazadas: 2, pendientesViejas: 0, enRevision: 1, bloqueo: null };
    await crear();

    expect(leer).toHaveBeenCalledTimes(1);
    const aviso = el().querySelector('.aviso-facturacion.cuentas')!;
    expect(aviso.getAttribute('role')).toBe('status');
    expect(limpio(aviso.querySelector('.titulo')?.textContent)).toBe('Facturas para revisar en Ventas:');
    expect(cuentas()).toEqual([
      ['2 rechazadas', '/ventas?fiscal=RECHAZADAS'],
      ['1 en revisión', '/ventas?fiscal=PENDIENTES'],
    ]);
    expect(bloqueo()).toBeNull();
  });

  it('las pendientes de CAE de más de 30 minutos van a «Pendientes»; una sola va en singular', async () => {
    respuesta = { rechazadas: 1, pendientesViejas: 3, enRevision: 0, bloqueo: null };
    await crear();

    expect(cuentas()).toEqual([
      ['1 rechazada', '/ventas?fiscal=RECHAZADAS'],
      ['3 pendientes de CAE desde hace más de 30 minutos', '/ventas?fiscal=PENDIENTES'],
    ]);

    TestBed.resetTestingModule();
    respuesta = { rechazadas: 0, pendientesViejas: 1, enRevision: 4, bloqueo: null };
    await crear();
    expect(cuentas()).toEqual([
      ['1 pendiente de CAE desde hace más de 30 minutos', '/ventas?fiscal=PENDIENTES'],
      ['4 en revisión', '/ventas?fiscal=PENDIENTES'],
    ]);
  });

  it('la facturación bloqueada por ARCA se dice con el texto del servidor tal cual y lleva a Facturación', async () => {
    respuesta = { ...sinNada, bloqueo: BLOQUEO };
    await crear();

    expect(el().querySelector('.aviso-facturacion.cuentas')).toBeNull();
    expect(bloqueo()!.getAttribute('role')).toBe('status');
    expect(limpio(bloqueo()!.querySelector('.texto')?.textContent)).toBe(BLOQUEO);
    expect(bloqueo()!.querySelector('a')!.getAttribute('href')).toBe('/configuracion/facturacion');
  });

  it('con todo en cero y sin bloqueo no muestra nada', async () => {
    await crear();

    expect(el().querySelector('.aviso-facturacion')).toBeNull();
  });

  it('vuelve a leer cada minuto: lo nuevo aparece, lo resuelto se va y una lectura que falla deja lo último', async () => {
    await crear();
    expect(el().querySelector('.aviso-facturacion')).toBeNull();

    respuesta = { rechazadas: 1, pendientesViejas: 0, enRevision: 0, bloqueo: null };
    await avanzar(INTERVALO_AVISOS_MS - 1);
    expect(leer).toHaveBeenCalledTimes(1);
    await avanzar(1);
    expect(leer).toHaveBeenCalledTimes(2);
    expect(cuentas()).toEqual([['1 rechazada', '/ventas?fiscal=RECHAZADAS']]);

    respuesta = null;
    await avanzar(INTERVALO_AVISOS_MS);
    expect(leer).toHaveBeenCalledTimes(3);
    expect(cuentas()).toEqual([['1 rechazada', '/ventas?fiscal=RECHAZADAS']]);

    respuesta = sinNada;
    await avanzar(INTERVALO_AVISOS_MS);
    expect(el().querySelector('.aviso-facturacion')).toBeNull();
  });

  it('al Vendedor no se le pide nada (el servidor le daría 403)', async () => {
    respuesta = { rechazadas: 2, pendientesViejas: 1, enRevision: 1, bloqueo: BLOQUEO };
    await crear('VENDEDOR');
    await avanzar(INTERVALO_AVISOS_MS * 3);

    expect(leer).not.toHaveBeenCalled();
    expect(el().querySelector('.aviso-facturacion')).toBeNull();
  });

  it('al salir del mostrador deja de leer', async () => {
    await crear();
    fixture.destroy();

    vi.advanceTimersByTime(INTERVALO_AVISOS_MS * 3);

    expect(leer).toHaveBeenCalledTimes(1);
  });
});
