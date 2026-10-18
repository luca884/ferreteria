import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Caja } from './caja';
import { CajaAbierta, CajaVigente, DetalleCaja } from './caja.models';
import { CajaService } from './caja.service';

registerLocaleData(localeEsAr);

describe('Caja (CU-10)', () => {
  const abierta = (cambios: Partial<CajaAbierta> = {}): CajaAbierta => ({
    id: 4,
    estado: 'ABIERTA',
    fechaApertura: '2026-10-16T08:00:00',
    abiertaPor: 'Ana',
    efectivoInicial: 5000,
    ...cambios,
  });
  const sinCaja = (propuesto = 0): CajaVigente => ({ estado: 'SIN_CAJA', caja: null, efectivoPropuesto: propuesto });
  const conCaja = (caja = abierta()): CajaVigente => ({ estado: caja.estado as 'ABIERTA', caja, efectivoPropuesto: 0 });

  const detalle = (cambios: Partial<DetalleCaja> = {}): DetalleCaja => ({
    id: 4,
    estado: 'ABIERTA',
    fechaApertura: '2026-10-16T08:00:00',
    abiertaPor: 'Ana',
    efectivoInicial: 5000,
    efectivoCierreAnterior: null,
    diferenciaApertura: null,
    fechaCierre: null,
    cerradaPor: null,
    ventas: { EFECTIVO: 1800, DEBITO: 1000, CREDITO: 800, TRANSFERENCIA: 1000 },
    totalVentas: 4600,
    ingresos: 200,
    gastos: 300,
    retiros: 1000,
    efectivoEsperado: 5700,
    efectivoContado: null,
    diferencia: null,
    movimientos: [],
    ...cambios,
  });

  let fixture: ComponentFixture<Caja>;
  let servicio: Record<'actual' | 'abrir' | 'registrarMovimiento' | 'cerrar' | 'detalle', ReturnType<typeof vi.fn>>;

  async function crear(vigente: CajaVigente, dueno = false) {
    servicio = {
      actual: vi.fn(() => of(vigente)),
      abrir: vi.fn((efectivo: number) => of(abierta({ efectivoInicial: efectivo }))),
      registrarMovimiento: vi.fn((_id: number, m: { tipo: string; importe: number; motivo: string }) =>
        of({ id: 1, ...m, fecha: '2026-10-16T10:00:00', usuario: 'Ana' }),
      ),
      cerrar: vi.fn((_id: number, contado: number) =>
        of(detalle({ estado: 'CERRADA', efectivoContado: contado, diferencia: contado - 5700, cerradaPor: 'Ana' })),
      ),
      detalle: vi.fn(() => of(detalle())),
    };
    await TestBed.configureTestingModule({
      imports: [Caja],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: CajaService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Caja);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const input = (id: string) => el().querySelector<HTMLInputElement | HTMLSelectElement>(`#${id}`);

  async function escribir(id: string, valor: string) {
    const campo = input(id)!;
    campo.value = valor;
    campo.dispatchEvent(new Event(campo instanceof HTMLSelectElement ? 'change' : 'input'));
    await refrescar();
  }

  async function enviar(selector: string) {
    el().querySelector(selector)!.dispatchEvent(new Event('submit'));
    await refrescar();
  }

  const boton = (texto: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => normal(b.textContent) === texto);

  describe('apertura', () => {
    it('sin caja muestra la apertura con el efectivo propuesto (el contado del último cierre)', async () => {
      await crear(sinCaja(7000));

      expect((input('efectivo-inicial') as HTMLInputElement).valueAsNumber).toBe(7000);
      expect(el().querySelector('form.cierre')).toBeNull();
      expect(el().querySelector('form.movimiento')).toBeNull();
    });

    it('abre con el monto editado y queda la caja abierta', async () => {
      await crear(sinCaja(7000));

      await escribir('efectivo-inicial', '6500');
      await enviar('form.apertura');

      expect(servicio.abrir).toHaveBeenCalledWith(6500);
      expect(el().querySelector('form.apertura')).toBeNull();
      expect(el().querySelector('form.cierre')).not.toBeNull();
      expect(normal(el().querySelector('.alerta.exito')?.textContent)).toBe('Abriste la caja.');
    });

    it('un efectivo negativo no se manda', async () => {
      await crear(sinCaja());

      await escribir('efectivo-inicial', '-1');
      await enviar('form.apertura');

      expect(servicio.abrir).not.toHaveBeenCalled();
      expect(normal(el().querySelector('.campo-error')?.textContent)).toBe('El efectivo no puede ser negativo.');
    });

    it('si otro usuario ya la abrió, muestra el mensaje del servidor', async () => {
      await crear(sinCaja());
      servicio.abrir.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 409, error: { status: 409, message: 'Ya hay una caja abierta.' } })),
      );

      await enviar('form.apertura');

      expect(normal(el().querySelector('.error')?.textContent)).toBe('Ya hay una caja abierta.');
    });
  });

  describe('movimientos', () => {
    it('el vendedor carga ingresos y gastos; el retiro no le aparece', async () => {
      await crear(conCaja());

      const opciones = Array.from((input('tipo-movimiento') as HTMLSelectElement).options).map((o) => o.value);
      expect(opciones).toEqual(['INGRESO', 'GASTO']);
    });

    it('el dueño también puede cargar un retiro', async () => {
      await crear(conCaja(), true);

      const opciones = Array.from((input('tipo-movimiento') as HTMLSelectElement).options).map((o) => o.value);
      expect(opciones).toEqual(['INGRESO', 'GASTO', 'RETIRO']);
    });

    it('manda tipo, importe y motivo, avisa y limpia el formulario', async () => {
      await crear(conCaja());

      await escribir('tipo-movimiento', 'GASTO');
      await escribir('importe-movimiento', '300');
      await escribir('motivo-movimiento', 'Cadete');
      await enviar('form.movimiento');

      expect(servicio.registrarMovimiento).toHaveBeenCalledWith(4, { tipo: 'GASTO', importe: 300, motivo: 'Cadete' });
      expect(normal(el().querySelector('.alerta.exito')?.textContent)).toBe('Se cargó el gasto de $ 300,00.');
      expect((input('motivo-movimiento') as HTMLInputElement).value).toBe('');
    });

    it('sin importe o sin motivo no se manda', async () => {
      await crear(conCaja());

      await enviar('form.movimiento');

      expect(servicio.registrarMovimiento).not.toHaveBeenCalled();
    });
  });

  describe('cierre ciego', () => {
    it('el vendedor no ve el esperado ni los totales mientras la caja está abierta', async () => {
      await crear(conCaja());

      expect(servicio.detalle).not.toHaveBeenCalled();
      expect(el().querySelector('app-resumen-caja')).toBeNull();
      expect(el().textContent).not.toContain('esperado');
    });

    it('pide confirmación y recién después del cierre muestra el esperado y la diferencia', async () => {
      await crear(conCaja());

      await escribir('efectivo-contado', '5650');
      await enviar('form.cierre');

      expect(servicio.cerrar).not.toHaveBeenCalled();
      expect(normal(el().querySelector('[role="alertdialog"] h2')?.textContent)).toBe(
        '¿Cerrar la caja con $ 5.650,00 contados?',
      );

      boton('Cerrar la caja')!.click();
      await refrescar();

      expect(servicio.cerrar).toHaveBeenCalledWith(4, 5650);
      const resumen = normal(el().querySelector('app-resumen-caja')?.textContent);
      expect(resumen).toContain('Efectivo esperado $ 5.700,00');
      expect(resumen).toContain('Efectivo contado $ 5.650,00');
      expect(resumen).toContain('Faltan $ 50,00');
      expect(el().querySelector('form.apertura')).not.toBeNull();
    });

    it('cancelar la confirmación no cierra nada', async () => {
      await crear(conCaja());
      await escribir('efectivo-contado', '10');
      await enviar('form.cierre');

      boton('Cancelar')!.click();
      await refrescar();

      expect(servicio.cerrar).not.toHaveBeenCalled();
      expect(el().querySelector('[role="alertdialog"]')).toBeNull();
    });

    it('la caja de otro día avisa que hay que cerrarla y solo deja cerrarla', async () => {
      await crear(conCaja(abierta({ estado: 'DE_OTRO_DIA', fechaApertura: '2026-10-15T08:00:00' })));

      expect(normal(el().querySelector('.alerta.advertencia')?.textContent)).toBe(
        'La caja del 15/10 sigue abierta. Cerrala antes de vender.',
      );
      expect(el().querySelector('form.movimiento')).toBeNull();
      expect(el().querySelector('form.cierre')).not.toBeNull();
    });
  });

  describe('dueño', () => {
    it('ve la caja del día en vivo, sin contado', async () => {
      await crear(conCaja(), true);

      expect(servicio.detalle).toHaveBeenCalledWith(4);
      const resumen = normal(el().querySelector('app-resumen-caja')?.textContent);
      expect(resumen).toContain('Efectivo esperado $ 5.700,00');
      expect(resumen).not.toContain('Efectivo contado');
    });

    it('tiene el enlace al historial de cajas cerradas', async () => {
      await crear(sinCaja(), true);

      expect(el().querySelector('a[href="/caja/historial"]')).not.toBeNull();
    });
  });
});
