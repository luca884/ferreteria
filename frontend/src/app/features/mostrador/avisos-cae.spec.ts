import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { VentaFacturada } from '../../shared/factura/factura.models';
import { documentoB, fiscalDe } from '../../shared/factura/factura.testing';
import { VentaConsultada } from '../ventas/ventas.models';
import { VentasService } from '../ventas/ventas.service';
import { ventaDePrueba } from '../ventas/ventas.testing';
import { AvisosCae } from './avisos-cae';
import { INTERVALO_AVISOS_MS } from './avisos-facturacion.models';
import { ProvisoriaAutorizada } from './venta.models';
import { VentaService } from './venta.service';

describe('AvisosCae («Llegó el CAE», CU-07 Reimprimir, C7, S14)', () => {
  let fixture: ComponentFixture<AvisosCae>;
  /** Lo que responde GET /ventas/provisorias-autorizadas; null: la lectura falla. */
  let llegadas: ProvisoriaAutorizada[] | null;
  let leer: ReturnType<typeof vi.fn>;
  let obtener: ReturnType<typeof vi.fn>;
  let abiertas: VentaFacturada[];
  let cerrados: number;
  let abrible: boolean;

  const venta12: ProvisoriaAutorizada = { ventaId: 120, numero: 12, factura: 'Factura B 0001-00000045' };
  const venta13: ProvisoriaAutorizada = { ventaId: 130, numero: 13, factura: 'Factura A 0001-00000007' };
  const hoja = (ventaId: number, numero: number): VentaConsultada =>
    ventaDePrueba({ ventaId, numero, fiscal: fiscalDe(documentoB({ numero: 45 })) });

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const avisos = () => Array.from(el().querySelectorAll<HTMLElement>('.aviso-cae'));
  const textos = () => avisos().map((a) => limpio(a.querySelector('.texto')?.textContent));
  const boton = (aviso: HTMLElement, nombre: string) =>
    Array.from(aviso.querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => limpio(b.textContent) === nombre || b.getAttribute('aria-label') === nombre,
    );

  async function crear() {
    leer = vi.fn(() => (llegadas ? of(llegadas) : throwError(() => new HttpErrorResponse({ status: 500 }))));
    const sesion = signal({ userId: 7, nombre: 'Ana', email: 'a@x.com', rol: 'VENDEDOR', ferreteriaId: 3, ferreteriaNombre: 'Tornillo' });
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [AvisosCae],
      providers: [
        { provide: VentaService, useValue: { provisoriasAutorizadas: leer } },
        { provide: VentasService, useValue: { factura: obtener } },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AvisosCae);
    abiertas = [];
    cerrados = 0;
    fixture.componentRef.setInput('puedeAbrir', () => abrible);
    fixture.componentInstance.abrir.subscribe((v) => abiertas.push(v));
    fixture.componentInstance.cerrado.subscribe(() => cerrados++);
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
    localStorage.clear();
    vi.useFakeTimers();
    llegadas = [];
    abrible = true;
    obtener = vi.fn((id: number) => of(hoja(id, id / 10)));
  });
  afterEach(() => vi.useRealTimers());

  it('al abrir pregunta por las ventas a las que les llegó el CAE y avisa cada una, con su factura y sin enlace a Ventas', async () => {
    llegadas = [venta12, venta13];
    await crear();

    expect(leer).toHaveBeenCalledTimes(1);
    expect(textos()).toEqual(['Llegó el CAE de la venta N.º 12', 'Llegó el CAE de la venta N.º 13']);
    expect(limpio(avisos()[0].querySelector('.factura')?.textContent)).toBe('Factura B 0001-00000045');
    expect(avisos()[0].getAttribute('role')).toBe('status');
    expect(boton(avisos()[0], 'Reimprimir factura')).toBeDefined();
    expect(boton(avisos()[0], 'Cerrar el aviso de la venta N.º 12')).toBeDefined();
    expect(el().querySelector('a')).toBeNull();
  });

  it('vuelve a preguntar cada minuto: una venta nueva suma su aviso; una lectura que falla deja los que había', async () => {
    llegadas = [venta12];
    await crear();

    llegadas = [venta12, venta13];
    await avanzar(INTERVALO_AVISOS_MS);
    expect(leer).toHaveBeenCalledTimes(2);
    expect(textos()).toEqual(['Llegó el CAE de la venta N.º 12', 'Llegó el CAE de la venta N.º 13']);

    llegadas = null;
    await avanzar(INTERVALO_AVISOS_MS);
    expect(leer).toHaveBeenCalledTimes(3);
    expect(textos()).toHaveLength(2);
  });

  it('«Reimprimir factura» pide la hoja de esa venta, la entrega al mostrador y descarta el aviso para siempre', async () => {
    llegadas = [venta12, venta13];
    await crear();

    boton(avisos()[0], 'Reimprimir factura')!.click();
    await refrescar();

    expect(obtener).toHaveBeenCalledWith(120);
    expect(abiertas).toHaveLength(1);
    expect(abiertas[0].numero).toBe(12);
    expect(abiertas[0].fiscal.documento.numero).toBe(45);
    expect(textos()).toEqual(['Llegó el CAE de la venta N.º 13']);

    // El servidor la sigue mandando mientras la caja esté abierta: el aviso no vuelve, ni al volver al mostrador.
    await avanzar(INTERVALO_AVISOS_MS);
    expect(textos()).toEqual(['Llegó el CAE de la venta N.º 13']);
    fixture.destroy();
    await crear();
    expect(textos()).toEqual(['Llegó el CAE de la venta N.º 13']);
  });

  it('Cerrar descarta el aviso para siempre sin pedir la hoja, y avisa al mostrador para devolver el foco', async () => {
    llegadas = [venta12];
    await crear();

    boton(avisos()[0], 'Cerrar el aviso de la venta N.º 12')!.click();
    await refrescar();

    expect(avisos()).toHaveLength(0);
    expect(obtener).not.toHaveBeenCalled();
    expect(cerrados).toBe(1);
    await avanzar(INTERVALO_AVISOS_MS);
    expect(avisos()).toHaveLength(0);
    fixture.destroy();
    await crear();
    expect(avisos()).toHaveLength(0);
  });

  it('si al llegar la hoja hay un diálogo abierto, no lo pisa: el aviso queda para reimprimir después', async () => {
    llegadas = [venta12];
    await crear();
    const respuesta = new Subject<VentaConsultada>();
    obtener.mockReturnValueOnce(respuesta);

    boton(avisos()[0], 'Reimprimir factura')!.click();
    await refrescar();
    expect(boton(avisos()[0], 'Reimprimir factura')!.disabled).toBe(true);
    abrible = false;
    respuesta.next(hoja(120, 12));
    respuesta.complete();
    await refrescar();

    expect(abiertas).toHaveLength(0);
    expect(textos()).toEqual(['Llegó el CAE de la venta N.º 12']);
    expect(boton(avisos()[0], 'Reimprimir factura')!.disabled).toBe(false);
  });

  it('si la hoja no se puede traer (o viene sin documento) lo dice en el aviso, que queda', async () => {
    llegadas = [venta12];
    await crear();
    obtener.mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({ status: 404, error: { status: 404, message: 'Venta no encontrada' } })),
    );

    boton(avisos()[0], 'Reimprimir factura')!.click();
    await refrescar();

    const alerta = avisos()[0].querySelector('[role="alert"]');
    expect(limpio(alerta?.textContent)).toBe('Venta no encontrada');
    expect(abiertas).toHaveLength(0);

    obtener.mockReturnValueOnce(of(ventaDePrueba({ ventaId: 120, numero: 12 })));
    boton(avisos()[0], 'Reimprimir factura')!.click();
    await refrescar();
    expect(limpio(avisos()[0].querySelector('[role="alert"]')?.textContent)).toBe(
      'No se pudo abrir la factura. Probá de nuevo.',
    );
    expect(abiertas).toHaveLength(0);
    expect(textos()).toEqual(['Llegó el CAE de la venta N.º 12']);
  });

  it('al salir del mostrador deja de preguntar', async () => {
    await crear();
    fixture.destroy();

    vi.advanceTimersByTime(INTERVALO_AVISOS_MS * 3);

    expect(leer).toHaveBeenCalledTimes(1);
  });
});
