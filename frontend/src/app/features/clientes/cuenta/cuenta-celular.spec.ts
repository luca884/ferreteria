import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
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
 * movil-sectores T12a (S1, S2, S5): la cuenta corriente de un cliente en el celular (saldo en tarjeta, «Cobrar», «Recargo» y
 * «Descuento» en una fila debajo del saldo, pendientes y movimientos en tarjetas). El mismo componente, estado y pedidos
 * que en la tablet y el escritorio. Los diálogos de cobro, ajuste y anulación son los de hoy (12b). Vitest no lee
 * `styles.css`: las tarjetas se miden en el navegador a 390 px.
 */
describe('Cuenta corriente en el celular (T12a)', () => {
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
  const con3: PendientesCuenta = {
    saldo: 1200,
    aCuenta: 0,
    comprobantes: [comprobante(31, 7, 300), comprobante(32, 8, 500), comprobante(33, 9, 400)],
  };
  const recibo = (): Recibo => ({
    id: 5,
    numero: 12,
    fecha: '2026-10-19T10:30:00',
    clienteId: 8,
    clienteNombre: 'Ana Gómez',
    medio: 'EFECTIVO',
    total: 600,
    aCuenta: 0,
    cobradoPor: 'Dueño Tornillo',
    imputaciones: [{ comprobanteId: 31, referencia: 'Venta N.º 7', importe: 300 }],
    estado: 'EMITIDO',
    fechaAnulacion: null,
    anuladoPor: null,
    motivoAnulacion: null,
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
  const respuestaAjuste = (): AjusteResponse => ({
    ajusteId: 3,
    numero: 3,
    referencia: 'Recargo N.º 3',
    tipo: 'RECARGO',
    importe: 500,
    motivo: 'Deuda vieja del cliente',
    fecha: '2026-10-23T18:00:00',
    saldo: 1700,
    imputaciones: [],
  });

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
    cliente?: Cliente;
  }

  async function crear(modo: Modo, { dueno = true, pendientes, cobrar, cliente = ana }: Opciones = {}) {
    simular(modo);
    clientes = { obtener: vi.fn(() => of(cliente)) };
    extractos = {
      extracto: vi.fn(() => of(extractoBase([lineaCobro]))),
      paraImprimir: vi.fn(() => of(extractoBase([lineaCobro]))),
    };
    cobranza = {
      pendientes: vi.fn(pendientes ?? (() => of(con3))),
      cobrar: vi.fn(cobrar ?? (() => of({ recibo: recibo(), repetida: false }))),
      recibo: vi.fn(() => of(recibo())),
      anular: vi.fn(() => of({ ...recibo(), estado: 'ANULADO' as const })),
      ajustar: vi.fn((): Observable<AjusteCobrado> => of({ ajuste: respuestaAjuste(), repetida: false })),
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
  const texto = (selector: string) => limpio(el().querySelector(selector)?.textContent) || undefined;
  const barra = (selector: string) => el().querySelector<HTMLElement>(`app-barra-acciones ${selector}`);
  const acciones = () => el().querySelector<HTMLElement>('.acciones-cuenta');
  const total = () => el().querySelector<HTMLInputElement>('#total-cobranza')!;

  /** «Cobrar», «Recargo» o «Descuento»: en el celular están en la fila de debajo del saldo; en la tablet, en la barra. */
  const accion = (nombre: 'Cobrar' | 'Recargo' | 'Descuento', modo: Modo) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>(modo === 'celular' ? '.acciones-cuenta button' : 'app-barra-acciones button')).find(
      (b) => limpio(b.textContent) === nombre,
    );
  async function pulsar(boton: HTMLElement | null | undefined) {
    boton!.click();
    await refrescar();
  }
  async function escribirEn(campo: HTMLInputElement, valor: string) {
    campo.value = valor;
    campo.dispatchEvent(new Event('input', { bubbles: true }));
    await refrescar();
  }
  async function cobrarConEnter(modo: Modo, importe: string) {
    await pulsar(accion('Cobrar', modo));
    await escribirEn(total(), importe);
    total().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true, bubbles: true }));
    await refrescar();
  }
  async function f4() {
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F4', cancelable: true, bubbles: true }));
    await refrescar();
  }

  const MODOS: Modo[] = ['celular', 'tableta'];

  describe.each(MODOS)('lo que no cambia con el celular: pedidos, textos y reglas (%s)', (modo) => {
    it('pide el cliente y los pendientes del id de la ruta, y el saldo y los pendientes dicen lo mismo', async () => {
      await crear(modo);

      expect(clientes.obtener).toHaveBeenCalledExactlyOnceWith(8);
      expect(cobranza.pendientes).toHaveBeenCalledExactlyOnceWith(8);
      expect(texto('.cuenta h2')).toBe('Ana Gómez');
      expect(texto('.cuenta .documento')).toBe('DNI 12.345.678');
      expect(texto('.lineas .saldo')).toBe('Saldo $ 1.200,00');
      const filas = Array.from(el().querySelectorAll('table.pendientes tbody tr')).map((tr) =>
        Array.from(tr.children).map((c) => limpio(c.textContent)),
      );
      expect(filas.map((f) => [f[0], f[2], f[3], f[4]])).toEqual([
        ['Venta N.º 7', '$ 300,00', '$ 0,00', '$ 300,00'],
        ['Venta N.º 8', '$ 500,00', '$ 0,00', '$ 500,00'],
        ['Venta N.º 9', '$ 400,00', '$ 0,00', '$ 400,00'],
      ]);
    });

    it('«A cuenta (sin imputar)» y «No tiene comprobantes con deuda.» salen con el mismo texto', async () => {
      await crear(modo, { pendientes: () => of({ saldo: -5000, aCuenta: 5000, comprobantes: [] }) });

      expect(texto('.lineas .saldo')).toBe('Saldo $ 5.000,00 a favor');
      expect(texto('.lineas .a-cuenta-actual')).toBe('A cuenta (sin imputar) $ 5.000,00');
      expect(texto('.sin-pendientes')).toBe('No tiene comprobantes con deuda.');
    });

    it('un cliente desactivado muestra su etiqueta', async () => {
      await crear(modo, { cliente: { ...ana, activo: false } });

      expect(texto('.cuenta h2 .etiqueta')).toBe('Desactivado');
    });

    it('«Cobrar» abre el cobro y manda clave, cliente, medio, total e imputaciones FIFO', async () => {
      await crear(modo);

      await cobrarConEnter(modo, '600');

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
      expect(el().querySelector('app-dialogo-cobranza')).toBeNull();
      expect(texto('.alerta.exito span')).toBe('Cobro registrado: Recibo N.º 12 por $ 600,00.');
    });

    it('un cobro que falla por la red deja el diálogo abierto con el texto de siempre y el saldo intacto', async () => {
      await crear(modo, { cobrar: () => throwError(() => new HttpErrorResponse({ status: 0 })) });

      await cobrarConEnter(modo, '600');

      expect(el().querySelector('app-dialogo-cobranza')).not.toBeNull();
      expect(limpio(el().querySelector('app-dialogo-cobranza [role="alert"]')?.textContent)).toContain(
        'No se pudo registrar el cobro: no hay conexión o el servidor no respondió. Reintentá: no se cobra dos veces.',
      );
      expect(texto('.lineas .saldo')).toBe('Saldo $ 1.200,00');
      expect(cobranza.pendientes).toHaveBeenCalledTimes(1);
    });

    it('«Recargo» y «Descuento» abren su diálogo con el título de siempre y mandan el ajuste', async () => {
      await crear(modo);

      await pulsar(accion('Descuento', modo));
      expect(texto('h2#dialogo-ajuste-titulo')).toBe('Descuento a Ana Gómez');
      await pulsar(el().querySelector<HTMLButtonElement>('app-dialogo-ajuste button.cancelar'));
      expect(el().querySelector('app-dialogo-ajuste')).toBeNull();

      await pulsar(accion('Recargo', modo));
      expect(texto('h2#dialogo-ajuste-titulo')).toBe('Recargo a Ana Gómez');
      await escribirEn(el().querySelector<HTMLInputElement>('#importe-ajuste')!, '500');
      await escribirEn(el().querySelector<HTMLInputElement>('#motivo-ajuste')!, 'Deuda vieja del cliente');
      await pulsar(Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => limpio(b.textContent) === 'Continuar'));
      await pulsar(Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => limpio(b.textContent) === 'Confirmar recargo'));

      expect(cobranza.ajustar).toHaveBeenCalledExactlyOnceWith(8, {
        clave: claves[1],
        tipo: 'RECARGO',
        importe: 500,
        motivo: 'Deuda vieja del cliente',
      });
    });

    it('F4 abre el cobro', async () => {
      await crear(modo);

      await f4();

      expect(el().querySelector('app-dialogo-cobranza')).not.toBeNull();
    });

    it('«Anular» de un cobro del extracto trae el recibo y abre el diálogo de anulación (12b lo conserva como hoy)', async () => {
      await crear(modo);

      await pulsar(el().querySelector('app-movimientos-cuenta button.anular'));

      expect(cobranza.recibo).toHaveBeenCalledExactlyOnceWith(5);
      expect(texto('app-dialogo-anulacion h2')).toBe('Anular recibo N.º 12');
      expect(cobranza.anular).not.toHaveBeenCalled();
    });

    it('con el cliente desactivado, «Anular» sigue apagado y explica por qué', async () => {
      await crear(modo, { cliente: { ...ana, activo: false } });

      const anular = el().querySelector<HTMLButtonElement>('app-movimientos-cuenta button.anular')!;
      expect(anular.getAttribute('aria-disabled')).toBe('true');
      expect(texto('app-movimientos-cuenta .aviso-inactivo')).toBe('Reactivá el cliente para anular');
    });

    it('el extracto se pide con el cliente y la página 0, y dice lo mismo (totales, referencia y usuario)', async () => {
      await crear(modo);

      expect(extractos.extracto.mock.calls[0][0]).toBe(8);
      expect(extractos.extracto.mock.calls[0][2]).toBe(0);
      expect(
        Array.from(el().querySelectorAll('.encabezado-periodo > div')).map((d) => `${limpio(d.querySelector('dt')?.textContent)}: ${limpio(d.querySelector('dd')?.textContent)}`),
      ).toEqual(['Saldo inicial: $ 0,00', 'Total debe: $ 1.200,00', 'Total haber: $ 0,00', 'Saldo final: $ 1.200,00']);
      expect(texto('table.movimientos tbody tr td:nth-child(3)')).toBe('Recibo N.º 12');
      expect(texto('table.movimientos tbody tr td:nth-child(7)')).toBe('Ana');
    });

    it('si la cuenta no se pudo cargar avisa y «Reintentar» la vuelve a pedir; no hay acciones de cobro', async () => {
      let llamada = 0;
      await crear(modo, {
        pendientes: () => (++llamada === 1 ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(con3)),
      });
      expect(texto('[role="alert"]')).toContain('No se pudo cargar la cuenta del cliente.');
      expect(acciones()).toBeNull();

      await pulsar(el().querySelector('.reintentar'));

      expect(texto('.lineas .saldo')).toBe('Saldo $ 1.200,00');
    });

    it('el Vendedor ve el aviso de siempre, no se hace ningún request y no hay Cobrar, Recargo ni Descuento ni F4', async () => {
      await crear(modo, { dueno: false });
      await f4();

      expect(texto('[role="alert"], .alerta')).toBe('Solo el Dueño ve y cobra la cuenta corriente.');
      expect(clientes.obtener).not.toHaveBeenCalled();
      expect(cobranza.pendientes).not.toHaveBeenCalled();
      expect(extractos.extracto).not.toHaveBeenCalled();
      expect(el().querySelector('app-dialogo-cobranza')).toBeNull();
      expect(
        Array.from(el().querySelectorAll('button')).filter((b) => ['Cobrar', 'Recargo', 'Descuento'].includes(limpio(b.textContent))),
      ).toHaveLength(0);
      expect(barra('a.volver')!.getAttribute('href')).toBe('/clientes');
    });
  });

  describe('en el celular', () => {
    it('la barra queda con «Volver a Clientes» y sin los botones de cobro', async () => {
      await crear('celular');

      expect(texto('app-barra-acciones a.volver')).toBe('Volver a Clientes');
      expect(el().querySelectorAll('app-barra-acciones button')).toHaveLength(0);
    });

    it('«Cobrar», «Recargo» y «Descuento» van juntos en una fila debajo del saldo, con «Cobrar» primero y como principal', async () => {
      await crear('celular');

      const fila = acciones()!;
      expect(Array.from(fila.querySelectorAll('button')).map((b) => limpio(b.textContent))).toEqual(['Cobrar', 'Recargo', 'Descuento']);
      expect(fila.querySelector('button.cobrar-f4')!.getAttribute('aria-keyshortcuts')).toBe('F4');
      expect(fila.querySelector('button.cobrar-f4')!.classList).toContain('primario');
      const saldo = el().querySelector('section.cuenta')!;
      const pendientes = el().querySelector('table.pendientes')!;
      expect(saldo.compareDocumentPosition(fila) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(fila.compareDocumentPosition(pendientes) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('sin comprobantes con deuda la fila de acciones sigue ahí (el adelanto también se cobra)', async () => {
      await crear('celular', { pendientes: () => of({ saldo: 0, aCuenta: 0, comprobantes: [] }) });

      expect(acciones()).not.toBeNull();
    });

    it('el Vendedor no tiene la fila de acciones pero sí «Volver a Clientes»', async () => {
      await crear('celular', { dueno: false });

      expect(acciones()).toBeNull();
      expect(texto('app-barra-acciones a.volver')).toBe('Volver a Clientes');
    });
  });

  describe('escritorio y tablet: la barra de siempre', () => {
    it('los botones están en la barra, Recargo, Descuento y Cobrar, y no hay fila de acciones', async () => {
      await crear('tableta');

      expect(Array.from(el().querySelectorAll('app-barra-acciones button')).map((b) => limpio(b.textContent))).toEqual([
        'Recargo',
        'Descuento',
        'Cobrar',
      ]);
      expect(acciones()).toBeNull();
      expect(barra('.cobrar-f4')!.getAttribute('aria-keyshortcuts')).toBe('F4');
    });
  });
});
