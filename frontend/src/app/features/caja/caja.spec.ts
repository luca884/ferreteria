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
  const sinCaja = (propuesto = 0, hayCierreAnterior = true): CajaVigente => ({
    estado: 'SIN_CAJA',
    caja: null,
    efectivoPropuesto: propuesto,
    hayCierreAnterior,
  });
  const conCaja = (caja = abierta()): CajaVigente => ({
    estado: caja.estado as 'ABIERTA',
    caja,
    efectivoPropuesto: 0,
    hayCierreAnterior: true,
  });

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
    ventas: { EFECTIVO: 1800, DEBITO: 1000, CREDITO: 800, TRANSFERENCIA: 1000, CUENTA_CORRIENTE: 0 },
    totalVentas: 4600,
    cobranzas: { EFECTIVO: 0, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 0, CUENTA_CORRIENTE: 0 },
    totalCobranzas: 0,
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
        { provide: AuthService, useValue: { esDueno: signal(dueno), sesion: signal({ ferreteriaNombre: 'Ferretería El Tornillo' }) } },
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

      expect((input('efectivo-inicial') as HTMLInputElement).value).toBe('7.000');
      expect(el().querySelector('form.cierre')).toBeNull();
      expect(el().querySelector('form.movimiento')).toBeNull();
    });

    it('la primera apertura (nunca hubo un cierre) pide contar el efectivo de la caja, sin hablar de un cierre que no existe (arreglos-confusos, S3)', async () => {
      await crear(sinCaja(0, false));

      const ayuda = normal(el().querySelector('form.apertura .ayuda')?.textContent);
      expect(ayuda).toBe('Sin caja abierta no se puede vender. Es la primera apertura: contá el efectivo que hay en la caja.');
      expect(ayuda).not.toContain('último cierre');
    });

    it('con un cierre anterior sigue diciendo que se propone el contado del último cierre, aunque haya contado $ 0 (arreglos-confusos, S3)', async () => {
      await crear(sinCaja(0, true));

      const ayuda = normal(el().querySelector('form.apertura .ayuda')?.textContent);
      expect(ayuda).toBe('Sin caja abierta no se puede vender. Se propone el efectivo contado en el último cierre.');
    });

    it('al cerrar la caja en esta misma sesión, la apertura que sigue ya habla del último cierre (arreglos-confusos, S3)', async () => {
      await crear(conCaja());
      await escribir('efectivo-contado', '5700');
      await enviar('form.cierre');
      boton('Cerrar la caja')!.click();
      await refrescar();

      expect(normal(el().querySelector('form.apertura .ayuda')?.textContent)).toContain('Se propone el efectivo contado en el último cierre.');
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

    it('el efectivo lleva la máscara de importes: 50000 se ve 50.000 y abre con cincuenta mil; el punto no entra', async () => {
      await crear(sinCaja());

      await escribir('efectivo-inicial', '50000');
      expect((input('efectivo-inicial') as HTMLInputElement).value).toBe('50.000');
      await enviar('form.apertura');
      expect(servicio.abrir).toHaveBeenLastCalledWith(50000);
    });

    it('el punto tecleado no entra, la coma sí y el menos tampoco: no hay efectivo negativo', async () => {
      await crear(sinCaja());

      await escribir('efectivo-inicial', '1.234,5');
      expect((input('efectivo-inicial') as HTMLInputElement).value).toBe('1.234,5');
      await escribir('efectivo-inicial', '-1.5');
      expect((input('efectivo-inicial') as HTMLInputElement).value).toBe('15');
      await enviar('form.apertura');

      expect(servicio.abrir).toHaveBeenCalledWith(15);
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

    it('el importe lleva la máscara: 50000 se ve 50.000 y se manda como cincuenta mil', async () => {
      await crear(conCaja());

      await escribir('importe-movimiento', '50000');
      await escribir('motivo-movimiento', 'Flete');
      expect((input('importe-movimiento') as HTMLInputElement).value).toBe('50.000');
      await enviar('form.movimiento');

      expect(servicio.registrarMovimiento).toHaveBeenCalledWith(4, { tipo: 'INGRESO', importe: 50000, motivo: 'Flete' });
    });

    it('sin importe o sin motivo no se manda', async () => {
      await crear(conCaja());

      await enviar('form.movimiento');

      expect(servicio.registrarMovimiento).not.toHaveBeenCalled();
    });

    describe('egreso mayor al efectivo (GDS-36)', () => {
      const sinEfectivo = (efectivo: Record<string, unknown>, message: string) =>
        throwError(
          () =>
            new HttpErrorResponse({
              status: 422,
              error: { status: 422, message, motivo: 'EFECTIVO_INSUFICIENTE', efectivo },
            }),
        );

      async function cargarGasto() {
        await escribir('tipo-movimiento', 'GASTO');
        await escribir('importe-movimiento', '150');
        await escribir('motivo-movimiento', 'Cadete');
        await enviar('form.movimiento');
      }

      it('al vendedor le muestra el rechazo sin cifras ni forma de confirmar', async () => {
        await crear(conCaja());
        servicio.registrarMovimiento.mockReturnValueOnce(
          sinEfectivo(
            { puedeConfirmar: false },
            'No hay efectivo suficiente en la caja para este gasto. Pedile al Dueño que lo cargue.',
          ),
        );

        await cargarGasto();

        expect(normal(el().querySelector('.error')?.textContent)).toBe(
          'No hay efectivo suficiente en la caja para este gasto. Pedile al Dueño que lo cargue.',
        );
        expect(boton('Registrar igual')).toBeUndefined();
      });

      it('al dueño le avisa con las cifras y «Registrar igual» lo manda confirmado', async () => {
        await crear(conCaja(), true);
        servicio.registrarMovimiento.mockReturnValueOnce(
          sinEfectivo(
            { puedeConfirmar: true, disponible: 100, importe: 150, faltante: 50 },
            'No alcanza el efectivo de la caja para este gasto.',
          ),
        );

        await cargarGasto();

        const aviso = normal(el().querySelector('.falta-efectivo')?.textContent);
        expect(aviso).toContain('No alcanza el efectivo de la caja para este gasto.');
        expect(aviso).toContain('Hay $ 100,00 en efectivo y faltan $ 50,00: la caja va a quedar en negativo.');
        expect(el().querySelector('.falta-efectivo')?.getAttribute('role')).toBe('alert');

        boton('Registrar igual')!.click();
        await refrescar();

        expect(servicio.registrarMovimiento).toHaveBeenLastCalledWith(4, {
          tipo: 'GASTO',
          importe: 150,
          motivo: 'Cadete',
          confirmaSinEfectivo: true,
        });
        expect(el().querySelector('.falta-efectivo')).toBeNull();
        expect(normal(el().querySelector('.alerta.exito')?.textContent)).toBe('Se cargó el gasto de $ 150,00.');
      });

      it('cambiar el importe o cancelar descarta el aviso sin mandar nada', async () => {
        await crear(conCaja(), true);
        servicio.registrarMovimiento.mockReturnValue(
          sinEfectivo(
            { puedeConfirmar: true, disponible: 100, importe: 150, faltante: 50 },
            'No alcanza el efectivo de la caja para este gasto.',
          ),
        );

        await cargarGasto();
        await escribir('importe-movimiento', '90');
        expect(el().querySelector('.falta-efectivo')).toBeNull();

        await enviar('form.movimiento');
        boton('Cancelar')!.click();
        await refrescar();

        expect(el().querySelector('.falta-efectivo')).toBeNull();
        expect(servicio.registrarMovimiento).toHaveBeenCalledTimes(2);
      });
    });
  });

  describe('cierre ciego', () => {
    it('el vendedor no ve el esperado ni los totales mientras la caja está abierta', async () => {
      await crear(conCaja());

      expect(servicio.detalle).not.toHaveBeenCalled();
      expect(el().querySelector('app-resumen-caja')).toBeNull();
      expect(el().textContent).not.toContain('esperado');
    });

    it('el vendedor tampoco ve las cobranzas mientras la caja está abierta (CC-053)', async () => {
      await crear(conCaja());

      expect(el().textContent).not.toContain('Cobranzas');
    });

    it('al cerrar, el resumen del cierre incluye las cobranzas del día', async () => {
      await crear(conCaja());
      servicio.cerrar.mockReturnValue(
        of(
          detalle({
            estado: 'CERRADA',
            efectivoContado: 5700,
            diferencia: 0,
            cobranzas: { EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 300, CUENTA_CORRIENTE: 0 },
            totalCobranzas: 1000,
          }),
        ),
      );
      await escribir('efectivo-contado', '5700');
      await enviar('form.cierre');
      boton('Cerrar la caja')!.click();
      await refrescar();

      const resumen = normal(el().querySelector('app-resumen-caja')?.textContent);
      expect(resumen).toContain('Cobranzas en efectivo $ 700,00');
      expect(resumen).toContain('Cobranzas por transferencia $ 300,00');
    });

    it('el vendedor tampoco ve las anulaciones mientras la caja está abierta (CC-100, cierre ciego)', async () => {
      await crear(conCaja());

      expect(el().textContent).not.toContain('Anulaciones');
    });

    it('al cerrar, el resumen del cierre incluye las anulaciones del día y el esperado que manda el backend (CC-100)', async () => {
      await crear(conCaja());
      servicio.cerrar.mockReturnValue(
        of(
          detalle({
            estado: 'CERRADA',
            efectivoContado: 5000,
            diferencia: 0,
            cobranzas: { EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 300, CUENTA_CORRIENTE: 0 },
            totalCobranzas: 1000,
            anulaciones: { EFECTIVO: 700, DEBITO: 0, CREDITO: 0, TRANSFERENCIA: 300, CUENTA_CORRIENTE: 0 },
            totalAnulaciones: 1000,
            efectivoEsperado: 5000,
          }),
        ),
      );
      await escribir('efectivo-contado', '5000');
      await enviar('form.cierre');
      boton('Cerrar la caja')!.click();
      await refrescar();

      const resumen = normal(el().querySelector('app-resumen-caja')?.textContent);
      expect(resumen).toContain('Anulaciones de cobranzas en efectivo −$ 700,00');
      expect(resumen).toContain('Anulaciones de cobranzas por transferencia −$ 300,00');
      expect(resumen).toContain('Efectivo esperado $ 5.000,00');
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

    it('el contado lleva la máscara: 50000 se ve 50.000 y se confirma con cincuenta mil', async () => {
      await crear(conCaja());

      await escribir('efectivo-contado', '50000');
      expect((input('efectivo-contado') as HTMLInputElement).value).toBe('50.000');
      await enviar('form.cierre');

      expect(normal(el().querySelector('[role="alertdialog"]')?.textContent)).toContain('$ 50.000,00 contados');
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

    it('con la caja abierta ve el resumen y los ingresos, gastos y retiros antes de «Cerrar la caja»', async () => {
      await crear(conCaja(), true);

      const titulos = Array.from(el().querySelectorAll('h2')).map((h) => normal(h.textContent));
      expect(titulos.indexOf('Resumen de la caja')).toBeGreaterThan(-1);
      expect(titulos.indexOf('Ingresos, gastos y retiros')).toBeGreaterThan(-1);
      expect(titulos.indexOf('Resumen de la caja')).toBeLessThan(titulos.indexOf('Cerrar la caja'));
      expect(titulos.indexOf('Ingresos, gastos y retiros')).toBeLessThan(titulos.indexOf('Cerrar la caja'));
    });
  });

  describe('texto explicativo', () => {
    const texto =
      'Abrí la caja al empezar el día con el efectivo que hay. Durante el día cargá los ingresos, gastos y retiros. Al terminar, cerrala contando el efectivo que quedó.';
    const ayudas = () => Array.from(el().querySelectorAll('p.ayuda')).map((p) => normal(p.textContent));

    it('explica cómo se usa la caja cuando está cerrada', async () => {
      await crear(sinCaja());
      expect(ayudas()).toContain(texto);
    });

    it('y también con la caja abierta', async () => {
      await crear(conCaja());
      expect(ayudas()).toContain(texto);
    });
  });

  describe('hoja impresa', () => {
    it('el Vendedor que cierra imprime el resumen del cierre: una sola hoja, con la ferretería', async () => {
      await crear(conCaja());
      await escribir('efectivo-contado', '5700');
      await enviar('form.cierre');
      boton('Cerrar la caja')!.click();
      await refrescar();
      const imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);

      el().querySelector<HTMLButtonElement>('app-resumen-caja button.imprimir')!.click();

      expect(imprimir).toHaveBeenCalledOnce();
      expect(el().querySelectorAll('.caja-imprimible').length).toBe(1);
      expect(normal(el().querySelector('app-resumen-caja .encabezado-impreso')?.textContent)).toContain('Ferretería El Tornillo');
      imprimir.mockRestore();
    });

    it('el Dueño tiene una sola hoja con la caja abierta y una sola después de cerrarla', async () => {
      await crear(conCaja(), true);
      expect(el().querySelectorAll('.caja-imprimible').length).toBe(1);

      await escribir('efectivo-contado', '5700');
      await enviar('form.cierre');
      boton('Cerrar la caja')!.click();
      await refrescar();

      expect(el().querySelectorAll('.caja-imprimible').length).toBe(1);
    });
  });
});
