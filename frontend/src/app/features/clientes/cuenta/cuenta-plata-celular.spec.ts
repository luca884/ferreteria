import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../../core/auth/auth.service';
import { Cliente } from '../cliente.models';
import { ClientesService } from '../clientes.service';
import { AjusteCobrado, AjusteResponse } from './ajuste.models';
import { PendientesCuenta, Recibo, ReciboCobrado } from './cobranza.models';
import { CobranzaService, GENERAR_CLAVE_COBRO } from './cobranza.service';
import { CuentaCliente } from './cuenta-cliente';
import { Extracto, LineaExtracto } from './extracto.models';
import { ExtractoService } from './extracto.service';

registerLocaleData(localeEsAr);

/**
 * movil-sectores T12b (S1, S5, riesgo alto: es el flujo de plata de la cuenta corriente): Cobrar (cobranza con imputación),
 * Recargo, Descuento y Anular recibo a nivel de la pantalla de la cuenta, en celular y en tablet con LITERALES.
 *
 * Estos tests se escribieron ANTES del re-layout del celular y fijan el comportamiento de HOY: pasan contra el código base
 * (sólo usan el DOM de siempre, nada de la barra «Volver» ni del pie del celular). Si el re-layout cambiara un pedido, un
 * texto, una clave de idempotencia o un rol, rompen. La estructura del celular se prueba en `dialogos-cuenta-celular.spec.ts`.
 */
describe('Cobranza, ajustes y anular recibo: la plata no cambia con el celular (T12b)', () => {
  const ana: Cliente = {
    id: 8,
    razonSocial: 'Ana Gómez',
    tipoDocumento: 'DNI',
    numeroDocumento: '12345678',
    condicionIva: 'CONSUMIDOR_FINAL',
    activo: true,
    cuentaCorrienteHabilitada: true,
    limiteCuentaCorriente: 5000,
    saldo: 1200,
  };
  const comprobante = (comprobanteId: number, n: number, pendiente: number) => ({
    comprobanteId,
    referencia: `Venta N.º ${n}`,
    fecha: '2026-10-10T09:00:00',
    importe: pendiente,
    imputado: 0,
    pendiente,
  });
  const recargoPartida = (ajusteId: number, pendiente: number) => ({
    comprobanteId: null,
    ajusteId,
    referencia: `Recargo N.º ${ajusteId}`,
    fecha: '2026-10-11T09:00:00',
    importe: pendiente,
    imputado: 0,
    pendiente,
  });
  /** Ventas de 300, 500 y 400: saldo 1.200. */
  const con3: PendientesCuenta = {
    saldo: 1200,
    aCuenta: 0,
    comprobantes: [comprobante(31, 7, 300), comprobante(32, 8, 500), comprobante(33, 9, 400)],
  };
  /** Venta 300, Recargo 200 (ajusteId 3) y venta 500: saldo 1.000. */
  const conRecargo: PendientesCuenta = {
    saldo: 1000,
    aCuenta: 0,
    comprobantes: [comprobante(31, 7, 300), recargoPartida(3, 200), comprobante(32, 8, 500)],
  };
  const sinDeuda: PendientesCuenta = { saldo: 0, aCuenta: 0, comprobantes: [] };

  const recibo = (cambios: Partial<Recibo> = {}): Recibo => ({
    id: 5,
    numero: 12,
    fecha: '2026-10-19T10:30:00',
    clienteId: 8,
    clienteNombre: 'Ana Gómez',
    medio: 'EFECTIVO',
    total: 600,
    aCuenta: 0,
    cobradoPor: 'Dueño Tornillo',
    imputaciones: [
      { comprobanteId: 31, referencia: 'Venta N.º 7', importe: 300 },
      { comprobanteId: 32, referencia: 'Venta N.º 8', importe: 300 },
    ],
    estado: 'EMITIDO',
    fechaAnulacion: null,
    anuladoPor: null,
    motivoAnulacion: null,
    ...cambios,
  });
  const lineaCobro: LineaExtracto = {
    id: 2,
    fecha: '2026-10-10T09:00:00',
    tipo: 'COBRO',
    referencia: 'Recibo N.º 12',
    debe: 0,
    haber: 600,
    saldo: 600,
    usuario: 'Ana',
    reciboId: 5,
    comprobanteId: null,
    motivo: null,
    excedioLimite: false,
    autorizadoPor: null,
    anulado: false,
  };
  const extractoBase = (items: LineaExtracto[]): Extracto => ({
    clienteId: 8,
    clienteNombre: 'Ana Gómez',
    tipoDocumento: null,
    numeroDocumento: null,
    saldoActual: 1200,
    desde: null,
    hasta: null,
    tipo: null,
    saldoInicial: 0,
    totalDebe: 1200,
    totalHaber: 0,
    saldoFinal: 1200,
    movimientos: { items, page: 0, size: 20, totalElements: items.length, totalPages: 1 },
    truncado: false,
  });
  const respuestaAjuste = (cambios: Partial<AjusteResponse> = {}): AjusteResponse => ({
    ajusteId: 3,
    numero: 3,
    referencia: 'Recargo N.º 3',
    tipo: 'RECARGO',
    importe: 500,
    motivo: 'Deuda vieja del cliente',
    fecha: '2026-10-23T18:00:00',
    saldo: 1700,
    imputaciones: [],
    ...cambios,
  });
  const errorHttp = (status: number, cuerpo: unknown) => new HttpErrorResponse({ status, error: cuerpo });
  const rechazo = (status: number, motivo: string, message: string, comprobantes?: unknown) =>
    errorHttp(status, { status, message, timestamp: '2026-10-19T13:30:00Z', motivo, comprobantes });

  let fixture: ComponentFixture<CuentaCliente>;
  let cobranza: {
    pendientes: ReturnType<typeof vi.fn>;
    cobrar: ReturnType<typeof vi.fn>;
    recibo: ReturnType<typeof vi.fn>;
    anular: ReturnType<typeof vi.fn>;
    ajustar: ReturnType<typeof vi.fn>;
  };
  let extractos: { extracto: ReturnType<typeof vi.fn> };
  let clientes: { obtener: ReturnType<typeof vi.fn> };
  let claves: string[];

  type Modo = 'celular' | 'tableta';
  const simular = (modo: Modo) =>
    vi.stubGlobal('matchMedia', (consulta: string) => ({
      matches: consulta.includes(modo === 'celular' ? '639.98px' : '1023.98px'),
      media: consulta,
    }));

  interface Opciones {
    dueno?: boolean;
    pendientes?: () => Observable<PendientesCuenta>;
    cobrar?: () => Observable<ReciboCobrado>;
    ajustar?: () => Observable<AjusteCobrado>;
    anular?: () => Observable<Recibo>;
    cliente?: Cliente;
  }

  async function crear(modo: Modo, { dueno = true, pendientes, cobrar, ajustar, anular, cliente = ana }: Opciones = {}) {
    simular(modo);
    clientes = { obtener: vi.fn(() => of(cliente)) };
    extractos = { extracto: vi.fn(() => of(extractoBase([lineaCobro]))) };
    cobranza = {
      pendientes: vi.fn(pendientes ?? (() => of(con3))),
      cobrar: vi.fn(cobrar ?? (() => of({ recibo: recibo(), repetida: false }))),
      recibo: vi.fn(() => of(recibo())),
      anular: vi.fn(anular ?? (() => of(recibo({ estado: 'ANULADO' })))),
      ajustar: vi.fn(ajustar ?? (() => of({ ajuste: respuestaAjuste(), repetida: false }))),
    };
    let n = 0;
    claves = [];
    await TestBed.configureTestingModule({
      imports: [CuentaCliente],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ClientesService, useValue: clientes },
        { provide: CobranzaService, useValue: cobranza },
        { provide: ExtractoService, useValue: { ...extractos, paraImprimir: vi.fn(() => of(extractoBase([lineaCobro]))) } },
        { provide: AuthService, useValue: { esDueno: signal(dueno), sesion: signal({ ferreteriaNombre: 'Tornillo SA' }) } },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '8' }) } } },
        {
          provide: GENERAR_CLAVE_COBRO,
          useValue: () => {
            const clave = `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
            claves.push(clave);
            return clave;
          },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(CuentaCliente);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => vi.unstubAllGlobals());

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || null;
  const cobroAbierto = () => el().querySelector('app-dialogo-cobranza');
  const ajusteAbierto = () => el().querySelector('app-dialogo-ajuste');
  const anulacionAbierta = () => el().querySelector('app-dialogo-anulacion');
  const total = () => el().querySelector<HTMLInputElement>('#total-cobranza')!;
  const importeAj = () => el().querySelector<HTMLInputElement>('#importe-ajuste')!;
  const motivoAj = () => el().querySelector<HTMLInputElement>('#motivo-ajuste')!;
  const motivoAn = () => el().querySelector<HTMLInputElement>('#motivo-anulacion')!;
  const filasCobro = () => Array.from(el().querySelectorAll<HTMLInputElement>('app-dialogo-cobranza input[data-comprobante]'));
  const filasAjuste = () => Array.from(el().querySelectorAll<HTMLInputElement>('app-dialogo-ajuste .imputacion input'));
  const valores = (inputs: HTMLInputElement[]) => inputs.map((i) => i.value);

  /** «Cobrar», «Recargo» o «Descuento»: en el celular en la fila de debajo del saldo; en la tablet, en la barra. */
  const accion = (nombre: 'Cobrar' | 'Recargo' | 'Descuento', modo: Modo) =>
    Array.from(
      el().querySelectorAll<HTMLButtonElement>(modo === 'celular' ? '.acciones-cuenta button' : 'app-barra-acciones button'),
    ).find((b) => limpio(b.textContent) === nombre)!;
  const enDialogo = (dialogo: string, selector: string) => el().querySelector<HTMLButtonElement>(`${dialogo} ${selector}`)!;

  async function pulsar(boton: HTMLElement | null | undefined) {
    boton!.click();
    await refrescar();
  }
  async function escribirEn(campo: HTMLInputElement, valor: string) {
    campo.value = valor;
    campo.dispatchEvent(new Event('input', { bubbles: true }));
    await refrescar();
  }
  async function tecla(destino: Element, key: string) {
    destino.dispatchEvent(new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true }));
    await refrescar();
  }
  async function f4() {
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F4', cancelable: true, bubbles: true }));
    await refrescar();
  }

  /** Abre Cobrar, escribe el total y confirma con Enter. */
  async function cobrarCon(modo: Modo, importe: string) {
    await pulsar(accion('Cobrar', modo));
    await escribirEn(total(), importe);
    await tecla(total(), 'Enter');
  }
  /** Abre el ajuste, escribe importe y motivo, pasa a la confirmación y la confirma. */
  async function ajustarCon(modo: Modo, tipo: 'Recargo' | 'Descuento', importe: string, motivo = 'Deuda vieja del cliente') {
    await pulsar(accion(tipo, modo));
    await escribirEn(importeAj(), importe);
    await escribirEn(motivoAj(), motivo);
    await pulsar(enDialogo('app-dialogo-ajuste', 'button.continuar'));
    await pulsar(enDialogo('app-dialogo-ajuste', 'button.confirmar-ajuste'));
  }
  /** Con un cobro en el extracto: «Anular» trae el recibo y abre el diálogo; escribe el motivo y confirma con Enter. */
  async function anularCon(motivo: string) {
    await pulsar(el().querySelector('app-movimientos-cuenta button.anular'));
    await escribirEn(motivoAn(), motivo);
    await tecla(motivoAn(), 'Enter');
  }

  const MODOS: Modo[] = ['celular', 'tableta'];

  describe.each(MODOS)('Cobrar: la cobranza con imputación (%s)', (modo) => {
    it('un cobro de 600 en efectivo imputa FIFO, 300 a la venta 7 y 300 a la 8, y manda clave, cliente, medio y total', async () => {
      await crear(modo);

      await cobrarCon(modo, '600');

      expect(cobranza.cobrar).toHaveBeenCalledExactlyOnceWith({
        clave: claves[0],
        clienteId: 8,
        medio: 'EFECTIVO',
        total: 600,
        imputaciones: [
          { comprobanteId: 31, importe: 300 },
          { comprobanteId: 32, importe: 300 },
        ],
      });
      expect(claves[0]).toMatch(/^[0-9a-f-]{36}$/);
    });

    it('al tipear el total propone la imputación: 700 son 300 + 400 y dice «A cuenta» sólo si sobra', async () => {
      await crear(modo);
      await pulsar(accion('Cobrar', modo));

      await escribirEn(total(), '700');
      expect(valores(filasCobro())).toEqual(['300,00', '400,00', '']);
      expect(texto('app-dialogo-cobranza .a-cuenta')).toBeNull();

      await escribirEn(total(), '1300');
      expect(valores(filasCobro())).toEqual(['300,00', '500,00', '400,00']);
      expect(texto('app-dialogo-cobranza .a-cuenta')).toBe('A cuenta $ 100,00');
    });

    it('un cobro mayor que la deuda imputa todo y manda el total entero: lo que sobra queda a cuenta', async () => {
      await crear(modo);

      await cobrarCon(modo, '1300');

      expect(cobranza.cobrar).toHaveBeenCalledExactlyOnceWith({
        clave: claves[0],
        clienteId: 8,
        medio: 'EFECTIVO',
        total: 1300,
        imputaciones: [
          { comprobanteId: 31, importe: 300 },
          { comprobanteId: 32, importe: 500 },
          { comprobanteId: 33, importe: 400 },
        ],
      });
    });

    it('la imputación a mano (venta 8: 500 y venta 9: 100) es la que se manda, y «Imputar en orden» vuelve a la FIFO', async () => {
      await crear(modo);
      await pulsar(accion('Cobrar', modo));
      await escribirEn(total(), '600');

      const [a, b, c] = filasCobro();
      await escribirEn(a, '');
      await escribirEn(b, '500');
      await escribirEn(c, '100');
      expect(valores(filasCobro())).toEqual(['', '500', '100']);
      expect(texto('app-dialogo-cobranza .a-cuenta')).toBeNull();
      await tecla(total(), 'Enter');

      expect(cobranza.cobrar).toHaveBeenCalledExactlyOnceWith({
        clave: claves[0],
        clienteId: 8,
        medio: 'EFECTIVO',
        total: 600,
        imputaciones: [
          { comprobanteId: 32, importe: 500 },
          { comprobanteId: 33, importe: 100 },
        ],
      });
    });

    it('«Imputar en orden» descarta lo escrito a mano y reaplica la FIFO', async () => {
      await crear(modo);
      await pulsar(accion('Cobrar', modo));
      await escribirEn(total(), '600');
      await escribirEn(filasCobro()[0], '50');

      await pulsar(enDialogo('app-dialogo-cobranza', 'button.imputar-en-orden'));

      expect(valores(filasCobro())).toEqual(['300,00', '300,00', '']);
    });

    it('«A cuenta» con lo imputado a mano: 600 con sólo 200 imputados deja «A cuenta $ 400,00»', async () => {
      await crear(modo);
      await pulsar(accion('Cobrar', modo));
      await escribirEn(total(), '600');

      await escribirEn(filasCobro()[0], '200');
      await escribirEn(filasCobro()[1], '');

      expect(texto('app-dialogo-cobranza .a-cuenta')).toBe('A cuenta $ 400,00');
    });

    it('un Recargo pendiente se imputa con su ajusteId y las ventas con su comprobanteId', async () => {
      await crear(modo, { pendientes: () => of(conRecargo) });

      await cobrarCon(modo, '600');

      expect(cobranza.cobrar).toHaveBeenCalledExactlyOnceWith({
        clave: claves[0],
        clienteId: 8,
        medio: 'EFECTIVO',
        total: 600,
        imputaciones: [
          { comprobanteId: 31, importe: 300 },
          { ajusteId: 3, importe: 200 },
          { comprobanteId: 32, importe: 100 },
        ],
      });
    });

    it('Transferencia se manda como medio TRANSFERENCIA', async () => {
      await crear(modo);
      await pulsar(accion('Cobrar', modo));
      const radios = Array.from(el().querySelectorAll<HTMLButtonElement>('app-dialogo-cobranza [role="radio"]'));
      expect(radios.map((r) => limpio(r.textContent))).toEqual(['Efectivo', 'Transferencia']);

      await pulsar(radios[1]);
      expect(radios.map((r) => r.getAttribute('aria-checked'))).toEqual(['false', 'true']);
      await escribirEn(total(), '300');
      await tecla(total(), 'Enter');

      expect(cobranza.cobrar).toHaveBeenCalledExactlyOnceWith({
        clave: claves[0],
        clienteId: 8,
        medio: 'TRANSFERENCIA',
        total: 300,
        imputaciones: [{ comprobanteId: 31, importe: 300 }],
      });
    });

    it('sin deudas es un adelanto: lo dice, no hay tabla y manda el total con imputaciones vacías', async () => {
      await crear(modo, { pendientes: () => of(sinDeuda) });
      await pulsar(accion('Cobrar', modo));

      expect(texto('app-dialogo-cobranza .sin-deudas')).toBe('Sin deudas: el cobro queda a cuenta (adelanto)');
      expect(el().querySelector('app-dialogo-cobranza table')).toBeNull();
      await escribirEn(total(), '250');
      expect(texto('app-dialogo-cobranza .a-cuenta')).toBe('A cuenta $ 250,00');
      await tecla(total(), 'Enter');

      expect(cobranza.cobrar).toHaveBeenCalledExactlyOnceWith({
        clave: claves[0],
        clienteId: 8,
        medio: 'EFECTIVO',
        total: 250,
        imputaciones: [],
      });
      expect(texto('.alerta.exito span')).toBe(
        'Cobro registrado: Recibo N.º 12 por $ 600,00.',
      );
    });

    it('lo que el cliente ya tiene a cuenta se dice arriba: «Ya tiene $ 5.000,00 a cuenta»', async () => {
      await crear(modo, { pendientes: () => of({ saldo: -4000, aCuenta: 5000, comprobantes: con3.comprobantes }) });
      await pulsar(accion('Cobrar', modo));

      expect(texto('app-dialogo-cobranza .ya-a-cuenta')).toBe('Ya tiene $ 5.000,00 a cuenta');
    });

    it('con el total vacío no manda nada y dice «Escribí el total a cobrar.»', async () => {
      await crear(modo);
      await pulsar(accion('Cobrar', modo));

      await tecla(total(), 'Enter');

      expect(cobranza.cobrar).not.toHaveBeenCalled();
      expect(texto('#error-total')).toBe('Escribí el total a cobrar.');
      expect(total().getAttribute('aria-invalid')).toBe('true');
      expect(cobroAbierto()).not.toBeNull();
    });

    it('con total 0 no manda nada y dice «El total tiene que ser mayor que cero.»', async () => {
      await crear(modo);
      await pulsar(accion('Cobrar', modo));

      await escribirEn(total(), '0');
      await tecla(total(), 'Enter');

      expect(cobranza.cobrar).not.toHaveBeenCalled();
      expect(texto('#error-total')).toBe('El total tiene que ser mayor que cero.');
    });

    it('un importe de fila mayor que lo pendiente y lo imputado mayor que el total no mandan nada y dicen por qué', async () => {
      await crear(modo);
      await pulsar(accion('Cobrar', modo));
      await escribirEn(total(), '600');

      await escribirEn(filasCobro()[0], '400');
      await tecla(total(), 'Enter');
      expect(cobranza.cobrar).not.toHaveBeenCalled();
      expect(texto('app-dialogo-cobranza small.campo-error')).toContain('300');

      await escribirEn(filasCobro()[0], '300');
      await escribirEn(filasCobro()[1], '400');
      expect(texto('#error-imputado')).toBe('Lo imputado supera el total.');
      await tecla(total(), 'Enter');
      expect(cobranza.cobrar).not.toHaveBeenCalled();
    });

    it('al registrarse: cierra el diálogo, avisa el recibo, abre el recibo y vuelve a pedir los pendientes', async () => {
      await crear(modo);

      await cobrarCon(modo, '600');

      expect(cobroAbierto()).toBeNull();
      expect(texto('.alerta.exito span')).toBe('Cobro registrado: Recibo N.º 12 por $ 600,00.');
      expect(el().querySelector('app-recibo-imprimible')).not.toBeNull();
      expect(cobranza.pendientes).toHaveBeenCalledTimes(2);
      expect(extractos.extracto).toHaveBeenCalledTimes(2);
    });

    it('un cobro con un resto «a cuenta» lo dice en el aviso', async () => {
      await crear(modo, { cobrar: () => of({ recibo: recibo({ total: 1300, aCuenta: 100 }), repetida: false }) });

      await cobrarCon(modo, '1300');

      expect(texto('.alerta.exito span')).toBe('Cobro registrado: Recibo N.º 12 por $ 1.300,00. Quedó a cuenta $ 100,00.');
    });

    it('un 200 (la misma clave) dice «Este cobro ya estaba registrado»', async () => {
      await crear(modo, { cobrar: () => of({ recibo: recibo(), repetida: true }) });

      await cobrarCon(modo, '600');

      expect(texto('.alerta.exito span')).toBe('Este cobro ya estaba registrado: Recibo N.º 12 por $ 600,00.');
    });

    it('cada apertura del diálogo nace con una clave nueva', async () => {
      await crear(modo);

      await pulsar(accion('Cobrar', modo));
      await pulsar(enDialogo('app-dialogo-cobranza', 'button.cancelar'));
      await pulsar(accion('Cobrar', modo));

      expect(claves).toEqual(['00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002']);
    });

    it('mientras el servidor responde, un segundo Enter y un clic en «Cobrar» no mandan otro cobro', async () => {
      const respuesta = new Subject<ReciboCobrado>();
      await crear(modo, { cobrar: () => respuesta });
      await cobrarCon(modo, '600');

      await tecla(total(), 'Enter');
      await pulsar(enDialogo('app-dialogo-cobranza', 'button.cobrar'));

      expect(cobranza.cobrar).toHaveBeenCalledTimes(1);
      expect(cobroAbierto()).not.toBeNull();
    });

    it('Esc y «Cancelar» cierran sin cobrar nada y sin tocar la cuenta', async () => {
      await crear(modo);
      await pulsar(accion('Cobrar', modo));
      await escribirEn(total(), '600');

      await tecla(total(), 'Escape');
      expect(cobroAbierto()).toBeNull();

      await pulsar(accion('Cobrar', modo));
      await pulsar(enDialogo('app-dialogo-cobranza', 'button.cancelar'));
      expect(cobroAbierto()).toBeNull();
      expect(cobranza.cobrar).not.toHaveBeenCalled();
      expect(cobranza.pendientes).toHaveBeenCalledTimes(1);
      expect(texto('.lineas .saldo')).toBe('Saldo $ 1.200,00');
    });

    it('F4 abre el cobro y con el cobro abierto otro F4 no abre uno nuevo', async () => {
      await crear(modo);

      await f4();
      await f4();

      expect(el().querySelectorAll('app-dialogo-cobranza')).toHaveLength(1);
      expect(claves).toHaveLength(1);
    });

    describe('rechazos: el diálogo queda abierto con su contenido y la cuenta intacta', () => {
      it('409 IMPUTACION_EXCEDE_DEUDA: avisa, vuelve a pedir las deudas, re-sugiere FIFO y la clave siguiente es nueva', async () => {
        const despues: PendientesCuenta = { saldo: 900, aCuenta: 0, comprobantes: [comprobante(32, 8, 500), comprobante(33, 9, 400)] };
        let llamada = 0;
        await crear(modo, {
          pendientes: () => of(++llamada === 1 ? con3 : despues),
          cobrar: () =>
            throwError(() =>
              rechazo(409, 'IMPUTACION_EXCEDE_DEUDA', 'La deuda de algún comprobante cambió.', [
                { comprobanteId: 31, referencia: 'Venta N.º 7', pendiente: 0 },
              ]),
            ),
        });

        await cobrarCon(modo, '600');

        expect(cobroAbierto()).not.toBeNull();
        expect(cobranza.pendientes).toHaveBeenCalledTimes(2);
        expect(texto('app-dialogo-cobranza .aviso')).toBe('Las deudas cambiaron: revisá la imputación y volvé a confirmar.');
        expect(valores(filasCobro())).toEqual(['500,00', '100,00']);
        expect(total().value).toBe('600');

        await tecla(total(), 'Enter');
        expect(cobranza.cobrar.mock.calls.map((c) => c[0].clave)).toEqual([claves[0], claves[1]]);
        expect(claves[1]).not.toBe(claves[0]);
      });

      it.each([
        ['CAJA_CERRADA', 'Abrí la caja para cobrar.'],
        ['CAJA_DE_OTRO_DIA', 'La caja del 18/10 sigue abierta. Cerrala antes de cobrar.'],
      ])('422 %s: muestra el mensaje y el reintento usa la MISMA clave', async (motivo, message) => {
        await crear(modo, { cobrar: () => throwError(() => rechazo(422, motivo, message)) });

        await cobrarCon(modo, '600');

        expect(cobroAbierto()).not.toBeNull();
        expect(texto('app-dialogo-cobranza .error-servidor')).toBe(message);
        expect(total().value).toBe('600');
        expect(enDialogo('app-dialogo-cobranza', 'button.cobrar').disabled).toBe(false);
        expect(texto('.lineas .saldo')).toBe('Saldo $ 1.200,00');
        expect(cobranza.pendientes).toHaveBeenCalledTimes(1);

        await tecla(total(), 'Enter');
        expect(cobranza.cobrar.mock.calls.map((c) => c[0].clave)).toEqual([claves[0], claves[0]]);
      });

      it('422 CLAVE_CON_OTRO_CONTENIDO: muestra el mensaje y el siguiente intento lleva una clave nueva', async () => {
        await crear(modo, {
          cobrar: () => throwError(() => rechazo(422, 'CLAVE_CON_OTRO_CONTENIDO', 'Este cobro ya se registró con otros datos.')),
        });

        await cobrarCon(modo, '600');
        expect(texto('app-dialogo-cobranza .error-servidor')).toBe('Este cobro ya se registró con otros datos.');

        await tecla(total(), 'Enter');
        expect(cobranza.cobrar.mock.calls.map((c) => c[0].clave)).toEqual([claves[0], claves[1]]);
      });

      it('422 «El cliente está desactivado.» (sin motivo) y 404 muestran el mensaje del servidor', async () => {
        await crear(modo, {
          cobrar: () => throwError(() => errorHttp(422, { status: 422, message: 'El cliente está desactivado.' })),
        });

        await cobrarCon(modo, '600');

        expect(texto('app-dialogo-cobranza .error-servidor')).toBe('El cliente está desactivado.');
        expect(cobroAbierto()).not.toBeNull();
      });

      it('400: junta los errores por campo en un texto y el siguiente intento lleva una clave nueva', async () => {
        await crear(modo, {
          cobrar: () =>
            throwError(() =>
              errorHttp(400, { status: 400, message: 'Hay datos inválidos', errors: { total: 'El total es obligatorio.', medio: 'Medio inválido.' } }),
            ),
        });

        await cobrarCon(modo, '600');

        expect(texto('app-dialogo-cobranza .error-servidor')).toContain('El total es obligatorio.');
        expect(texto('app-dialogo-cobranza .error-servidor')).toContain('Medio inválido.');
        expect(cobroAbierto()).not.toBeNull();
        await tecla(total(), 'Enter');
        expect(cobranza.cobrar.mock.calls.map((c) => c[0].clave)).toEqual([claves[0], claves[1]]);
      });

      it('500 y sin conexión: «No se pudo registrar el cobro…», contenido congelado y el reintento usa la MISMA clave', async () => {
        let intento = 0;
        await crear(modo, {
          cobrar: () => (++intento === 1 ? throwError(() => errorHttp(500, null)) : of({ recibo: recibo(), repetida: true })),
        });

        await cobrarCon(modo, '600');

        expect(texto('app-dialogo-cobranza .error-servidor')).toBe(
          'No se pudo registrar el cobro: no hay conexión o el servidor no respondió. Reintentá: no se cobra dos veces.',
        );
        expect(texto('app-dialogo-cobranza .aviso-congelado')).toBe('No pudimos confirmar el cobro. Reintentá: se envía lo mismo.');
        expect(total().readOnly).toBe(true);
        expect(cobranza.pendientes).toHaveBeenCalledTimes(1);

        await escribirEn(total(), '999');
        await tecla(total(), 'Enter');

        expect(cobranza.cobrar.mock.calls.map((c) => c[0])).toEqual([
          { clave: claves[0], clienteId: 8, medio: 'EFECTIVO', total: 600, imputaciones: [{ comprobanteId: 31, importe: 300 }, { comprobanteId: 32, importe: 300 }] },
          { clave: claves[0], clienteId: 8, medio: 'EFECTIVO', total: 600, imputaciones: [{ comprobanteId: 31, importe: 300 }, { comprobanteId: 32, importe: 300 }] },
        ]);
        expect(claves).toHaveLength(1);
        expect(cobroAbierto()).toBeNull();
        expect(texto('.alerta.exito span')).toBe('Este cobro ya estaba registrado: Recibo N.º 12 por $ 600,00.');
      });

      it('«Cancelar y empezar de nuevo» suelta el envío sin confirmar: clave nueva y el contenido se vuelve a editar', async () => {
        await crear(modo, { cobrar: () => throwError(() => errorHttp(0, null)) });
        await cobrarCon(modo, '600');

        await pulsar(enDialogo('app-dialogo-cobranza', 'button.reiniciar'));
        expect(el().querySelector('app-dialogo-cobranza .aviso-congelado')).toBeNull();
        expect(total().readOnly).toBe(false);
        await tecla(total(), 'Enter');

        expect(cobranza.cobrar.mock.calls.map((c) => c[0].clave)).toEqual([claves[0], claves[1]]);
      });
    });
  });

  describe.each(MODOS)('Recargo y Descuento: los ajustes del saldo (%s)', (modo) => {
    it('Recargo: pide los datos, dice el efecto en el saldo y manda clave, tipo, importe y motivo, sin imputaciones', async () => {
      await crear(modo);

      await pulsar(accion('Recargo', modo));
      expect(texto('h2#dialogo-ajuste-titulo')).toBe('Recargo a Ana Gómez');
      expect(texto('app-dialogo-ajuste label.campo-importe')).toContain('Importe del recargo');
      expect(el().querySelector('app-dialogo-ajuste table')).toBeNull();
      await escribirEn(importeAj(), '500');
      await escribirEn(motivoAj(), '  Deuda vieja del cliente  ');
      expect(texto('app-dialogo-ajuste .contador')).toBe('27/200');
      await pulsar(enDialogo('app-dialogo-ajuste', 'button.continuar'));

      expect(texto('app-dialogo-ajuste .resumen')).toBe('Vas a cargar un Recargo de $ 500,00 a la cuenta de Ana Gómez.');
      expect(texto('app-dialogo-ajuste .motivo-resumen')).toBe('Motivo: Deuda vieja del cliente');
      expect(texto('app-dialogo-ajuste .efecto-saldo')).toBe('El saldo pasa de $ 1.200,00 a $ 1.700,00.');
      expect(texto('app-dialogo-ajuste .no-se-deshace')).toBe(
        'Esta acción no se puede deshacer: un ajuste mal cargado se corrige con el ajuste contrario.',
      );
      expect(cobranza.ajustar).not.toHaveBeenCalled();
      await pulsar(enDialogo('app-dialogo-ajuste', 'button.confirmar-ajuste'));

      expect(cobranza.ajustar).toHaveBeenCalledExactlyOnceWith(8, {
        clave: claves[0],
        tipo: 'RECARGO',
        importe: 500,
        motivo: 'Deuda vieja del cliente',
      });
      expect(ajusteAbierto()).toBeNull();
      expect(texto('.alerta.exito span')).toBe('Recargo N.º 3 registrado por $ 500,00. El saldo ahora es $ 1.700,00.');
      expect(cobranza.pendientes).toHaveBeenCalledTimes(2);
      expect(extractos.extracto).toHaveBeenCalledTimes(2);
    });

    it('Descuento: imputa FIFO a ventas y Recargos con comprobanteId o ajusteId y dice el efecto en el saldo', async () => {
      await crear(modo, {
        pendientes: () => of(conRecargo),
        ajustar: () => of({ ajuste: respuestaAjuste({ tipo: 'DESCUENTO', referencia: 'Descuento N.º 4', importe: 600, saldo: 400 }), repetida: false }),
      });

      await pulsar(accion('Descuento', modo));
      expect(texto('h2#dialogo-ajuste-titulo')).toBe('Descuento a Ana Gómez');
      await escribirEn(importeAj(), '600');
      await escribirEn(motivoAj(), 'Condonación acordada');
      expect(valores(filasAjuste())).toEqual(['300,00', '200,00', '100,00']);
      await pulsar(enDialogo('app-dialogo-ajuste', 'button.continuar'));

      expect(texto('app-dialogo-ajuste .efecto-saldo')).toBe('El saldo pasa de $ 1.000,00 a $ 400,00.');
      expect(Array.from(el().querySelectorAll('app-dialogo-ajuste .imputaciones-resumen li')).map((li) => limpio(li.textContent))).toEqual([
        'Venta N.º 7 $ 300,00',
        'Recargo N.º 3 $ 200,00',
        'Venta N.º 8 $ 100,00',
      ]);
      await pulsar(enDialogo('app-dialogo-ajuste', 'button.confirmar-ajuste'));

      expect(cobranza.ajustar).toHaveBeenCalledExactlyOnceWith(8, {
        clave: claves[0],
        tipo: 'DESCUENTO',
        importe: 600,
        motivo: 'Condonación acordada',
        imputaciones: [
          { comprobanteId: 31, importe: 300 },
          { ajusteId: 3, importe: 200 },
          { comprobanteId: 32, importe: 100 },
        ],
      });
      expect(texto('.alerta.exito span')).toBe('Descuento N.º 4 registrado por $ 600,00. El saldo ahora es $ 400,00.');
    });

    it('Descuento mayor que la deuda: lo que sobra queda «A cuenta» y el saldo pasa a «a favor»', async () => {
      await crear(modo);

      await pulsar(accion('Descuento', modo));
      await escribirEn(importeAj(), '1500');
      expect(texto('app-dialogo-ajuste .a-cuenta')).toBe('A cuenta $ 300,00');
      await escribirEn(motivoAj(), 'Condonación acordada');
      await pulsar(enDialogo('app-dialogo-ajuste', 'button.continuar'));

      expect(texto('app-dialogo-ajuste .efecto-saldo')).toBe('El saldo pasa de $ 1.200,00 a $ 300,00 a favor.');
    });

    it('Descuento sin deudas: lo dice y no hay tabla', async () => {
      await crear(modo, { pendientes: () => of(sinDeuda) });

      await pulsar(accion('Descuento', modo));

      expect(texto('app-dialogo-ajuste .sin-deudas')).toBe('Sin deudas: el descuento queda a cuenta (saldo a favor)');
      expect(el().querySelector('app-dialogo-ajuste table')).toBeNull();
    });

    it('«Imputar en orden» del Descuento vuelve a la sugerencia FIFO', async () => {
      await crear(modo);
      await pulsar(accion('Descuento', modo));
      await escribirEn(importeAj(), '600');
      await escribirEn(filasAjuste()[0], '10');
      expect(valores(filasAjuste())).toEqual(['10', '300,00', '']);

      await pulsar(enDialogo('app-dialogo-ajuste', 'button.imputar-en-orden'));

      expect(valores(filasAjuste())).toEqual(['300,00', '300,00', '']);
    });

    it('validación: sin importe ni motivo no pasa a la confirmación y dice qué falta; nada se manda', async () => {
      await crear(modo);
      await pulsar(accion('Recargo', modo));

      await pulsar(enDialogo('app-dialogo-ajuste', 'button.continuar'));

      expect(texto('#error-importe')).toBe('Escribí el importe del ajuste.');
      expect(texto('#error-motivo')).toBe('Escribí el motivo del ajuste (al menos 5 letras).');
      expect(el().querySelector('app-dialogo-ajuste .efecto-saldo')).toBeNull();
      expect(cobranza.ajustar).not.toHaveBeenCalled();

      await escribirEn(importeAj(), '0');
      expect(texto('#error-importe')).toBe('El importe tiene que ser mayor que cero.');
      await escribirEn(motivoAj(), 'abc');
      expect(texto('#error-motivo')).toBe('Escribí el motivo del ajuste (al menos 5 letras).');
    });

    it('«Volver» de la confirmación vuelve a los datos con lo escrito; Esc y «Cancelar» cierran sin mandar nada', async () => {
      await crear(modo);
      await pulsar(accion('Recargo', modo));
      await escribirEn(importeAj(), '500');
      await escribirEn(motivoAj(), 'Deuda vieja del cliente');
      await pulsar(enDialogo('app-dialogo-ajuste', 'button.continuar'));

      await pulsar(enDialogo('app-dialogo-ajuste', 'button.volver'));
      expect(importeAj().value).toBe('500');
      expect(motivoAj().value).toBe('Deuda vieja del cliente');

      await tecla(importeAj(), 'Escape');
      expect(ajusteAbierto()).toBeNull();
      await pulsar(accion('Descuento', modo));
      await pulsar(enDialogo('app-dialogo-ajuste', 'button.cancelar'));
      expect(ajusteAbierto()).toBeNull();
      expect(cobranza.ajustar).not.toHaveBeenCalled();
      expect(texto('.lineas .saldo')).toBe('Saldo $ 1.200,00');
    });

    it('mientras el servidor responde, un segundo clic en «Confirmar recargo» no manda otro ajuste', async () => {
      const respuesta = new Subject<AjusteCobrado>();
      await crear(modo, { ajustar: () => respuesta });
      await ajustarCon(modo, 'Recargo', '500');

      await pulsar(enDialogo('app-dialogo-ajuste', 'button.confirmar-ajuste'));

      expect(cobranza.ajustar).toHaveBeenCalledTimes(1);
      expect(ajusteAbierto()).not.toBeNull();
    });

    describe('rechazos: el diálogo queda abierto y la cuenta intacta', () => {
      it('409 IMPUTACION_EXCEDE_DEUDA: vuelve a los datos con las deudas actuales, avisa y la clave cambia', async () => {
        const despues: PendientesCuenta = { saldo: 800, aCuenta: 0, comprobantes: [comprobante(32, 8, 500), recargoPartida(3, 300)] };
        let llamada = 0;
        await crear(modo, {
          pendientes: () => of(++llamada === 1 ? conRecargo : despues),
          ajustar: () =>
            throwError(() =>
              rechazo(409, 'IMPUTACION_EXCEDE_DEUDA', 'La deuda de alguna partida cambió.', [
                { comprobanteId: 31, ajusteId: null, referencia: 'Venta N.º 7', pendiente: 0 },
              ]),
            ),
        });

        await ajustarCon(modo, 'Descuento', '600', 'Condonación acordada');

        expect(ajusteAbierto()).not.toBeNull();
        expect(texto('app-dialogo-ajuste .aviso')).toBe('Las deudas cambiaron: revisá la imputación y volvé a confirmar.');
        expect(el().querySelector('app-dialogo-ajuste .efecto-saldo')).toBeNull();
        expect(valores(filasAjuste())).toEqual(['500,00', '100,00']);
        expect(importeAj().value).toBe('600');

        await pulsar(enDialogo('app-dialogo-ajuste', 'button.continuar'));
        await pulsar(enDialogo('app-dialogo-ajuste', 'button.confirmar-ajuste'));
        expect(cobranza.ajustar.mock.calls.map((c) => c[1].clave)).toEqual([claves[0], claves[1]]);
      });

      it('409 de clave con otro contenido: muestra el mensaje y la clave siguiente es nueva', async () => {
        await crear(modo, {
          ajustar: () => throwError(() => rechazo(409, 'CLAVE_CON_OTRO_CONTENIDO', 'Esa clave ya se usó con otro contenido.')),
        });

        await ajustarCon(modo, 'Recargo', '500');
        expect(texto('app-dialogo-ajuste .error-servidor')).toBe('Esa clave ya se usó con otro contenido.');

        await pulsar(enDialogo('app-dialogo-ajuste', 'button.confirmar-ajuste'));
        expect(cobranza.ajustar.mock.calls.map((c) => c[1].clave)).toEqual([claves[0], claves[1]]);
      });

      it('422 «El cliente está desactivado.» y 403 muestran el mensaje del servidor', async () => {
        await crear(modo, {
          ajustar: () => throwError(() => errorHttp(422, { status: 422, message: 'El cliente está desactivado.' })),
        });

        await ajustarCon(modo, 'Recargo', '500');

        expect(ajusteAbierto()).not.toBeNull();
        expect(texto('app-dialogo-ajuste .error-servidor')).toBe('El cliente está desactivado.');
        expect(texto('.lineas .saldo')).toBe('Saldo $ 1.200,00');
        expect(cobranza.pendientes).toHaveBeenCalledTimes(1);
      });

      it('400: los errores de importe y motivo van junto a su campo y el resto en el mensaje; la clave siguiente es nueva', async () => {
        await crear(modo, {
          ajustar: () =>
            throwError(() =>
              errorHttp(400, {
                status: 400,
                message: 'Hay datos inválidos',
                errors: { importe: 'El importe admite hasta 2 decimales.', motivo: 'El motivo es obligatorio.', imputaciones: 'No corresponde.' },
              }),
            ),
        });

        await ajustarCon(modo, 'Recargo', '500');

        expect(el().querySelector('app-dialogo-ajuste .efecto-saldo')).toBeNull();
        expect(texto('#error-importe')).toBe('El importe admite hasta 2 decimales.');
        expect(texto('#error-motivo')).toBe('El motivo es obligatorio.');
        expect(texto('app-dialogo-ajuste .error-servidor')).toBe('No corresponde.');

        await pulsar(enDialogo('app-dialogo-ajuste', 'button.continuar'));
        await pulsar(enDialogo('app-dialogo-ajuste', 'button.confirmar-ajuste'));
        expect(cobranza.ajustar.mock.calls.map((c) => c[1].clave)).toEqual([claves[0], claves[1]]);
      });

      it('503: «El sistema está ocupado, reintentá.» y reintenta con la MISMA clave', async () => {
        await crear(modo, { ajustar: () => throwError(() => errorHttp(503, { status: 503, message: 'x' })) });

        await ajustarCon(modo, 'Recargo', '500');

        expect(texto('app-dialogo-ajuste .error-servidor')).toBe('El sistema está ocupado, reintentá.');
        await pulsar(enDialogo('app-dialogo-ajuste', 'button.confirmar-ajuste'));
        expect(cobranza.ajustar.mock.calls.map((c) => c[1].clave)).toEqual([claves[0], claves[0]]);
      });

      it('sin conexión: contenido congelado, el reintento manda lo mismo con la MISMA clave y «Cancelar y empezar de nuevo» da una clave nueva', async () => {
        let intento = 0;
        await crear(modo, {
          ajustar: () => (++intento <= 2 ? throwError(() => errorHttp(0, null)) : of({ ajuste: respuestaAjuste(), repetida: true })),
        });

        await ajustarCon(modo, 'Recargo', '500');
        expect(texto('app-dialogo-ajuste .error-servidor')).toBe(
          'No se pudo registrar el ajuste: no hay conexión o el servidor no respondió. Reintentá: no se carga dos veces.',
        );
        expect(texto('app-dialogo-ajuste .aviso-congelado')).toBe('No pudimos confirmar el ajuste. Reintentá: se envía lo mismo.');
        expect(enDialogo('app-dialogo-ajuste', 'button.volver').disabled).toBe(true);

        await pulsar(enDialogo('app-dialogo-ajuste', 'button.reiniciar'));
        await pulsar(enDialogo('app-dialogo-ajuste', 'button.confirmar-ajuste'));
        expect(cobranza.ajustar.mock.calls.map((c) => c[1].clave)).toEqual([claves[0], claves[1]]);

        await pulsar(enDialogo('app-dialogo-ajuste', 'button.confirmar-ajuste'));
        expect(cobranza.ajustar.mock.calls.map((c) => c[1].clave)).toEqual([claves[0], claves[1], claves[1]]);
        expect(texto('.alerta.exito span')).toBe('Este ajuste ya estaba registrado: Recargo N.º 3 por $ 500,00. El saldo ahora es $ 1.700,00.');
      });
    });
  });

  describe.each(MODOS)('Anular recibo (%s)', (modo) => {
    const abrir = async (opciones: Opciones = {}) => {
      await crear(modo, opciones);
      await pulsar(el().querySelector('app-movimientos-cuenta button.anular'));
    };

    it('«Anular» trae el recibo (GET) y abre el diálogo con lo que se anula; todavía no anula nada', async () => {
      await abrir();

      expect(cobranza.recibo).toHaveBeenCalledExactlyOnceWith(5);
      expect(cobranza.anular).not.toHaveBeenCalled();
      expect(texto('h2#dialogo-anulacion-titulo')).toBe('Anular recibo N.º 12');
      expect(texto('app-dialogo-anulacion .resumen')).toContain('Recibo N.º 12 de Ana Gómez');
      expect(texto('app-dialogo-anulacion .resumen')).toContain('cobrado en Efectivo por $ 600,00.');
      expect(Array.from(el().querySelectorAll('app-dialogo-anulacion .liberados li')).map((li) => limpio(li.textContent))).toEqual([
        'Venta N.º 7 $ 300,00',
        'Venta N.º 8 $ 300,00',
      ]);
      expect(texto('app-dialogo-anulacion .liberados p')).toBe('Vuelven a quedar pendientes:');
      expect(texto('app-dialogo-anulacion .aviso-caja')).toBe('Esto resta $ 600,00 del efectivo esperado de la caja de hoy.');
      expect(texto('app-dialogo-anulacion .no-se-deshace')).toBe('Esta acción no se puede deshacer.');
      expect(texto('app-dialogo-anulacion label.campo-motivo')).toContain('Motivo de la anulación');
    });

    it('un cobro por transferencia dice «No cambia el efectivo de la caja.» y uno con resto a cuenta dice cuánto deja de estar a cuenta', async () => {
      await crear(modo);
      cobranza.recibo.mockReturnValue(of(recibo({ medio: 'TRANSFERENCIA', aCuenta: 100 })));
      await pulsar(el().querySelector('app-movimientos-cuenta button.anular'));

      expect(texto('app-dialogo-anulacion .aviso-caja')).toBe('No cambia el efectivo de la caja.');
      expect(texto('app-dialogo-anulacion .deja-a-cuenta')).toBe('Deja de estar a cuenta $ 100,00');
    });

    it('Enter con un motivo válido anula el recibo 5 con el motivo recortado y avisa el resultado', async () => {
      await crear(modo);

      await anularCon('  Cliente equivocado  ');

      expect(cobranza.anular).toHaveBeenCalledExactlyOnceWith(5, 'Cliente equivocado');
      expect(anulacionAbierta()).toBeNull();
      expect(texto('.alerta.exito span')).toBe('Recibo N.º 12 anulado. Los comprobantes volvieron a quedar pendientes.');
      expect(cobranza.pendientes).toHaveBeenCalledTimes(2);
      expect(extractos.extracto).toHaveBeenCalledTimes(2);
    });

    it('«Anular recibo» (el botón) manda lo mismo que Enter', async () => {
      await abrir();
      await escribirEn(motivoAn(), 'Importe mal cargado');

      await pulsar(enDialogo('app-dialogo-anulacion', 'button.anular-recibo'));

      expect(cobranza.anular).toHaveBeenCalledExactlyOnceWith(5, 'Importe mal cargado');
    });

    it('con un motivo corto no manda nada y dice «Escribí el motivo de la anulación (al menos 5 letras).»', async () => {
      await abrir();
      await escribirEn(motivoAn(), 'abc');

      await tecla(motivoAn(), 'Enter');

      expect(cobranza.anular).not.toHaveBeenCalled();
      expect(anulacionAbierta()).not.toBeNull();
      expect(texto('#error-motivo')).toBe('Escribí el motivo de la anulación (al menos 5 letras).');
      expect(motivoAn().getAttribute('aria-invalid')).toBe('true');
    });

    it('mientras el servidor responde se apagan los dos botones y un segundo Enter no manda otra anulación', async () => {
      const respuesta = new Subject<Recibo>();
      await abrir({ anular: () => respuesta });
      await escribirEn(motivoAn(), 'Cliente equivocado');

      await tecla(motivoAn(), 'Enter');
      await tecla(motivoAn(), 'Enter');

      expect(cobranza.anular).toHaveBeenCalledTimes(1);
      expect(enDialogo('app-dialogo-anulacion', 'button.anular-recibo').disabled).toBe(true);
      expect(enDialogo('app-dialogo-anulacion', 'button.cancelar').disabled).toBe(true);
    });

    it('409 RECIBO_YA_ANULADO: cierra, avisa quién y cuándo y vuelve a pedir la cuenta (hecho consumado)', async () => {
      await crear(modo, {
        anular: () =>
          throwError(() =>
            errorHttp(409, {
              status: 409,
              message: 'El recibo ya fue anulado.',
              timestamp: '2026-10-20T12:00:00Z',
              motivo: 'RECIBO_YA_ANULADO',
              anuladoPor: 'Dueño Tornillo',
              fechaAnulacion: '2026-10-20T09:15:40',
            }),
          ),
      });

      await anularCon('Cliente equivocado');

      expect(anulacionAbierta()).toBeNull();
      expect(texto('.alerta.advertencia')).toContain('Este recibo ya fue anulado por Dueño Tornillo el 20/10/2026 09:15');
      expect(texto('.alerta.exito')).toBeNull();
      expect(cobranza.pendientes).toHaveBeenCalledTimes(2);
    });

    it.each([
      ['422 caja cerrada', () => rechazo(422, 'CAJA_CERRADA', 'Abrí la caja para anular un cobro en efectivo.'), 'Abrí la caja para anular un cobro en efectivo.'],
      ['422 cliente desactivado', () => errorHttp(422, { status: 422, message: 'El cliente está desactivado.' }), 'El cliente está desactivado.'],
      ['400 motivo', () => errorHttp(400, { status: 400, message: 'Hay datos inválidos', errors: { motivo: 'El motivo es obligatorio.' } }), 'El motivo es obligatorio.'],
      ['503', () => errorHttp(503, { status: 503, message: 'x' }), 'El sistema está ocupado, reintentá.'],
      ['500', () => errorHttp(500, null), 'No se pudo anular: no hay conexión o el servidor no respondió. Reintentá: si ya se anuló, vas a ver el aviso.'],
      ['sin conexión', () => errorHttp(0, null), 'No se pudo anular: no hay conexión o el servidor no respondió. Reintentá: si ya se anuló, vas a ver el aviso.'],
    ])('%s: el diálogo sigue abierto con el motivo escrito, dice el error y la cuenta queda intacta', async (_nombre, error, mensaje) => {
      await crear(modo, { anular: () => throwError(error) });

      await anularCon('Cliente equivocado');

      expect(anulacionAbierta()).not.toBeNull();
      expect(texto('app-dialogo-anulacion .error-servidor')).toBe(mensaje);
      expect(motivoAn().value).toBe('Cliente equivocado');
      expect(enDialogo('app-dialogo-anulacion', 'button.anular-recibo').disabled).toBe(false);
      expect(texto('.lineas .saldo')).toBe('Saldo $ 1.200,00');
      expect(cobranza.pendientes).toHaveBeenCalledTimes(1);
      expect(texto('.alerta.exito')).toBeNull();

      await tecla(motivoAn(), 'Enter');
      expect(cobranza.anular).toHaveBeenCalledTimes(2);
      expect(cobranza.anular).toHaveBeenLastCalledWith(5, 'Cliente equivocado');
    });

    it('«Cancelar» y Esc cierran sin anular nada', async () => {
      await abrir();

      await pulsar(enDialogo('app-dialogo-anulacion', 'button.cancelar'));
      expect(anulacionAbierta()).toBeNull();

      await pulsar(el().querySelector('app-movimientos-cuenta button.anular'));
      await tecla(motivoAn(), 'Escape');
      expect(anulacionAbierta()).toBeNull();
      expect(cobranza.anular).not.toHaveBeenCalled();
    });

    it('si no se puede traer el recibo no abre nada y dice «No se pudo traer el recibo. Probá de nuevo.»', async () => {
      await crear(modo);
      cobranza.recibo.mockReturnValue(throwError(() => errorHttp(500, null)));

      await pulsar(el().querySelector('app-movimientos-cuenta button.anular'));

      expect(anulacionAbierta()).toBeNull();
      expect(texto('p.error[role="alert"]')).toBe('No se pudo traer el recibo. Probá de nuevo.');
    });

    it('con el cliente desactivado, «Anular» sigue apagado y explica por qué: «Reactivá el cliente para anular»', async () => {
      await crear(modo, { cliente: { ...ana, activo: false } });

      const anular = el().querySelector<HTMLButtonElement>('app-movimientos-cuenta button.anular')!;
      expect(anular.getAttribute('aria-disabled')).toBe('true');
      expect(texto('app-movimientos-cuenta .aviso-inactivo')).toBe('Reactivá el cliente para anular');
      await pulsar(anular);
      expect(cobranza.recibo).not.toHaveBeenCalled();
      expect(anulacionAbierta()).toBeNull();
    });

    it('«Ver recibo» de un movimiento trae el recibo y lo muestra para reimprimir, sin anular nada', async () => {
      await crear(modo);

      await pulsar(el().querySelector('app-movimientos-cuenta button.ver-recibo, app-movimientos-cuenta button.reimprimir'));

      expect(cobranza.recibo).toHaveBeenCalledExactlyOnceWith(5);
      expect(el().querySelector('app-recibo-imprimible')).not.toBeNull();
      expect(cobranza.anular).not.toHaveBeenCalled();
    });
  });

  describe.each(MODOS)('el Vendedor no cobra ni ajusta ni anula (%s)', (modo) => {
    it('ve el aviso de siempre, no se hace ningún request y no hay Cobrar, Recargo, Descuento, Anular ni F4', async () => {
      await crear(modo, { dueno: false });
      await f4();

      expect(texto('.alerta')).toBe('Solo el Dueño ve y cobra la cuenta corriente.');
      expect(clientes.obtener).not.toHaveBeenCalled();
      expect(cobranza.pendientes).not.toHaveBeenCalled();
      expect(cobranza.cobrar).not.toHaveBeenCalled();
      expect(cobranza.ajustar).not.toHaveBeenCalled();
      expect(cobranza.anular).not.toHaveBeenCalled();
      expect(extractos.extracto).not.toHaveBeenCalled();
      expect(el().querySelector('app-dialogo-cobranza, app-dialogo-ajuste, app-dialogo-anulacion')).toBeNull();
      expect(
        Array.from(el().querySelectorAll('button')).filter((b) => ['Cobrar', 'Recargo', 'Descuento', 'Anular'].includes(limpio(b.textContent))),
      ).toHaveLength(0);
    });

    it('mientras la cuenta no cargó, Cobrar, Recargo y Descuento están apagados', async () => {
      await crear(modo, { pendientes: () => new Subject<PendientesCuenta>() });

      const botones = Array.from(el().querySelectorAll<HTMLButtonElement>('.acciones-cuenta button, app-barra-acciones button'));
      expect(botones.every((b) => b.disabled)).toBe(true);
      expect(texto('.estado-vacio')).toBe('Cargando…');
    });
  });
});
