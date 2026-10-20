import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../../core/auth/auth.service';
import { Cliente } from '../cliente.models';
import { ClientesService } from '../clientes.service';
import { CobranzaRequest, PendientesCuenta, Recibo, ReciboCobrado } from './cobranza.models';
import { CobranzaService, GENERAR_CLAVE_COBRO } from './cobranza.service';
import { CuentaCliente } from './cuenta-cliente';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';

registerLocaleData(localeEsAr);

describe('CuentaCliente (CC-028, CC-033, CC-034, CC-038, CC-043)', () => {
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

  /** Pendientes A 300 (el más viejo), B 500 y C 400: saldo 1.200. */
  const con3: PendientesCuenta = {
    saldo: 1200,
    aCuenta: 0,
    comprobantes: [comprobante(31, 7, 300), comprobante(32, 8, 500), comprobante(33, 9, 400)],
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
    ...cambios,
  });

  const errorHttp = (status: number, cuerpo: unknown) => new HttpErrorResponse({ status, error: cuerpo });
  const rechazo = (status: number, motivo: string, message: string, comprobantes?: unknown) =>
    errorHttp(status, { status, message, timestamp: '2026-10-19T13:30:00Z', motivo, comprobantes });

  let fixture: ComponentFixture<CuentaCliente>;
  let clientes: { obtener: ReturnType<typeof vi.fn> };
  let cobranza: {
    pendientes: ReturnType<typeof vi.fn>;
    cobrar: ReturnType<typeof vi.fn>;
    recibo: ReturnType<typeof vi.fn>;
  };
  let claves: string[];

  interface Opciones {
    dueno?: boolean;
    id?: string;
    pendientes?: () => Observable<PendientesCuenta>;
    cobrar?: (r: CobranzaRequest) => Observable<ReciboCobrado>;
  }

  async function crear({ dueno = true, id = '8', pendientes, cobrar }: Opciones = {}) {
    clientes = { obtener: vi.fn(() => of(ana)) };
    cobranza = {
      pendientes: vi.fn(pendientes ?? (() => of(con3))),
      cobrar: vi.fn(cobrar ?? (() => of({ recibo: recibo(), repetida: false }))),
      recibo: vi.fn(() => of(recibo())),
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
        { provide: AuthService, useValue: { esDueno: signal(dueno), sesion: signal({ ferreteriaNombre: 'Tornillo SA' }) } },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id }) } } },
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

  const el = () => fixture.nativeElement as HTMLElement;
  const limpio = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || null;
  const boton = (nombre: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => limpio(b.textContent) === nombre);
  const dialogo = () => el().querySelector('app-dialogo-cobranza');
  const total = () => el().querySelector<HTMLInputElement>('#total-cobranza')!;

  async function f4() {
    const evento = new KeyboardEvent('keydown', { key: 'F4', cancelable: true, bubbles: true });
    document.dispatchEvent(evento);
    await refrescar();
    return evento;
  }

  async function escribirTotal(valor: string) {
    total().value = valor;
    total().dispatchEvent(new Event('input', { bubbles: true }));
    await refrescar();
  }

  async function confirmar() {
    total().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true, bubbles: true }));
    await refrescar();
  }

  /** Abre el diálogo, tipea el total y confirma con Enter. */
  async function cobrar(importe: string) {
    await f4();
    await escribirTotal(importe);
    await confirmar();
  }

  describe('Vendedor', () => {
    it('ve el aviso «Solo el Dueño ve y cobra la cuenta corriente» y no se hace NINGÚN request', async () => {
      await crear({ dueno: false });

      expect(texto('[role="alert"], .alerta')).toBe('Solo el Dueño ve y cobra la cuenta corriente.');
      expect(clientes.obtener).not.toHaveBeenCalled();
      expect(cobranza.pendientes).not.toHaveBeenCalled();
      expect(cobranza.cobrar).not.toHaveBeenCalled();
      expect(cobranza.recibo).not.toHaveBeenCalled();
    });

    it('no tiene «Cobrar» y F4 no hace nada', async () => {
      await crear({ dueno: false });

      expect(boton('Cobrar (F4)')).toBeUndefined();
      await f4();
      expect(dialogo()).toBeNull();
      expect(cobranza.pendientes).not.toHaveBeenCalled();
    });
  });

  describe('Dueño: la cuenta', () => {
    it('al abrir pide el cliente y sus pendientes con el id de la ruta', async () => {
      await crear();

      expect(clientes.obtener).toHaveBeenCalledWith(8);
      expect(cobranza.pendientes).toHaveBeenCalledWith(8);
    });

    it('muestra el cliente, el saldo y los pendientes en orden FIFO con su referencia y lo que falta cobrar', async () => {
      await crear();

      expect(texto('h2')).toContain('Ana Gómez');
      expect(texto('.saldo')).toContain('$ 1.200,00');
      const renglones = Array.from(el().querySelectorAll('table.pendientes tbody tr')).map((tr) => limpio(tr.textContent));
      expect(renglones).toHaveLength(3);
      expect(renglones[0]).toContain('Venta N.º 7');
      expect(renglones[0]).toContain('$ 300,00');
      expect(renglones[1]).toContain('Venta N.º 8');
      expect(renglones[2]).toContain('Venta N.º 9');
    });

    it('un saldo negativo se dice «a favor» y se muestra lo que tiene a cuenta', async () => {
      await crear({ pendientes: () => of({ saldo: -500, aCuenta: 500, comprobantes: [] }) });

      expect(texto('.saldo')).toContain('$ 500,00 a favor');
      expect(texto('.a-cuenta-actual')).toContain('$ 500,00');
    });

    it('sin deuda dice que no tiene comprobantes pendientes y no muestra la tabla', async () => {
      await crear({ pendientes: () => of(sinDeuda) });

      expect(el().querySelector('table.pendientes')).toBeNull();
      expect(texto('.sin-pendientes')).toBe('No tiene comprobantes con deuda.');
      expect(el().querySelector('.a-cuenta-actual')).toBeNull();
    });

    it('mientras lee dice «Cargando…» y no ofrece cobrar', async () => {
      const respuesta = new Subject<PendientesCuenta>();
      await crear({ pendientes: () => respuesta });

      expect(texto('.estado-vacio')).toBe('Cargando…');
      expect(boton('Cobrar (F4)')!.disabled).toBe(true);
      await f4();
      expect(dialogo()).toBeNull();
    });

    it('si no se puede leer muestra el error con «Reintentar», que vuelve a pedir todo', async () => {
      let intento = 0;
      await crear({ pendientes: () => (++intento === 1 ? throwError(() => errorHttp(500, null)) : of(con3)) });

      expect(texto('[role="alert"]')).toContain('No se pudo cargar la cuenta del cliente.');
      expect(boton('Cobrar (F4)')!.disabled).toBe(true);

      boton('Reintentar')!.click();
      await refrescar();

      expect(el().querySelector('[role="alert"]')).toBeNull();
      expect(el().querySelectorAll('table.pendientes tbody tr')).toHaveLength(3);
      expect(clientes.obtener).toHaveBeenCalledTimes(2);
    });

    it('un id inválido en la ruta es un error sin pedir nada', async () => {
      await crear({ id: 'abc' });

      expect(texto('[role="alert"]')).toContain('El cliente no existe.');
      expect(clientes.obtener).not.toHaveBeenCalled();
      expect(cobranza.pendientes).not.toHaveBeenCalled();
    });

    it('tiene el enlace para volver a Clientes', async () => {
      await crear();

      expect(el().querySelector('a[href="/clientes"]')).not.toBeNull();
    });
  });

  describe('cobrar (F4)', () => {
    it('F4 abre el diálogo y el botón «Cobrar (F4)» hace lo mismo', async () => {
      await crear();

      const evento = await f4();
      expect(dialogo()).not.toBeNull();
      expect(evento.defaultPrevented).toBe(true);

      boton('Cancelar')!.click();
      await refrescar();
      expect(dialogo()).toBeNull();

      boton('Cobrar (F4)')!.click();
      await refrescar();
      expect(dialogo()).not.toBeNull();
    });

    it('F4 abre el diálogo también con la lista vacía y saldo 0: es un adelanto', async () => {
      await crear({ pendientes: () => of(sinDeuda) });

      await f4();

      expect(dialogo()).not.toBeNull();
      expect(texto('.sin-deudas')).toBe('Sin deudas: el cobro queda a cuenta (adelanto)');
    });

    it('con el diálogo abierto, otro F4 no abre uno nuevo ni cambia nada', async () => {
      await crear();
      await f4();
      await escribirTotal('600');

      await f4();

      expect(el().querySelectorAll('app-dialogo-cobranza')).toHaveLength(1);
      expect(total().value).toBe('600');
    });

    it('Esc en el diálogo lo cierra sin hacer ningún cobro', async () => {
      await crear();
      await f4();

      total().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true }));
      await refrescar();

      expect(dialogo()).toBeNull();
      expect(cobranza.cobrar).not.toHaveBeenCalled();
    });

    it('el diálogo arranca sin precargar la deuda', async () => {
      await crear();
      await f4();

      expect(total().value).toBe('');
    });
  });

  describe('éxito', () => {
    it('manda clave UUID, cliente, medio, total e imputaciones FIFO; cierra el diálogo y avisa del recibo', async () => {
      await crear();

      await cobrar('600');

      expect(cobranza.cobrar).toHaveBeenCalledTimes(1);
      expect(cobranza.cobrar).toHaveBeenCalledWith({
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
      expect(dialogo()).toBeNull();
      expect(texto('.alerta.exito')).toBe('Cobro registrado: Recibo N.º 12 por $ 600,00.');
    });

    it('recarga los pendientes del servidor (nada de saldo cacheado entre cobros) y muestra lo nuevo', async () => {
      const despues: PendientesCuenta = { saldo: 600, aCuenta: 0, comprobantes: [comprobante(32, 8, 200), comprobante(33, 9, 400)] };
      let llamada = 0;
      await crear({ pendientes: () => of(++llamada === 1 ? con3 : despues) });

      await cobrar('600');

      expect(cobranza.pendientes).toHaveBeenCalledTimes(2);
      expect(texto('.saldo')).toContain('$ 600,00');
      expect(el().querySelectorAll('table.pendientes tbody tr')).toHaveLength(2);
    });

    it('un adelanto (sin deuda) manda imputaciones vacías y avisa lo que quedó a cuenta', async () => {
      await crear({
        pendientes: () => of(sinDeuda),
        cobrar: () => of({ recibo: recibo({ total: 500, aCuenta: 500, imputaciones: [] }), repetida: false }),
      });

      await cobrar('500');

      expect(cobranza.cobrar).toHaveBeenCalledWith({
        clave: claves[0],
        clienteId: 8,
        medio: 'EFECTIVO',
        total: 500,
        imputaciones: [],
      });
      expect(texto('.alerta.exito')).toBe('Cobro registrado: Recibo N.º 12 por $ 500,00. Quedó a cuenta $ 500,00.');
    });

    it('un 200 (la misma clave ya estaba registrada) dice que ya estaba registrado, con el mismo recibo', async () => {
      await crear({ cobrar: () => of({ recibo: recibo(), repetida: true }) });

      await cobrar('600');

      expect(texto('.alerta.exito')).toBe('Este cobro ya estaba registrado: Recibo N.º 12 por $ 600,00.');
      expect(dialogo()).toBeNull();
    });

    it('cada vez que se abre el diálogo nace una clave nueva', async () => {
      await crear();

      await f4();
      boton('Cancelar')!.click();
      await refrescar();
      await cobrar('100');

      expect(claves).toHaveLength(2);
      expect(cobranza.cobrar.mock.calls[0][0].clave).toBe(claves[1]);
    });

    it('mientras el servidor responde, el Enter repetido no manda un segundo cobro', async () => {
      const respuesta = new Subject<ReciboCobrado>();
      await crear({ cobrar: () => respuesta });
      await f4();
      await escribirTotal('300');

      await confirmar();
      await confirmar();

      expect(cobranza.cobrar).toHaveBeenCalledTimes(1);
    });
  });

  describe('rechazos', () => {
    it('409: el diálogo sigue abierto, los pendientes se refrescan, se re-sugiere FIFO, se avisa y la clave cambia', async () => {
      const despues: PendientesCuenta = { saldo: 900, aCuenta: 0, comprobantes: [comprobante(32, 8, 500), comprobante(33, 9, 400)] };
      let llamada = 0;
      await crear({
        pendientes: () => of(++llamada === 1 ? con3 : despues),
        cobrar: () =>
          throwError(() =>
            rechazo(409, 'IMPUTACION_EXCEDE_DEUDA', 'La deuda de algún comprobante cambió.', [
              { comprobanteId: 31, referencia: 'Venta N.º 7', pendiente: 0 },
            ]),
          ),
      });

      await cobrar('600');

      expect(dialogo()).not.toBeNull();
      expect(cobranza.pendientes).toHaveBeenCalledTimes(2);
      expect(texto('.aviso')).toBe('Las deudas cambiaron: revisá la imputación y volvé a confirmar.');
      expect(
        Array.from(el().querySelectorAll<HTMLInputElement>('input[data-comprobante]')).map((i) => i.value),
      ).toEqual(['500,00', '100,00']);
      expect(total().value).toBe('600');

      // El siguiente intento es otro contenido: otra clave.
      await confirmar();
      expect(cobranza.cobrar.mock.calls[1][0].clave).toBe(claves[1]);
      expect(cobranza.cobrar.mock.calls[0][0].clave).toBe(claves[0]);
    });

    it('409 vuelve a poder confirmar (el diálogo no queda trabado en vuelo)', async () => {
      await crear({
        cobrar: () => throwError(() => rechazo(409, 'IMPUTACION_EXCEDE_DEUDA', 'La deuda cambió.', [])),
      });

      await cobrar('600');
      await confirmar();

      expect(cobranza.cobrar).toHaveBeenCalledTimes(2);
    });

    it.each([
      ['CAJA_CERRADA', 'Abrí la caja para cobrar.'],
      ['CAJA_DE_OTRO_DIA', 'La caja del 18/10 sigue abierta. Cerrala antes de cobrar.'],
    ])('422 %s muestra el mensaje del servidor, el botón sigue y el reintento usa la misma clave', async (motivo, message) => {
      await crear({ cobrar: () => throwError(() => rechazo(422, motivo, message)) });

      await cobrar('600');

      expect(dialogo()).not.toBeNull();
      expect(texto('.error-servidor')).toBe(message);
      expect(boton('Cobrar')!.disabled).toBe(false);

      await confirmar();
      expect(cobranza.cobrar).toHaveBeenCalledTimes(2);
      expect(cobranza.cobrar.mock.calls[1][0].clave).toBe(claves[0]);
    });

    it('422 CLAVE_CON_OTRO_CONTENIDO muestra el mensaje y el siguiente intento lleva una clave nueva', async () => {
      await crear({
        cobrar: () => throwError(() => rechazo(422, 'CLAVE_CON_OTRO_CONTENIDO', 'Este cobro ya se registró con otros datos.')),
      });

      await cobrar('600');
      expect(texto('.error-servidor')).toBe('Este cobro ya se registró con otros datos.');

      await confirmar();
      expect(cobranza.cobrar.mock.calls[1][0].clave).toBe(claves[1]);
    });

    it('422 «El cliente está desactivado.» (sin motivo) muestra el mensaje y no cierra el diálogo', async () => {
      await crear({
        cobrar: () => throwError(() => errorHttp(422, { status: 422, message: 'El cliente está desactivado.' })),
      });

      await cobrar('600');

      expect(texto('.error-servidor')).toBe('El cliente está desactivado.');
      expect(dialogo()).not.toBeNull();
    });

    it('403 (no es Dueño) y 404 (comprobante fuera de la cuenta) muestran el mensaje del servidor', async () => {
      await crear({
        cobrar: () => throwError(() => errorHttp(404, { status: 404, message: 'Comprobante no encontrado en la cuenta del cliente' })),
      });

      await cobrar('600');

      expect(texto('.error-servidor')).toBe('Comprobante no encontrado en la cuenta del cliente');
    });

    it('red o 5xx: mensaje de reintento seguro, el diálogo sigue y el reintento usa la MISMA clave', async () => {
      let intento = 0;
      await crear({
        cobrar: () =>
          ++intento === 1
            ? throwError(() => errorHttp(0, null))
            : intento === 2
              ? throwError(() => errorHttp(503, { status: 503, message: 'x' }))
              : of({ recibo: recibo(), repetida: true }),
      });

      await cobrar('600');
      expect(texto('.error-servidor')).toContain('No se pudo registrar el cobro');
      expect(texto('.error-servidor')).toContain('no se cobra dos veces');
      expect(dialogo()).not.toBeNull();

      await confirmar();
      await confirmar();

      const usadas = cobranza.cobrar.mock.calls.map((c) => c[0].clave);
      expect(usadas).toEqual([claves[0], claves[0], claves[0]]);
      expect(claves).toHaveLength(1);
      expect(dialogo()).toBeNull();
      expect(texto('.alerta.exito')).toContain('Este cobro ya estaba registrado');
    });

    it('400: muestra los errores por campo del servidor y el siguiente intento lleva una clave nueva', async () => {
      await crear({
        cobrar: () =>
          throwError(() =>
            errorHttp(400, {
              status: 400,
              message: 'Hay datos inválidos',
              errors: { 'imputaciones[0].importe': 'El importe admite hasta 2 decimales.' },
            }),
          ),
      });

      await cobrar('600');
      expect(texto('.error-servidor')).toBe('El importe admite hasta 2 decimales.');

      await confirmar();
      expect(cobranza.cobrar.mock.calls[1][0].clave).toBe(claves[1]);
    });

    it('un error del servidor se limpia al reabrir el diálogo', async () => {
      await crear({ cobrar: () => throwError(() => rechazo(422, 'CAJA_CERRADA', 'Abrí la caja para cobrar.')) });
      await cobrar('600');

      total().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true }));
      await refrescar();
      await f4();

      expect(el().querySelector('.error-servidor')).toBeNull();
    });
  });
});
