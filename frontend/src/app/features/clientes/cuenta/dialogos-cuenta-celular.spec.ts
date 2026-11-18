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
 * movil-sectores T12b (S2, S5, S7): Cobrar, Recargo, Descuento y Anular recibo, abiertos desde la cuenta corriente, en el
 * celular son una pantalla completa con la barra «Volver» arriba y el pie fijo con la acción principal. Estos tests son de
 * ESTRUCTURA y de foco (en el celular y en la tablet, que no cambia); los pedidos, los textos, las claves y los roles están
 * fijados con literales en `cuenta-plata-celular.spec.ts`, que pasa igual contra el código anterior. Vitest no lee
 * `styles.css`: el layout se mide en el navegador a 390 y 360 px.
 */
describe('Cobranza, ajustes y anular recibo en el celular: pantalla completa (T12b)', () => {
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

  const hijos = (dialogo: Element) => Array.from(dialogo.children) as HTMLElement[];
  const botones = (selector: string) => Array.from(el().querySelectorAll<HTMLButtonElement>(`${selector} button`)).map((b) => limpio(b.textContent));
  const enfocadoEsCampo = () => ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName ?? '');

  /** La tarjeta del diálogo: lo que en el celular es la pantalla completa. */
  const tarjeta = (dialogo: string) => el().querySelector<HTMLElement>(`${dialogo} .dialogo`)!;
  const volver = (dialogo: string) => el().querySelector<HTMLButtonElement>(`${dialogo} .ficha-volver`);

  describe('Cobrar en el celular', () => {
    beforeEach(async () => {
      await crear('celular');
      accion('Cobrar', 'celular').focus();
      await pulsar(accion('Cobrar', 'celular'));
    });

    it('es una pantalla completa: la barra «Volver» con el título arriba y el pie fijo con «Cancelar» y «Cobrar» al final', () => {
      const dialogo = tarjeta('app-dialogo-cobranza');
      expect(dialogo.classList).toContain('dialogo-cuenta-celular');
      const partes = hijos(dialogo);
      expect(partes[0].classList).toContain('ficha-barra');
      expect(partes[partes.length - 1].classList).toContain('ficha-pie');
      expect(partes[partes.length - 1].classList).toContain('dialogo-acciones');
      expect(Array.from(partes[partes.length - 1].children).map((b) => limpio(b.textContent))).toEqual(['Cancelar', 'Cobrar']);
    });

    it('la barra tiene la flecha «Volver» y el título «Cobrar a Ana Gómez», que sigue nombrando al diálogo', () => {
      const barra = el().querySelector('app-dialogo-cobranza .ficha-barra')!;
      expect(barra.querySelector('button.ficha-volver')!.getAttribute('aria-label')).toBe('Volver');
      expect(barra.querySelector('button.ficha-volver')!.getAttribute('type')).toBe('button');
      expect(limpio(barra.querySelector('h2')!.textContent)).toBe('Cobrar a Ana Gómez');
      expect(el().querySelectorAll('app-dialogo-cobranza h2')).toHaveLength(1);
      expect(tarjeta('app-dialogo-cobranza').getAttribute('aria-labelledby')).toBe('dialogo-cobranza-titulo');
      expect(barra.querySelector('h2')!.id).toBe('dialogo-cobranza-titulo');
    });

    it('la imputación son filas (la misma tabla) sin el scroll de costado, con un campo por fila', () => {
      const marco = el().querySelector('app-dialogo-cobranza .tabla-scroll')!;
      expect(marco.classList).toContain('imputacion-celular');
      expect(marco.querySelectorAll('table.imputacion tbody tr')).toHaveLength(3);
      expect(valores(filasCobro()).length).toBe(3);
      expect(filasCobro()[0].getAttribute('aria-label')).toBe('Importe a imputar a Venta N.º 7');
    });

    it('al abrir el foco va al total, como hoy', () => {
      expect(document.activeElement).toBe(total());
    });

    it('«Volver» cierra sin cobrar nada y el foco vuelve a «Cobrar», no a un campo', async () => {
      await escribirEn(total(), '600');

      await pulsar(volver('app-dialogo-cobranza'));

      expect(cobroAbierto()).toBeNull();
      expect(cobranza.cobrar).not.toHaveBeenCalled();
      expect(cobranza.pendientes).toHaveBeenCalledTimes(1);
      expect(document.activeElement).toBe(accion('Cobrar', 'celular'));
      expect(enfocadoEsCampo()).toBe(false);
    });

    it('Esc cierra igual y el foco tampoco va a un campo', async () => {
      await tecla(total(), 'Escape');

      expect(cobroAbierto()).toBeNull();
      expect(enfocadoEsCampo()).toBe(false);
    });

    it('con un envío sin confirmar, el pie conserva «Cancelar y empezar de nuevo» antes de «Cancelar» y «Cobrar», y «Volver» cierra', async () => {
      TestBed.resetTestingModule();
      await crear('celular', { cobrar: () => throwError(() => errorHttp(0, null)) });
      await cobrarCon('celular', '600');

      expect(botones('app-dialogo-cobranza .ficha-pie')).toEqual(['Cancelar y empezar de nuevo', 'Cancelar', 'Cobrar']);
      await pulsar(volver('app-dialogo-cobranza'));
      expect(cobroAbierto()).toBeNull();
    });
  });

  describe('Cobrar sin deudas en el celular', () => {
    it('el adelanto no tiene el marco de la imputación pero sí la barra y el pie', async () => {
      await crear('celular', { pendientes: () => of(sinDeuda) });
      await pulsar(accion('Cobrar', 'celular'));

      expect(el().querySelector('app-dialogo-cobranza .imputacion-celular')).toBeNull();
      expect(el().querySelector('app-dialogo-cobranza .ficha-barra')).not.toBeNull();
      expect(el().querySelector('app-dialogo-cobranza .dialogo-acciones.ficha-pie')).not.toBeNull();
    });
  });

  describe.each(['Recargo', 'Descuento'] as const)('%s en el celular', (tipo) => {
    beforeEach(async () => {
      await crear('celular');
      accion(tipo, 'celular').focus();
      await pulsar(accion(tipo, 'celular'));
    });

    it('paso 1: la barra «Volver» con el título y el pie fijo con «Cancelar» y «Continuar»', () => {
      const dialogo = tarjeta('app-dialogo-ajuste');
      expect(dialogo.classList).toContain('dialogo-cuenta-celular');
      const partes = hijos(dialogo);
      expect(partes[0].classList).toContain('ficha-barra');
      expect(limpio(partes[0].querySelector('h2')!.textContent)).toBe(`${tipo} a Ana Gómez`);
      expect(partes[0].querySelector('h2')!.id).toBe('dialogo-ajuste-titulo');
      expect(dialogo.getAttribute('aria-labelledby')).toBe('dialogo-ajuste-titulo');
      expect(el().querySelectorAll('app-dialogo-ajuste h2')).toHaveLength(1);
      expect(partes[partes.length - 1].classList).toContain('ficha-pie');
      expect(Array.from(partes[partes.length - 1].children).map((b) => limpio(b.textContent))).toEqual(['Cancelar', 'Continuar']);
    });

    it('la flecha se llama «Volver a la cuenta» para no confundirse con el «Volver» de la confirmación', () => {
      expect(volver('app-dialogo-ajuste')!.getAttribute('aria-label')).toBe('Volver a la cuenta');
    });

    it('la imputación del Descuento son filas sin scroll de costado; el Recargo no tiene tabla', () => {
      const marco = el().querySelector('app-dialogo-ajuste .tabla-scroll');
      if (tipo === 'Descuento') {
        expect(marco!.classList).toContain('imputacion-celular');
        expect(marco!.querySelectorAll('tbody tr')).toHaveLength(3);
      } else {
        expect(marco).toBeNull();
      }
    });

    it('al abrir el foco va al importe, como hoy', () => {
      expect(document.activeElement).toBe(importeAj());
    });

    it('paso 2: la misma barra y el pie con «Volver» (al paso 1) y «Confirmar» que sigue fijo', async () => {
      await escribirEn(importeAj(), '500');
      await escribirEn(motivoAj(), 'Deuda vieja del cliente');
      await pulsar(enDialogo('app-dialogo-ajuste', 'button.continuar'));

      const partes = hijos(tarjeta('app-dialogo-ajuste'));
      expect(partes[0].classList).toContain('ficha-barra');
      expect(limpio(partes[0].querySelector('h2')!.textContent)).toBe(`${tipo} a Ana Gómez`);
      const pie = partes[partes.length - 1];
      expect(pie.classList).toContain('ficha-pie');
      expect(Array.from(pie.querySelectorAll('button')).map((b) => limpio(b.textContent))).toEqual([
        'Volver',
        'Cancelar',
        `Confirmar ${tipo.toLowerCase()}`,
      ]);
      expect(el().querySelectorAll('app-dialogo-ajuste .ficha-barra')).toHaveLength(1);
    });

    it('la flecha cierra el ajuste sin mandar nada, también desde la confirmación, y el foco vuelve al botón, no a un campo', async () => {
      await escribirEn(importeAj(), '500');
      await escribirEn(motivoAj(), 'Deuda vieja del cliente');
      await pulsar(enDialogo('app-dialogo-ajuste', 'button.continuar'));

      await pulsar(volver('app-dialogo-ajuste'));

      expect(ajusteAbierto()).toBeNull();
      expect(cobranza.ajustar).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(accion(tipo, 'celular'));
      expect(enfocadoEsCampo()).toBe(false);
    });

    it('con un envío sin confirmar la flecha también cierra y el pie conserva «Cancelar y empezar de nuevo»', async () => {
      TestBed.resetTestingModule();
      await crear('celular', { ajustar: () => throwError(() => errorHttp(0, null)) });
      await ajustarCon('celular', tipo, '500');

      expect(botones('app-dialogo-ajuste .ficha-pie')).toEqual([
        'Cancelar y empezar de nuevo',
        'Volver',
        'Cancelar',
        `Confirmar ${tipo.toLowerCase()}`,
      ]);
      await pulsar(volver('app-dialogo-ajuste'));
      expect(ajusteAbierto()).toBeNull();
    });
  });

  describe('Anular recibo en el celular', () => {
    beforeEach(async () => {
      await crear('celular');
      el().querySelector<HTMLElement>('app-movimientos-cuenta button.anular')!.focus();
      await pulsar(el().querySelector('app-movimientos-cuenta button.anular'));
    });

    it('es una pantalla completa: la barra «Volver» con «Anular recibo N.º 12» y el pie fijo con «Cancelar» y «Anular recibo»', () => {
      const dialogo = tarjeta('app-dialogo-anulacion');
      expect(dialogo.classList).toContain('dialogo-cuenta-celular');
      const partes = hijos(dialogo);
      expect(partes[0].classList).toContain('ficha-barra');
      expect(partes[0].querySelector('button.ficha-volver')!.getAttribute('aria-label')).toBe('Volver');
      expect(limpio(partes[0].querySelector('h2')!.textContent)).toBe('Anular recibo N.º 12');
      expect(partes[0].querySelector('h2')!.id).toBe('dialogo-anulacion-titulo');
      expect(dialogo.getAttribute('aria-labelledby')).toBe('dialogo-anulacion-titulo');
      expect(el().querySelectorAll('app-dialogo-anulacion h2')).toHaveLength(1);
      const pie = partes[partes.length - 1];
      expect(pie.classList).toContain('ficha-pie');
      expect(Array.from(pie.children).map((b) => limpio(b.textContent))).toEqual(['Cancelar', 'Anular recibo']);
    });

    it('al abrir el foco va al motivo, como hoy', () => {
      expect(document.activeElement).toBe(motivoAn());
    });

    it('«Volver» cierra sin anular nada y el foco vuelve a «Anular» (el botón de la fila), no a un campo', async () => {
      await escribirEn(motivoAn(), 'Cliente equivocado');

      await pulsar(volver('app-dialogo-anulacion'));

      expect(anulacionAbierta()).toBeNull();
      expect(cobranza.anular).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(el().querySelector('app-movimientos-cuenta button.anular'));
      expect(enfocadoEsCampo()).toBe(false);
    });

    it('mientras la anulación está en vuelo la flecha se apaga como «Cancelar» y no cierra', async () => {
      const respuesta = new Subject<Recibo>();
      cobranza.anular.mockReturnValue(respuesta);
      await escribirEn(motivoAn(), 'Cliente equivocado');
      await tecla(motivoAn(), 'Enter');

      expect(volver('app-dialogo-anulacion')!.disabled).toBe(true);
      await pulsar(volver('app-dialogo-anulacion'));

      expect(anulacionAbierta()).not.toBeNull();
      expect(cobranza.anular).toHaveBeenCalledTimes(1);
    });
  });

  describe('«Ver recibo» y el recibo recién cobrado en el celular', () => {
    it('el recibo es el imprimible de siempre, sin barra «Volver» de la cuenta', async () => {
      await crear('celular');
      await cobrarCon('celular', '600');

      expect(el().querySelector('app-recibo-imprimible')).not.toBeNull();
      expect(el().querySelector('app-recibo-imprimible .ficha-barra, app-recibo-imprimible .dialogo-cuenta-celular')).toBeNull();
    });
  });

  describe.each(['app-dialogo-cobranza', 'app-dialogo-ajuste', 'app-dialogo-anulacion'])('tablet y escritorio: %s no cambia', (dialogo) => {
    beforeEach(async () => {
      await crear('tableta');
      if (dialogo === 'app-dialogo-cobranza') await pulsar(accion('Cobrar', 'tableta'));
      else if (dialogo === 'app-dialogo-ajuste') await pulsar(accion('Descuento', 'tableta'));
      else await pulsar(el().querySelector('app-movimientos-cuenta button.anular'));
    });

    it('no tiene la barra «Volver», ni el pie fijo, ni las clases del celular: el título es el primer hijo y las acciones, un pie común', () => {
      const raiz = tarjeta(dialogo);
      expect(raiz.classList).not.toContain('dialogo-cuenta-celular');
      expect(raiz.querySelector('.ficha-barra, .ficha-volver, .ficha-pie, .imputacion-celular')).toBeNull();
      expect(hijos(raiz)[0].tagName).toBe('H2');
      expect(hijos(raiz)[hijos(raiz).length - 1].className).toBe('dialogo-acciones');
    });
  });
});
