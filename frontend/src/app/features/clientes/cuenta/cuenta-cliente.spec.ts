import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../../core/auth/auth.service';
import { Cliente } from '../cliente.models';
import { ClientesService } from '../clientes.service';
import { AjusteCobrado, AjusteRequest, AjusteResponse } from './ajuste.models';
import { CobranzaRequest, PendientesCuenta, Recibo, ReciboCobrado } from './cobranza.models';
import { CobranzaService, GENERAR_CLAVE_COBRO } from './cobranza.service';
import { CuentaCliente } from './cuenta-cliente';
import { Extracto, LineaExtracto } from './extracto.models';
import { ExtractoService } from './extracto.service';
import { cargarEstilosGlobales } from './estilos-globales.testing';
import { ReciboImprimible } from './recibo-imprimible';
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
    estado: 'EMITIDO',
    fechaAnulacion: null,
    anuladoPor: null,
    motivoAnulacion: null,
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
    anular: ReturnType<typeof vi.fn>;
    ajustar: ReturnType<typeof vi.fn>;
  };
  let extractos: { extracto: ReturnType<typeof vi.fn>; paraImprimir: ReturnType<typeof vi.fn> };
  let claves: string[];

  /** Una venta: no tiene acciones, así que los specs de 3a no ven botones nuevos. */
  const lineaVenta: LineaExtracto = {
    id: 1,
    fecha: '2026-10-10T09:00:00',
    tipo: 'VENTA',
    referencia: 'Venta N.º 7',
    debe: 1200,
    haber: 0,
    saldo: 1200,
    usuario: 'Ana',
    reciboId: null,
    comprobanteId: 31,
    motivo: null,
    excedioLimite: false,
    autorizadoPor: null,
    anulado: false,
  };
  /** Un cobro vigente del recibo 5 (el que devuelve `recibo()`): el que se puede anular. */
  const lineaCobro: LineaExtracto = {
    ...lineaVenta,
    id: 2,
    tipo: 'COBRO',
    referencia: 'Recibo N.º 12',
    debe: 0,
    haber: 600,
    saldo: 600,
    reciboId: 5,
    comprobanteId: null,
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

  /** Un cobro ya anulado por el Dueño el 20/10 a las 09:15 (lo que devuelven el POST de anulación y el GET después). */
  const reciboAnulado = (): Recibo =>
    recibo({
      estado: 'ANULADO',
      fechaAnulacion: '2026-10-20T09:15:40',
      anuladoPor: 'Dueño Tornillo',
      motivoAnulacion: 'Cliente equivocado',
    });

  /** Lo que devuelve POST /ajustes: un Recargo de 500 sobre un saldo de 1.200 (contrato de CU-12, S1). */
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

  interface Opciones {
    dueno?: boolean;
    id?: string;
    pendientes?: () => Observable<PendientesCuenta>;
    cobrar?: (r: CobranzaRequest) => Observable<ReciboCobrado>;
    extracto?: () => Observable<Extracto>;
    cliente?: Cliente;
    anular?: (reciboId: number, motivo: string) => Observable<Recibo>;
    ajustar?: (clienteId: number, pedido: AjusteRequest) => Observable<AjusteCobrado>;
  }

  async function crear({
    dueno = true,
    id = '8',
    pendientes,
    cobrar,
    extracto: pedirExtracto,
    cliente = ana,
    anular,
    ajustar,
  }: Opciones = {}) {
    clientes = { obtener: vi.fn(() => of(cliente)) };
    extractos = {
      extracto: vi.fn(pedirExtracto ?? (() => of(extractoBase([lineaVenta])))),
      paraImprimir: vi.fn(() => of(extractoBase([lineaVenta]))),
    };
    cobranza = {
      pendientes: vi.fn(pendientes ?? (() => of(con3))),
      cobrar: vi.fn(cobrar ?? (() => of({ recibo: recibo(), repetida: false }))),
      recibo: vi.fn(() => of(recibo())),
      anular: vi.fn(anular ?? (() => of(reciboAnulado()))),
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
        { provide: ExtractoService, useValue: extractos },
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
    cargarEstilosGlobales();
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

      expect(boton('Cobrar')).toBeUndefined();
      await f4();
      expect(dialogo()).toBeNull();
      expect(cobranza.pendientes).not.toHaveBeenCalled();
    });
  });

  describe('Movimientos (CC-096)', () => {
    const movimientos = () => el().querySelector('app-movimientos-cuenta');

    it('el Dueño ve la sección «Movimientos» debajo de los pendientes y se pide el extracto del cliente (página 0)', async () => {
      await crear();

      expect(movimientos()).not.toBeNull();
      expect(limpio(movimientos()!.querySelector('h2')?.textContent)).toBe('Movimientos');
      expect(extractos.extracto).toHaveBeenCalledTimes(1);
      expect(extractos.extracto.mock.calls[0][0]).toBe(8);
      expect(extractos.extracto.mock.calls[0][2]).toBe(0);
      const pendientes = el().querySelector('table.pendientes')!;
      expect(pendientes.compareDocumentPosition(movimientos()!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('también aparece cuando el cliente no tiene deuda (debajo del aviso «No tiene comprobantes con deuda.»)', async () => {
      await crear({ pendientes: () => of(sinDeuda) });

      expect(movimientos()).not.toBeNull();
      const aviso = el().querySelector('.sin-pendientes')!;
      expect(aviso.compareDocumentPosition(movimientos()!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('el Vendedor NO ve «Movimientos», no se hace ningún request del extracto y sigue el aviso de 3a', async () => {
      await crear({ dueno: false });

      expect(movimientos()).toBeNull();
      expect(el().textContent).not.toContain('Movimientos');
      expect(extractos.extracto).not.toHaveBeenCalled();
      expect(extractos.paraImprimir).not.toHaveBeenCalled();
      expect(texto('[role="alert"], .alerta')).toBe('Solo el Dueño ve y cobra la cuenta corriente.');
    });

    it('mientras la cuenta no cargó (cargando o con error) no se pide el extracto', async () => {
      await crear({ pendientes: () => throwError(() => errorHttp(500, null)) });

      expect(movimientos()).toBeNull();
      expect(extractos.extracto).not.toHaveBeenCalled();
    });

    it('le pasa `clienteActivo` del cliente cargado: con el cliente desactivado «Anular» explica que hay que reactivarlo (S5)', async () => {
      await crear({ cliente: { ...ana, activo: false }, extracto: () => of(extractoBase([lineaCobro])) });

      const anular = movimientos()!.querySelector<HTMLButtonElement>('button.anular')!;
      expect(anular.getAttribute('aria-disabled')).toBe('true');
      expect(anular.hasAttribute('disabled')).toBe(false);
      expect(limpio(movimientos()!.querySelector('.aviso-inactivo')?.textContent)).toBe('Reactivá el cliente para anular');
    });

    it('con el cliente activo «Anular» está habilitado', async () => {
      await crear({ extracto: () => of(extractoBase([lineaCobro])) });

      expect(movimientos()!.querySelector<HTMLButtonElement>('button.anular')!.hasAttribute('aria-disabled')).toBe(false);
    });

    it('tras un cobro la sección se recarga (version++) y muestra el cobro nuevo', async () => {
      await crear();
      expect(extractos.extracto).toHaveBeenCalledTimes(1);

      await cobrar('600');

      expect(extractos.extracto).toHaveBeenCalledTimes(2);
    });

    it('F4 sigue abriendo Cobrar con la sección a la vista', async () => {
      await crear();

      await f4();

      expect(dialogo()).not.toBeNull();
    });
  });

  describe('Anular un cobro y reimprimir (CC-097, CC-098, CC-060)', () => {
    const lineaAnulacion: LineaExtracto = {
      ...lineaCobro,
      id: 3,
      tipo: 'ANULACION',
      referencia: 'Anulación Recibo N.º 12',
      debe: 600,
      haber: 0,
      saldo: 1200,
      motivo: 'Cliente equivocado',
    };
    const anulacion = () => el().querySelector('app-dialogo-anulacion');
    const botonAnular = () => el().querySelector<HTMLButtonElement>('app-movimientos-cuenta button.anular')!;
    const motivo = () => el().querySelector<HTMLInputElement>('#motivo-anulacion')!;
    const recibos = () => el().querySelectorAll('app-recibo-imprimible');
    const alerta = () => limpio(el().querySelector('.alerta')?.textContent);

    async function pedirAnular() {
      botonAnular().focus();
      botonAnular().click();
      await refrescar();
    }

    async function escribirMotivo(valor: string) {
      motivo().value = valor;
      motivo().dispatchEvent(new Event('input', { bubbles: true }));
      await refrescar();
    }

    async function teclaEnMotivo(key: string) {
      motivo().dispatchEvent(new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true }));
      await refrescar();
    }

    /** Abre el diálogo, escribe un motivo válido y confirma con Enter. */
    async function anularConMotivo(valor = 'Cliente equivocado') {
      await pedirAnular();
      await escribirMotivo(valor);
      await teclaEnMotivo('Enter');
    }

    const conCobro = (opciones: Opciones = {}) => crear({ extracto: () => of(extractoBase([lineaCobro])), ...opciones });

    describe('abrir el diálogo', () => {
      it('«Anular» trae el recibo (GET) y abre el diálogo de anulación con su resumen; todavía no se anula', async () => {
        await conCobro();

        await pedirAnular();

        expect(cobranza.recibo).toHaveBeenCalledWith(5);
        expect(anulacion()).not.toBeNull();
        expect(limpio(anulacion()!.querySelector('h2')?.textContent)).toBe('Anular recibo N.º 12');
        expect(limpio(anulacion()!.querySelector('.resumen')?.textContent)).toContain('Ana Gómez');
        expect(cobranza.anular).not.toHaveBeenCalled();
      });

      it('si no se puede traer el recibo no abre nada y avisa que se pruebe de nuevo', async () => {
        await conCobro();
        cobranza.recibo.mockReturnValue(throwError(() => errorHttp(500, null)));

        await pedirAnular();

        expect(anulacion()).toBeNull();
        expect(texto('[role="alert"]')).toContain('No se pudo traer el recibo.');
        expect(cobranza.anular).not.toHaveBeenCalled();
      });

      it('mientras trae el recibo un segundo clic en «Anular» no pide otro', async () => {
        const respuesta = new Subject<Recibo>();
        await conCobro();
        cobranza.recibo.mockReturnValue(respuesta);

        botonAnular().click();
        botonAnular().click();
        await refrescar();
        respuesta.next(recibo());
        respuesta.complete();
        await refrescar();

        expect(cobranza.recibo).toHaveBeenCalledTimes(1);
        expect(el().querySelectorAll('app-dialogo-anulacion')).toHaveLength(1);
      });

      it('si el recibo ya estaba anulado (la fila estaba desactualizada) no abre el diálogo: avisa y recarga', async () => {
        await conCobro();
        cobranza.recibo.mockReturnValue(of(reciboAnulado()));

        await pedirAnular();

        expect(anulacion()).toBeNull();
        expect(alerta()).toContain('Este recibo ya fue anulado por Dueño Tornillo el 20/10/2026 09:15');
        expect(extractos.extracto).toHaveBeenCalledTimes(2);
        expect(cobranza.pendientes).toHaveBeenCalledTimes(2);
      });

      it('si el recibo ya estaba anulado el foco pasa a «Ver recibo»: «Anular» desaparece al recargar la fila y no cae al body', async () => {
        await conCobro();
        cobranza.recibo.mockReturnValue(of(reciboAnulado()));

        await pedirAnular();

        expect(document.activeElement).toBe(boton('Ver recibo'));
      });

      it('con el diálogo abierto F4 no abre el cobro', async () => {
        await conCobro();
        await pedirAnular();

        await f4();

        expect(dialogo()).toBeNull();
        expect(anulacion()).not.toBeNull();
      });

      it('mientras se trae el recibo para anular F4 tampoco abre el cobro', async () => {
        await conCobro();
        cobranza.recibo.mockReturnValue(new Subject<Recibo>());

        botonAnular().click();
        await refrescar();
        await f4();

        expect(dialogo()).toBeNull();
      });
    });

    describe('confirmar', () => {
      it('Enter con un motivo válido anula el recibo con el motivo recortado', async () => {
        await conCobro();

        await anularConMotivo('  Cliente equivocado  ');

        expect(cobranza.anular).toHaveBeenCalledTimes(1);
        expect(cobranza.anular).toHaveBeenCalledWith(5, 'Cliente equivocado');
      });

      it('con un motivo corto no se llama al backend', async () => {
        await conCobro();

        await pedirAnular();
        await escribirMotivo('abc');
        await teclaEnMotivo('Enter');

        expect(cobranza.anular).not.toHaveBeenCalled();
        expect(anulacion()).not.toBeNull();
      });

      it('mientras se envía el diálogo se deshabilita y un segundo Enter no manda otra anulación', async () => {
        const respuesta = new Subject<Recibo>();
        await conCobro({ anular: () => respuesta });
        await pedirAnular();
        await escribirMotivo('Cliente equivocado');

        await teclaEnMotivo('Enter');
        await teclaEnMotivo('Enter');

        expect(cobranza.anular).toHaveBeenCalledTimes(1);
        expect(anulacion()!.querySelector<HTMLButtonElement>('button.anular-recibo')!.disabled).toBe(true);
      });
    });

    describe('200: anulado', () => {
      it('cierra el diálogo y avisa «Recibo N.º 12 anulado. Los comprobantes volvieron a quedar pendientes.»', async () => {
        await conCobro();

        await anularConMotivo();

        expect(anulacion()).toBeNull();
        expect(texto('.alerta.exito span')).toBe('Recibo N.º 12 anulado. Los comprobantes volvieron a quedar pendientes.');
        expect(boton('Ver recibo')).toBeDefined();
      });

      it('recarga los pendientes y el extracto (saldo, pendientes y Movimientos se refrescan)', async () => {
        await conCobro();
        expect(cobranza.pendientes).toHaveBeenCalledTimes(1);
        expect(extractos.extracto).toHaveBeenCalledTimes(1);

        await anularConMotivo();

        expect(cobranza.pendientes).toHaveBeenCalledTimes(2);
        expect(extractos.extracto).toHaveBeenCalledTimes(2);
      });

      it('el foco pasa a «Ver recibo»: la fila ya no tiene «Anular» y el resultado se anuncia ahí', async () => {
        await conCobro();

        await anularConMotivo();

        expect(document.activeElement).toBe(boton('Ver recibo'));
      });

      it('«Ver recibo» trae el recibo (GET) y lo muestra con la marca ANULADO, el motivo y quién lo anuló', async () => {
        await conCobro();
        await anularConMotivo();
        cobranza.recibo.mockClear();
        cobranza.recibo.mockReturnValue(of(reciboAnulado()));

        boton('Ver recibo')!.click();
        await refrescar();

        expect(cobranza.recibo).toHaveBeenCalledWith(5);
        expect(recibos()).toHaveLength(1);
        const contenido = limpio(recibos()[0].textContent);
        expect(contenido).toContain('ANULADO');
        expect(contenido).toContain('Motivo: Cliente equivocado');
        expect(contenido).toContain('Anulado por Dueño Tornillo el 20/10/2026 09:15');
      });

      it('un cobro nuevo saca el aviso de la anulación', async () => {
        await conCobro();
        await anularConMotivo();

        await f4();

        expect(el().querySelector('.alerta')).toBeNull();
      });

      it('no deja el aviso del cobro anterior junto al de la anulación', async () => {
        await conCobro();
        await cobrar('600');
        boton('Cerrar')!.click();
        await refrescar();
        expect(boton('Reimprimir recibo')).toBeDefined();

        await anularConMotivo();

        expect(el().querySelectorAll('.alerta')).toHaveLength(1);
        expect(boton('Reimprimir recibo')).toBeUndefined();
      });
    });

    describe('rechazos', () => {
      it('409 RECIBO_YA_ANULADO: cierra el diálogo, avisa quién y cuándo y recarga (hecho consumado)', async () => {
        await conCobro({
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

        await anularConMotivo();

        expect(anulacion()).toBeNull();
        expect(texto('.alerta')).toContain('Este recibo ya fue anulado por Dueño Tornillo el 20/10/2026 09:15');
        expect(texto('.alerta.exito')).toBeNull();
        expect(cobranza.pendientes).toHaveBeenCalledTimes(2);
        expect(extractos.extracto).toHaveBeenCalledTimes(2);
      });

      it('409: el foco pasa a «Ver recibo» (el aviso), no cae al body cuando «Anular» desaparece', async () => {
        await conCobro({
          anular: () =>
            throwError(() =>
              rechazo(409, 'RECIBO_YA_ANULADO', 'El recibo ya fue anulado.'),
            ),
        });

        await anularConMotivo();

        expect(anulacion()).toBeNull();
        expect(document.activeElement).toBe(boton('Ver recibo'));
      });

      it('409 sin quién ni cuándo: muestra el mensaje del servidor', async () => {
        await conCobro({
          anular: () => throwError(() => rechazo(409, 'RECIBO_YA_ANULADO', 'El recibo ya fue anulado.')),
        });

        await anularConMotivo();

        expect(anulacion()).toBeNull();
        expect(texto('.alerta')).toContain('El recibo ya fue anulado.');
      });

      it.each([
        ['CAJA_CERRADA', 'Abrí la caja para anular.'],
        ['CAJA_DE_OTRO_DIA', 'La caja del 18/10 sigue abierta. Cerrala antes de anular.'],
      ])('422 %s: muestra el mensaje del servidor, el diálogo sigue abierto y conserva el motivo', async (codigo, message) => {
        await conCobro({ anular: () => throwError(() => rechazo(422, codigo, message)) });

        await anularConMotivo();

        expect(anulacion()).not.toBeNull();
        expect(limpio(anulacion()!.querySelector('.error-servidor')?.textContent)).toBe(message);
        expect(motivo().value).toBe('Cliente equivocado');
        expect(el().querySelector('.alerta')).toBeNull();
      });

      it('422 sin motivo (cliente desactivado): muestra el mensaje y el diálogo sigue abierto', async () => {
        await conCobro({
          anular: () => throwError(() => errorHttp(422, { status: 422, message: 'El cliente está desactivado.' })),
        });

        await anularConMotivo();

        expect(anulacion()).not.toBeNull();
        expect(limpio(anulacion()!.querySelector('.error-servidor')?.textContent)).toBe('El cliente está desactivado.');
      });

      it('después de un rechazo se puede volver a confirmar', async () => {
        await conCobro({ anular: () => throwError(() => rechazo(422, 'CAJA_CERRADA', 'Abrí la caja.')) });
        await anularConMotivo();

        await teclaEnMotivo('Enter');

        expect(cobranza.anular).toHaveBeenCalledTimes(2);
      });

      it('400: muestra el error del motivo que manda el servidor', async () => {
        await conCobro({
          anular: () =>
            throwError(() =>
              errorHttp(400, { status: 400, message: 'Datos inválidos.', errors: { motivo: 'El motivo es obligatorio.' } }),
            ),
        });

        await anularConMotivo();

        expect(anulacion()).not.toBeNull();
        expect(limpio(anulacion()!.querySelector('.error-servidor')?.textContent)).toBe('El motivo es obligatorio.');
      });

      it('503: «El sistema está ocupado, reintentá.» y el diálogo sigue abierto', async () => {
        await conCobro({
          anular: () => throwError(() => errorHttp(503, { status: 503, message: 'El sistema está ocupado, reintentá.' })),
        });

        await anularConMotivo();

        expect(anulacion()).not.toBeNull();
        expect(limpio(anulacion()!.querySelector('.error-servidor')?.textContent)).toBe('El sistema está ocupado, reintentá.');
      });

      it('sin conexión o 5xx: dice que no se pudo, que se reintente y que si ya se anuló se va a ver el aviso', async () => {
        await conCobro({ anular: () => throwError(() => errorHttp(0, new ProgressEvent('error'))) });

        await anularConMotivo();

        expect(anulacion()).not.toBeNull();
        const mensaje = limpio(anulacion()!.querySelector('.error-servidor')?.textContent);
        expect(mensaje).toContain('No se pudo anular');
        expect(mensaje).toContain('Reintentá');
        expect(mensaje).toContain('si ya se anuló, vas a ver el aviso');
      });

      it('un 500 se trata igual que la falta de conexión y reintentar es seguro (se vuelve a mandar)', async () => {
        await conCobro({ anular: () => throwError(() => errorHttp(500, null)) });
        await anularConMotivo();
        expect(limpio(anulacion()!.querySelector('.error-servidor')?.textContent)).toContain('Reintentá');

        await teclaEnMotivo('Enter');

        expect(cobranza.anular).toHaveBeenCalledTimes(2);
        expect(cobranza.anular).toHaveBeenNthCalledWith(2, 5, 'Cliente equivocado');
      });

      it('un error con mensaje del servidor que no es de los conocidos se muestra tal cual', async () => {
        await conCobro({ anular: () => throwError(() => errorHttp(404, { status: 404, message: 'Recibo no encontrado.' })) });

        await anularConMotivo();

        expect(limpio(anulacion()!.querySelector('.error-servidor')?.textContent)).toBe('Recibo no encontrado.');
      });

      it('al reintentar se limpia el error anterior', async () => {
        let intento = 0;
        const respuesta = new Subject<Recibo>();
        await conCobro({
          anular: () => (++intento === 1 ? throwError(() => rechazo(422, 'CAJA_CERRADA', 'Abrí la caja.')) : respuesta),
        });
        await anularConMotivo();
        expect(anulacion()!.querySelector('.error-servidor')).not.toBeNull();

        await teclaEnMotivo('Enter');

        expect(anulacion()!.querySelector('.error-servidor')).toBeNull();
      });
    });

    describe('cancelar', () => {
      it('Esc cierra sin anular y el foco vuelve al botón «Anular» de origen', async () => {
        await conCobro();
        await pedirAnular();

        await teclaEnMotivo('Escape');

        expect(anulacion()).toBeNull();
        expect(cobranza.anular).not.toHaveBeenCalled();
        expect(document.activeElement).toBe(botonAnular());
      });

      it('«Cancelar» cierra sin anular y no deja avisos', async () => {
        await conCobro();
        await pedirAnular();

        boton('Cancelar')!.click();
        await refrescar();

        expect(anulacion()).toBeNull();
        expect(cobranza.anular).not.toHaveBeenCalled();
        expect(el().querySelector('.alerta')).toBeNull();
      });

      it('al volver a abrir, el diálogo empieza limpio (sin el error ni el motivo de antes)', async () => {
        await conCobro({ anular: () => throwError(() => rechazo(422, 'CAJA_CERRADA', 'Abrí la caja.')) });
        await anularConMotivo();
        await teclaEnMotivo('Escape');

        await pedirAnular();

        expect(anulacion()!.querySelector('.error-servidor')).toBeNull();
        expect(motivo().value).toBe('');
      });
    });

    describe('Reimprimir (CC-098)', () => {
      const reimprimir = () => el().querySelector<HTMLButtonElement>('app-movimientos-cuenta button.reimprimir')!;

      it('en una fila COBRO trae el recibo por su id y lo muestra', async () => {
        await conCobro();

        reimprimir().click();
        await refrescar();

        expect(cobranza.recibo).toHaveBeenCalledWith(5);
        expect(recibos()).toHaveLength(1);
        expect(limpio(recibos()[0].textContent)).toContain('Recibo N.º 12');
        expect(limpio(recibos()[0].textContent)).not.toContain('ANULADO');
      });

      it('en una fila ANULACION abre el mismo recibo, ya ANULADO', async () => {
        await crear({ extracto: () => of(extractoBase([lineaAnulacion])) });
        cobranza.recibo.mockReturnValue(of(reciboAnulado()));

        reimprimir().click();
        await refrescar();

        expect(cobranza.recibo).toHaveBeenCalledWith(5);
        expect(limpio(recibos()[0].textContent)).toContain('ANULADO');
      });

      it('si no se puede traer el recibo avisa y no abre nada', async () => {
        await conCobro();
        cobranza.recibo.mockReturnValue(throwError(() => errorHttp(500, null)));

        reimprimir().click();
        await refrescar();

        expect(recibos()).toHaveLength(0);
        expect(texto('[role="alert"]')).toContain('No se pudo traer el recibo.');
      });

      it('Esc cierra el recibo y el foco vuelve a «Reimprimir»', async () => {
        await conCobro();
        reimprimir().focus();
        reimprimir().click();
        await refrescar();

        el().querySelector('app-recibo-imprimible [role="dialog"]')!.dispatchEvent(
          new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true }),
        );
        await refrescar();

        expect(recibos()).toHaveLength(0);
        expect(document.activeElement).toBe(reimprimir());
      });

      it('con el recibo abierto F4 no abre el cobro', async () => {
        await conCobro();
        reimprimir().click();
        await refrescar();

        await f4();

        expect(dialogo()).toBeNull();
      });
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

    it('la referencia de cada comprobante no se fuerza a mayúsculas («Venta N.º 7», no «VENTA N.º 7»)', async () => {
      await crear();

      const referencia = el().querySelector<HTMLElement>('table.pendientes tbody th')!;
      expect(referencia.textContent).toContain('Venta N.º 7');
      expect(getComputedStyle(referencia).textTransform).toBe('none');
    });

    it('la referencia de cada fila es un dato más: sin el fondo gris ni el sticky del encabezado de columna', async () => {
      await crear();

      const referencia = getComputedStyle(el().querySelector<HTMLElement>('table.pendientes tbody th')!);
      expect(referencia.position).toBe('static');
      expect(['transparent', 'rgba(0, 0, 0, 0)']).toContain(referencia.backgroundColor);
      expect(getComputedStyle(el().querySelector<HTMLElement>('table.pendientes thead th')!).position).toBe('sticky');
    });

    it('la referencia va con la letra del resto de la fila, no la chica del encabezado (#77)', async () => {
      await crear();

      const referencia = el().querySelector<HTMLElement>('table.pendientes tbody th')!;
      const celda = referencia.closest('tr')!.querySelector<HTMLElement>('td')!;
      expect(getComputedStyle(referencia).fontSize).toBe(getComputedStyle(celda).fontSize);
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
      expect(boton('Cobrar')!.disabled).toBe(true);
      await f4();
      expect(dialogo()).toBeNull();
    });

    it('si no se puede leer muestra el error con «Reintentar», que vuelve a pedir todo', async () => {
      let intento = 0;
      await crear({ pendientes: () => (++intento === 1 ? throwError(() => errorHttp(500, null)) : of(con3)) });

      expect(texto('[role="alert"]')).toContain('No se pudo cargar la cuenta del cliente.');
      expect(boton('Cobrar')!.disabled).toBe(true);

      boton('Reintentar')!.click();
      await refrescar();

      expect(el().querySelector('[role="alert"]')).toBeNull();
      expect(el().querySelectorAll('table.pendientes tbody tr')).toHaveLength(3);
      expect(clientes.obtener).toHaveBeenCalledTimes(2);
    });

    it('cliente inexistente o de otra ferretería (404): dice «Cliente no encontrado», sin «Reintentar» y con el enlace para volver', async () => {
      await crear({
        pendientes: () => throwError(() => errorHttp(404, { status: 404, message: 'Cliente no encontrado' })),
      });

      expect(texto('[role="alert"]')).toContain('Cliente no encontrado');
      expect(boton('Reintentar')).toBeUndefined();
      expect(el().querySelector('a[href="/clientes"]')).not.toBeNull();
      expect(boton('Cobrar')!.disabled).toBe(true);
    });

    it('el 404 también llega por la lectura del cliente: sin «Reintentar»', async () => {
      await crear();
      clientes.obtener.mockReturnValue(throwError(() => errorHttp(404, { status: 404, message: 'Cliente no encontrado' })));

      fixture.componentInstance['cargar']();
      await refrescar();

      expect(texto('[role="alert"]')).toContain('Cliente no encontrado');
      expect(boton('Reintentar')).toBeUndefined();
    });

    it('un error de red (status 0) conserva «Reintentar»', async () => {
      await crear({ pendientes: () => throwError(() => errorHttp(0, null)) });

      expect(boton('Reintentar')).toBeDefined();
    });

    it('un 404 seguido de un reintento posible no deja el botón escondido: un 500 después vuelve a mostrarlo', async () => {
      let intento = 0;
      await crear({
        pendientes: () =>
          ++intento === 1
            ? throwError(() => errorHttp(404, { status: 404, message: 'Cliente no encontrado' }))
            : throwError(() => errorHttp(500, null)),
      });
      expect(boton('Reintentar')).toBeUndefined();

      fixture.componentInstance['cargar']();
      await refrescar();

      expect(boton('Reintentar')).toBeDefined();
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
    it('F4 abre el diálogo y el botón «Cobrar» hace lo mismo', async () => {
      await crear();

      const evento = await f4();
      expect(dialogo()).not.toBeNull();
      expect(evento.defaultPrevented).toBe(true);

      boton('Cancelar')!.click();
      await refrescar();
      expect(dialogo()).toBeNull();

      boton('Cobrar')!.click();
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
      expect(texto('.alerta.exito span')).toBe('Cobro registrado: Recibo N.º 12 por $ 600,00.');
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
      expect(texto('.alerta.exito span')).toBe('Cobro registrado: Recibo N.º 12 por $ 500,00. Quedó a cuenta $ 500,00.');
    });

    it('un 200 (la misma clave ya estaba registrada) dice que ya estaba registrado, con el mismo recibo', async () => {
      await crear({ cobrar: () => of({ recibo: recibo(), repetida: true }) });

      await cobrar('600');

      expect(texto('.alerta.exito span')).toBe('Este cobro ya estaba registrado: Recibo N.º 12 por $ 600,00.');
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
      expect(el().querySelector<HTMLButtonElement>('app-dialogo-cobranza button.cobrar')!.disabled).toBe(false);

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
      expect(texto('.alerta.exito span')).toContain('Este cobro ya estaba registrado');
    });

    describe('contenido congelado tras un envío sin confirmar (red o 5xx)', () => {
      const congelado = () => el().querySelector('.aviso-congelado');
      const medioElegido = () => texto('button[role="radio"][aria-checked="true"]');
      const salida = () => boton('Cancelar y empezar de nuevo');

      /** Falla el primer envío con `primero` y los siguientes con `despues` (por defecto, éxito). */
      const fallando = (primero: unknown, despues?: unknown) => {
        let intento = 0;
        return () =>
          ++intento === 1
            ? throwError(() => primero)
            : despues
              ? throwError(() => despues)
              : of({ recibo: recibo(), repetida: true });
      };

      it.each([
        ['red (status 0)', errorHttp(0, null)],
        ['503', errorHttp(503, { status: 503, message: 'x' })],
      ])('%s congela el total, las filas y el medio, y avisa que se reenvía lo mismo', async (_n, error) => {
        await crear({ cobrar: fallando(error) });

        await cobrar('600');

        expect(congelado()).not.toBeNull();
        expect(total().readOnly).toBe(true);
        expect(
          Array.from(el().querySelectorAll<HTMLInputElement>('input[data-comprobante]')).every((f) => f.readOnly),
        ).toBe(true);
        expect(salida()).toBeDefined();
      });

      it('el reintento manda lo mismo y con la MISMA clave aunque se intente tipear', async () => {
        await crear({ cobrar: fallando(errorHttp(0, null)) });

        await cobrar('600');
        await escribirTotal('50');
        await confirmar();

        expect(cobranza.cobrar).toHaveBeenCalledTimes(2);
        expect(cobranza.cobrar.mock.calls[1][0]).toEqual(cobranza.cobrar.mock.calls[0][0]);
        expect(claves).toHaveLength(1);
      });

      it('si el reintento sale bien, el diálogo se cierra y muestra el recibo', async () => {
        await crear({ cobrar: fallando(errorHttp(0, null)) });

        await cobrar('600');
        await confirmar();

        expect(dialogo()).toBeNull();
        expect(el().querySelectorAll('app-recibo-imprimible')).toHaveLength(1);
      });

      it('«Cancelar y empezar de nuevo»: libera el contenido, limpia el error, pasa a una clave nueva y deja el diálogo abierto', async () => {
        await crear({ cobrar: fallando(errorHttp(0, null)) });
        await cobrar('600');
        expect(texto('.error-servidor')).toContain('No se pudo registrar el cobro');

        salida()!.click();
        await refrescar();

        expect(dialogo()).not.toBeNull();
        expect(congelado()).toBeNull();
        expect(el().querySelector('.error-servidor')).toBeNull();
        expect(total().readOnly).toBe(false);
        expect(claves).toHaveLength(2);
        expect(cobranza.cobrar).toHaveBeenCalledTimes(1);
      });

      it('«Cancelar y empezar de nuevo» deja el foco en el total (no cae al body) y Esc cierra el diálogo sin tocar Tab', async () => {
        await crear({ cobrar: fallando(errorHttp(0, null)) });
        await cobrar('600');
        salida()!.focus();

        salida()!.click();
        await refrescar();

        expect(salida()).toBeUndefined();
        expect(document.activeElement).toBe(total());
        (document.activeElement as HTMLElement).dispatchEvent(
          new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true }),
        );
        await refrescar();
        expect(dialogo()).toBeNull();
      });

      it('un rechazo definitivo en el reintento también deja el foco en el total si el foco estaba en «Cancelar y empezar de nuevo»', async () => {
        await crear({
          cobrar: fallando(errorHttp(0, null), rechazo(422, 'CAJA_CERRADA', 'Abrí la caja para cobrar.')),
        });
        await cobrar('600');
        salida()!.focus();

        await confirmar();

        expect(salida()).toBeUndefined();
        expect(document.activeElement).toBe(total());
      });

      it('después de empezar de nuevo, el contenido editado se manda con la clave nueva', async () => {
        await crear({ cobrar: fallando(errorHttp(0, null)) });
        await cobrar('600');
        salida()!.click();
        await refrescar();

        await escribirTotal('250');
        await confirmar();

        const enviado = cobranza.cobrar.mock.calls[1][0] as CobranzaRequest;
        expect(enviado.clave).toBe(claves[1]);
        expect(enviado.total).toBe(250);
        expect(dialogo()).toBeNull();
      });

      it('después de empezar de nuevo, un nuevo envío sin respuesta vuelve a congelar', async () => {
        await crear({ cobrar: () => throwError(() => errorHttp(0, null)) });
        await cobrar('600');
        salida()!.click();
        await refrescar();

        await escribirTotal('250');
        await confirmar();

        expect(congelado()).not.toBeNull();
        expect(total().readOnly).toBe(true);
      });

      it('un rechazo definitivo en el reintento (422 caja cerrada) libera el contenido, con la misma clave', async () => {
        await crear({
          cobrar: fallando(errorHttp(0, null), rechazo(422, 'CAJA_CERRADA', 'Abrí la caja para cobrar.')),
        });
        await cobrar('600');
        expect(congelado()).not.toBeNull();

        await confirmar();

        expect(congelado()).toBeNull();
        expect(total().readOnly).toBe(false);
        expect(texto('.error-servidor')).toBe('Abrí la caja para cobrar.');
        expect(claves).toHaveLength(1);
      });

      it('un 409 (la deuda cambió) en el reintento libera el contenido y regenera la clave', async () => {
        await crear({
          cobrar: fallando(errorHttp(0, null), rechazo(409, 'IMPUTACION_EXCEDE_DEUDA', 'La deuda cambió.', [])),
        });
        await cobrar('600');

        await confirmar();

        expect(congelado()).toBeNull();
        expect(total().readOnly).toBe(false);
        expect(claves).toHaveLength(2);
      });

      it('un 400 en el reintento libera el contenido', async () => {
        await crear({
          cobrar: fallando(errorHttp(0, null), errorHttp(400, { status: 400, message: 'Hay datos inválidos', errors: {} })),
        });
        await cobrar('600');

        await confirmar();

        expect(congelado()).toBeNull();
        expect(total().readOnly).toBe(false);
      });

      it('un 422 CLAVE_CON_OTRO_CONTENIDO en el reintento libera el contenido y regenera la clave', async () => {
        await crear({
          cobrar: fallando(
            errorHttp(0, null),
            rechazo(422, 'CLAVE_CON_OTRO_CONTENIDO', 'Este cobro ya se registró con otros datos.'),
          ),
        });
        await cobrar('600');

        await confirmar();

        expect(congelado()).toBeNull();
        expect(claves).toHaveLength(2);
      });

      it.each([
        ['409', rechazo(409, 'IMPUTACION_EXCEDE_DEUDA', 'La deuda cambió.', [])],
        ['422', rechazo(422, 'CAJA_CERRADA', 'Abrí la caja para cobrar.')],
        ['400', errorHttp(400, { status: 400, message: 'Hay datos inválidos', errors: {} })],
        ['404', errorHttp(404, { status: 404, message: 'Comprobante no encontrado' })],
      ])('un %s en el primer envío no congela nada', async (_n, error) => {
        await crear({ cobrar: () => throwError(() => error) });

        await cobrar('600');

        expect(congelado()).toBeNull();
        expect(salida()).toBeUndefined();
        expect(total().readOnly).toBe(false);
      });

      it('mientras el reintento está en vuelo sigue congelado', async () => {
        const respuesta = new Subject<ReciboCobrado>();
        let intento = 0;
        await crear({ cobrar: () => (++intento === 1 ? throwError(() => errorHttp(0, null)) : respuesta) });
        await cobrar('600');

        await confirmar();

        expect(congelado()).not.toBeNull();
        expect(total().readOnly).toBe(true);
        respuesta.complete();
      });

      it('cerrar con Esc y reabrir con F4 empieza de cero: clave nueva, sin congelar y sin el contenido anterior', async () => {
        await crear({ cobrar: fallando(errorHttp(0, null)) });
        await cobrar('600');

        total().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true }));
        await refrescar();
        await f4();

        expect(congelado()).toBeNull();
        expect(total().readOnly).toBe(false);
        expect(total().value).toBe('');
        expect(medioElegido()).toBe('Efectivo');
        expect(claves).toHaveLength(2);
      });
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

  describe('recibo (CC-036, CC-043)', () => {
    const recibos = () => el().querySelectorAll('app-recibo-imprimible');
    const reciboTexto = () => limpio(el().querySelector('app-recibo-imprimible')?.textContent);

    it('tras un cobro exitoso (201) muestra el recibo, con su número, el cliente y las imputaciones', async () => {
      await crear();

      await cobrar('600');

      expect(dialogo()).toBeNull();
      expect(recibos()).toHaveLength(1);
      expect(reciboTexto()).toContain('Recibo N.º 12');
      expect(reciboTexto()).toContain('Ana Gómez');
      expect(reciboTexto()).toContain('Venta N.º 7');
      expect(reciboTexto()).toContain('Venta N.º 8');
      expect(reciboTexto()).toContain('Recibo interno, no válido como comprobante fiscal');
    });

    it('tras un 200 repetido muestra el mismo recibo', async () => {
      await crear({ cobrar: () => of({ recibo: recibo(), repetida: true }) });

      await cobrar('600');

      expect(recibos()).toHaveLength(1);
      expect(reciboTexto()).toContain('Recibo N.º 12');
      expect(texto('.alerta.exito span')).toContain('Este cobro ya estaba registrado');
    });

    it('el adelanto muestra el recibo con «A cuenta $X» y sin tabla de imputaciones', async () => {
      await crear({
        pendientes: () => of(sinDeuda),
        cobrar: () => of({ recibo: recibo({ total: 500, aCuenta: 500, imputaciones: [] }), repetida: false }),
      });

      await cobrar('500');

      expect(reciboTexto()).toContain('A cuenta $ 500,00');
      expect(el().querySelector('app-recibo-imprimible table')).toBeNull();
    });

    it('Esc cierra el recibo y el aviso del cobro queda con «Reimprimir recibo»', async () => {
      await crear();
      await cobrar('600');

      el().querySelector('app-recibo-imprimible [role="dialog"]')!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true }),
      );
      await refrescar();

      expect(recibos()).toHaveLength(0);
      expect(texto('.alerta.exito span')).toContain('Recibo N.º 12');
      expect(boton('Reimprimir recibo')).toBeDefined();
    });

    it('«Reimprimir recibo» usa GET /recibos/{id} y muestra lo que dice el servidor', async () => {
      await crear();
      await cobrar('600');
      boton('Cerrar')!.click();
      await refrescar();
      cobranza.recibo.mockReturnValue(of(recibo({ cobradoPor: 'Otro Usuario' })));

      boton('Reimprimir recibo')!.click();
      await refrescar();

      expect(cobranza.recibo).toHaveBeenCalledWith(5);
      expect(recibos()).toHaveLength(1);
      expect(reciboTexto()).toContain('Otro Usuario');
    });

    it('si no se puede traer el recibo avisa y deja reintentar', async () => {
      await crear();
      await cobrar('600');
      boton('Cerrar')!.click();
      await refrescar();
      cobranza.recibo.mockReturnValue(throwError(() => errorHttp(500, null)));

      boton('Reimprimir recibo')!.click();
      await refrescar();

      expect(recibos()).toHaveLength(0);
      expect(texto('[role="alert"]')).toContain('No se pudo traer el recibo.');
      expect(boton('Reimprimir recibo')).toBeDefined();
    });

    it('con el recibo abierto F4 no abre el cobro', async () => {
      await crear();
      await cobrar('600');

      await f4();

      expect(dialogo()).toBeNull();
      expect(recibos()).toHaveLength(1);
    });

    it('el aviso del cobro y «Reimprimir recibo» van en una fila con espacio entre ellos (no pegados)', async () => {
      await crear();
      await cobrar('600');
      boton('Cerrar')!.click();
      await refrescar();

      const aviso = getComputedStyle(el().querySelector<HTMLElement>('.alerta.exito')!);
      expect(aviso.display).toBe('flex');
      expect(aviso.justifyContent).toBe('space-between');
      expect(aviso.gap).not.toBe('');
      expect(aviso.gap).not.toBe('0px');
    });

    it('un cobro nuevo saca el recibo anterior y su aviso', async () => {
      await crear();
      await cobrar('600');
      boton('Cerrar')!.click();
      await refrescar();

      await f4();

      expect(el().querySelector('.alerta.exito')).toBeNull();
      expect(boton('Reimprimir recibo')).toBeUndefined();
    });
  });

  describe('Ajustes: Recargo y Descuento (CU-12, S5, S8)', () => {
    const recargoPartida = (ajusteId: number, pendiente: number) => ({
      comprobanteId: null,
      ajusteId,
      referencia: `Recargo N.º ${ajusteId}`,
      fecha: '2026-10-11T09:00:00',
      importe: pendiente,
      imputado: 0,
      pendiente,
    });
    /** Venta 300, Recargo 200 (ajusteId 3) y venta 500: saldo 1.000. */
    const conRecargo: PendientesCuenta = {
      saldo: 1000,
      aCuenta: 0,
      comprobantes: [comprobante(31, 7, 300), recargoPartida(3, 200), comprobante(32, 8, 500)],
    };
    const dialogoAjuste = () => el().querySelector('app-dialogo-ajuste');
    const importeAj = () => el().querySelector<HTMLInputElement>('#importe-ajuste')!;
    const motivoAj = () => el().querySelector<HTMLInputElement>('#motivo-ajuste')!;

    async function escribirEn(campo: HTMLInputElement, valor: string) {
      campo.value = valor;
      campo.dispatchEvent(new Event('input', { bubbles: true }));
      await refrescar();
    }

    async function pulsar(nombre: string) {
      boton(nombre)!.click();
      await refrescar();
    }

    /** Abre el diálogo del tipo, escribe importe y motivo, pasa a la confirmación y la confirma. */
    async function ajustarCon(tipo: 'Recargo' | 'Descuento', importe: string, motivo = 'Deuda vieja del cliente') {
      await pulsar(tipo);
      await escribirEn(importeAj(), importe);
      await escribirEn(motivoAj(), motivo);
      await pulsar('Continuar');
      await pulsar(`Confirmar ${tipo.toLowerCase()}`);
    }

    describe('quién los ve', () => {
      it('el Dueño tiene «Recargo» y «Descuento» en la barra, junto a «Cobrar»', async () => {
        await crear();

        expect(boton('Recargo')).toBeDefined();
        expect(boton('Descuento')).toBeDefined();
        expect(boton('Cobrar')).toBeDefined();
      });

      it('el Vendedor no ve ni «Recargo» ni «Descuento» y no se hace ningún request de ajustes', async () => {
        await crear({ dueno: false });

        expect(boton('Recargo')).toBeUndefined();
        expect(boton('Descuento')).toBeUndefined();
        expect(cobranza.ajustar).not.toHaveBeenCalled();
        expect(cobranza.pendientes).not.toHaveBeenCalled();
      });

      it('mientras la cuenta no cargó no se pueden abrir', async () => {
        await crear({ pendientes: () => throwError(() => errorHttp(500, null)) });

        expect(boton('Recargo')!.disabled).toBe(true);
        expect(boton('Descuento')!.disabled).toBe(true);
      });
    });

    describe('pendientes con Recargos (S3)', () => {
      it('un Recargo figura en la tabla con su referencia, aunque no tenga comprobanteId', async () => {
        const aviso = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
        const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
        // Al cobrar se vuelven a pedir los pendientes: la lista cambia de orden y Angular compara las claves de las filas.
        let llamada = 0;
        const despues: PendientesCuenta = { saldo: 250, aCuenta: 0, comprobantes: [recargoPartida(4, 50), recargoPartida(3, 200)] };
        await crear({
          pendientes: () =>
            of(++llamada === 1 ? { ...conRecargo, comprobantes: [...conRecargo.comprobantes, recargoPartida(4, 50)] } : despues),
        });

        const referencias = () => Array.from(el().querySelectorAll('table.pendientes tbody th')).map((t) => limpio(t.textContent));
        expect(referencias()).toEqual(['Venta N.º 7', 'Recargo N.º 3', 'Venta N.º 8', 'Recargo N.º 4']);

        await cobrar('800');

        expect(referencias()).toEqual(['Recargo N.º 4', 'Recargo N.º 3']);
        const llamados = [...aviso.mock.calls, ...error.mock.calls].map((c) => String(c[0]));
        expect(llamados.filter((l) => l.includes('NG0955'))).toEqual([]);
        aviso.mockRestore();
        error.mockRestore();
      });

      it('el cobro imputa FIFO también al Recargo: lo manda con ajusteId y a las ventas con comprobanteId', async () => {
        await crear({ pendientes: () => of(conRecargo) });

        await cobrar('600');

        expect(cobranza.cobrar).toHaveBeenCalledWith({
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
    });

    describe('Recargo', () => {
      it('«Recargo» abre el diálogo del Recargo y otro clic o F4 no abren otro', async () => {
        await crear();

        await pulsar('Recargo');

        expect(dialogoAjuste()).not.toBeNull();
        expect(el().querySelector('h2#dialogo-ajuste-titulo')!.textContent).toContain('Recargo a Ana Gómez');
        await f4();
        expect(dialogo()).toBeNull();
        await pulsar('Descuento');
        expect(el().querySelectorAll('app-dialogo-ajuste')).toHaveLength(1);
      });

      it('con un cobro en curso no se abre un ajuste', async () => {
        await crear();
        await f4();

        boton('Recargo')!.click();
        await refrescar();

        expect(dialogoAjuste()).toBeNull();
      });

      it('confirma con el efecto en el saldo y manda clave, tipo, importe y motivo (sin imputaciones)', async () => {
        await crear();

        await pulsar('Recargo');
        await escribirEn(importeAj(), '500');
        await escribirEn(motivoAj(), 'Deuda vieja del cliente');
        await pulsar('Continuar');

        expect(texto('.efecto-saldo')).toBe('El saldo pasa de $ 1.200,00 a $ 1.700,00.');
        expect(cobranza.ajustar).not.toHaveBeenCalled();

        await pulsar('Confirmar recargo');

        expect(cobranza.ajustar).toHaveBeenCalledTimes(1);
        expect(cobranza.ajustar).toHaveBeenCalledWith(8, {
          clave: claves[0],
          tipo: 'RECARGO',
          importe: 500,
          motivo: 'Deuda vieja del cliente',
        });
        expect(claves[0]).toMatch(/^[0-9a-f-]{36}$/);
      });

      it('al registrarse cierra el diálogo, avisa y recarga saldo, pendientes y movimientos', async () => {
        let llamada = 0;
        const despues: PendientesCuenta = { saldo: 1700, aCuenta: 0, comprobantes: [...con3.comprobantes, recargoPartida(3, 500)] };
        await crear({ pendientes: () => of(++llamada === 1 ? con3 : despues) });
        expect(extractos.extracto).toHaveBeenCalledTimes(1);

        await ajustarCon('Recargo', '500');

        expect(dialogoAjuste()).toBeNull();
        expect(texto('.alerta.exito span')).toBe('Recargo N.º 3 registrado por $ 500,00. El saldo ahora es $ 1.700,00.');
        expect(cobranza.pendientes).toHaveBeenCalledTimes(2);
        expect(texto('.saldo strong')).toBe('$ 1.700,00');
        expect(el().querySelectorAll('table.pendientes tbody tr')).toHaveLength(4);
        expect(extractos.extracto).toHaveBeenCalledTimes(2);
      });

      it('si el saldo queda a favor lo dice «a favor»', async () => {
        await crear({ ajustar: () => of({ ajuste: respuestaAjuste({ saldo: -300 }), repetida: false }) });

        await ajustarCon('Recargo', '500');

        expect(texto('.alerta.exito span')).toContain('El saldo ahora es $ 300,00 a favor.');
      });

      it('un 200 (la misma clave ya estaba registrada) dice que ya estaba registrado', async () => {
        await crear({ ajustar: () => of({ ajuste: respuestaAjuste(), repetida: true }) });

        await ajustarCon('Recargo', '500');

        expect(texto('.alerta.exito span')).toContain('Este ajuste ya estaba registrado: Recargo N.º 3');
      });

      it('cada apertura del diálogo lleva una clave nueva', async () => {
        await crear();

        await ajustarCon('Recargo', '500');
        await ajustarCon('Recargo', '600');

        expect(claves).toHaveLength(2);
        expect(cobranza.ajustar.mock.calls.map((c) => c[1].clave)).toEqual([claves[0], claves[1]]);
      });

      it('un cobro nuevo saca el aviso del ajuste anterior', async () => {
        await crear();
        await ajustarCon('Recargo', '500');
        expect(texto('.alerta.exito span')).toContain('Recargo N.º 3 registrado');

        await f4();

        expect(texto('.alerta.exito span')).toBeNull();
      });
    });

    describe('Descuento', () => {
      it('imputa FIFO a ventas y Recargos y manda comprobanteId o ajusteId, con el resto a cuenta', async () => {
        await crear({
          pendientes: () => of(conRecargo),
          ajustar: () => of({ ajuste: respuestaAjuste({ tipo: 'DESCUENTO', referencia: 'Descuento N.º 4', importe: 600, saldo: 400 }), repetida: false }),
        });

        await pulsar('Descuento');
        await escribirEn(importeAj(), '600');
        await escribirEn(motivoAj(), 'Condonación acordada');
        await pulsar('Continuar');

        expect(texto('.efecto-saldo')).toBe('El saldo pasa de $ 1.000,00 a $ 400,00.');
        await pulsar('Confirmar descuento');

        expect(cobranza.ajustar).toHaveBeenCalledWith(8, {
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
    });

    describe('rechazos', () => {
      it('409 IMPUTACION_EXCEDE_DEUDA: el diálogo sigue, vuelve a los datos con las deudas actuales, avisa y la clave cambia', async () => {
        const despues: PendientesCuenta = { saldo: 800, aCuenta: 0, comprobantes: [comprobante(32, 8, 500), recargoPartida(3, 300)] };
        let llamada = 0;
        await crear({
          pendientes: () => of(++llamada === 1 ? conRecargo : despues),
          ajustar: () =>
            throwError(() =>
              rechazo(409, 'IMPUTACION_EXCEDE_DEUDA', 'La deuda de alguna partida cambió.', [
                { comprobanteId: 31, ajusteId: null, referencia: 'Venta N.º 7', pendiente: 0 },
              ]),
            ),
        });

        await pulsar('Descuento');
        await escribirEn(importeAj(), '600');
        await escribirEn(motivoAj(), 'Condonación acordada');
        await pulsar('Continuar');
        await pulsar('Confirmar descuento');

        expect(dialogoAjuste()).not.toBeNull();
        expect(cobranza.pendientes).toHaveBeenCalledTimes(2);
        expect(texto('.aviso')).toBe('Las deudas cambiaron: revisá la imputación y volvé a confirmar.');
        expect(el().querySelector('.efecto-saldo')).toBeNull();
        expect(
          Array.from(el().querySelectorAll<HTMLInputElement>('.imputacion input')).map((i) => i.value),
        ).toEqual(['500,00', '100,00']);
        expect(importeAj().value).toBe('600');

        await pulsar('Continuar');
        await pulsar('Confirmar descuento');
        expect(cobranza.ajustar.mock.calls[0][1].clave).toBe(claves[0]);
        expect(cobranza.ajustar.mock.calls[1][1].clave).toBe(claves[1]);
      });

      it('409 de clave con otro contenido: muestra el mensaje y el siguiente intento lleva una clave nueva', async () => {
        await crear({
          ajustar: () => throwError(() => rechazo(409, 'CLAVE_CON_OTRO_CONTENIDO', 'Esa clave ya se usó con otro contenido.')),
        });

        await ajustarCon('Recargo', '500');
        expect(texto('.error-servidor')).toBe('Esa clave ya se usó con otro contenido.');

        await pulsar('Confirmar recargo');
        expect(cobranza.ajustar.mock.calls[1][1].clave).toBe(claves[1]);
      });

      it('422 «El cliente está desactivado.» (sin motivo) muestra el mensaje y no cierra el diálogo', async () => {
        await crear({
          ajustar: () => throwError(() => errorHttp(422, { status: 422, message: 'El cliente está desactivado.' })),
        });

        await ajustarCon('Recargo', '500');

        expect(dialogoAjuste()).not.toBeNull();
        expect(texto('.error-servidor')).toBe('El cliente está desactivado.');
        expect(cobranza.ajustar).toHaveBeenCalledTimes(1);
      });

      it('403 muestra el mensaje del servidor', async () => {
        await crear({ ajustar: () => throwError(() => errorHttp(403, { status: 403, message: 'Solo el Dueño puede ajustar.' })) });

        await ajustarCon('Recargo', '500');

        expect(texto('.error-servidor')).toBe('Solo el Dueño puede ajustar.');
      });

      it('400: los errores van junto a cada campo y el siguiente intento lleva una clave nueva', async () => {
        await crear({
          ajustar: () =>
            throwError(() =>
              errorHttp(400, {
                status: 400,
                message: 'Hay datos inválidos',
                errors: { importe: 'El importe admite hasta 2 decimales.', motivo: 'El motivo es obligatorio.', imputaciones: 'No corresponde.' },
              }),
            ),
        });

        await ajustarCon('Recargo', '500');

        expect(el().querySelector('.efecto-saldo')).toBeNull();
        expect(texto('#error-importe')).toBe('El importe admite hasta 2 decimales.');
        expect(texto('#error-motivo')).toBe('El motivo es obligatorio.');
        expect(texto('.error-servidor')).toBe('No corresponde.');

        await pulsar('Continuar');
        await pulsar('Confirmar recargo');
        expect(cobranza.ajustar.mock.calls[1][1].clave).toBe(claves[1]);
      });

      it('red o 5xx: mensaje de reintento seguro, contenido congelado y el reintento usa la MISMA clave', async () => {
        let intento = 0;
        await crear({
          ajustar: () => (++intento === 1 ? throwError(() => errorHttp(0, null)) : of({ ajuste: respuestaAjuste(), repetida: true })),
        });

        await ajustarCon('Recargo', '500');
        expect(texto('.error-servidor')).toContain('No se pudo registrar el ajuste');
        expect(texto('.error-servidor')).toContain('no se carga dos veces');
        expect(texto('.aviso-congelado')).not.toBeNull();
        expect(dialogoAjuste()).not.toBeNull();

        await pulsar('Confirmar recargo');

        expect(cobranza.ajustar.mock.calls.map((c) => c[1].clave)).toEqual([claves[0], claves[0]]);
        expect(claves).toHaveLength(1);
        expect(dialogoAjuste()).toBeNull();
        expect(texto('.alerta.exito span')).toContain('Este ajuste ya estaba registrado');
      });

      it('«Cancelar y empezar de nuevo» suelta el envío sin confirmar con una clave nueva', async () => {
        await crear({ ajustar: () => throwError(() => errorHttp(0, null)) });
        await ajustarCon('Recargo', '500');

        await pulsar('Cancelar y empezar de nuevo');
        expect(el().querySelector('.aviso-congelado')).toBeNull();
        await pulsar('Confirmar recargo');

        expect(cobranza.ajustar.mock.calls.map((c) => c[1].clave)).toEqual([claves[0], claves[1]]);
      });

      it('un error del servidor se limpia al reabrir el diálogo', async () => {
        await crear({ ajustar: () => throwError(() => errorHttp(422, { status: 422, message: 'El cliente está desactivado.' })) });
        await ajustarCon('Recargo', '500');

        boton('Cancelar')!.click();
        await refrescar();
        await pulsar('Recargo');

        expect(el().querySelector('.error-servidor')).toBeNull();
      });
    });
  });
});
