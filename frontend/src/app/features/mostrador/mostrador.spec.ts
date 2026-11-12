import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpErrorResponse } from '@angular/common/http';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Articulo } from '../articulos/articulo.models';
import { ArticulosService } from '../articulos/articulos.service';
import { CajaVigente } from '../caja/caja.models';
import { CajaService } from '../caja/caja.service';
import { FormaPago } from '../formas-pago/forma-pago.models';
import { FormasPagoService } from '../formas-pago/formas-pago.service';
import { PageResponse } from '../../core/api/page.models';
import { Cliente } from '../clientes/cliente.models';
import { ClientesService } from '../clientes/clientes.service';
import { EmitirPresupuestoRequest, PresupuestoEmitido } from '../presupuestos/presupuesto.models';
import { PresupuestosService } from '../presupuestos/presupuestos.service';
import { EmitirRemitoRequest, RemitoEmitido } from '../remitos/remito.models';
import { RemitosService } from '../remitos/remitos.service';
import { ArticuloMostrador, BusquedaMostrador } from './mostrador.models';
import { Mostrador } from './mostrador';
import { MostradorService } from './mostrador.service';
import { GENERAR_CLAVE } from './ticket.store';
import { VentaService } from './venta.service';
import { ConfirmarVentaRequest, FacturacionDelMostrador, ProvisoriaAutorizada, VentaCobrada } from './venta.models';
import { MARCA_SIMULADO, documentoB, documentoX, fiscalDe } from '../../shared/factura/factura.testing';
import { FiscalVenta } from '../../shared/factura/factura.models';
import { EmisorService } from '../../shared/documento-no-fiscal/emisor.service';
import { emisorDePrueba } from '../../shared/documento-no-fiscal/documento-no-fiscal.testing';
import { ConfiguracionFacturacion, CredencialArca } from '../facturacion/facturacion.models';
import { FacturacionService } from '../facturacion/facturacion.service';
import { VentaConsultada } from '../ventas/ventas.models';
import { VentasService } from '../ventas/ventas.service';
import { ventaDePrueba } from '../ventas/ventas.testing';
import { AvisosFacturacion, INTERVALO_AVISOS_MS } from './avisos-facturacion.models';
import { AvisosFacturacionService } from './avisos-facturacion.service';

registerLocaleData(localeEsAr);

const formaDe = (id: number, nombre: string, medio: FormaPago['medio'], recargoPorcentaje = 0): FormaPago => ({
  id,
  nombre,
  medio,
  recargoPorcentaje,
  descuentoPorcentaje: 0,
  activa: true,
  orden: id,
});

/** Las formas de siempre, sin recargo: Efectivo 1, Débito 2, Crédito 3, Transferencia 4. */
const FORMAS_BASE: FormaPago[] = [
  formaDe(1, 'Efectivo', 'EFECTIVO'),
  formaDe(2, 'Débito', 'DEBITO'),
  formaDe(3, 'Crédito', 'CREDITO'),
  formaDe(4, 'Transferencia', 'TRANSFERENCIA'),
];

const CLIENTE_ANA: Cliente = {
  id: 1,
  razonSocial: 'Ana Gómez',
  tipoDocumento: 'DNI',
  numeroDocumento: '12345678',
  condicionIva: 'CONSUMIDOR_FINAL',
  activo: true,
  cuentaCorrienteHabilitada: false,
};
const CLIENTE_NORTE: Cliente = {
  id: 2,
  razonSocial: 'Ferretería Norte SRL',
  tipoDocumento: 'CUIT',
  numeroDocumento: '30712345671',
  condicionIva: 'RESPONSABLE_INSCRIPTO',
  activo: true,
  cuentaCorrienteHabilitada: false,
};

describe('Mostrador', () => {
  const articulo = (id: number, cambios: Partial<ArticuloMostrador> = {}): ArticuloMostrador => ({
    id,
    codigoInterno: `COD-${id}`,
    codigoBarras: null,
    codigoProveedor: null,
    descripcion: `Artículo ${id}`,
    precioFinal: 170,
    unidad: 'UNIDAD',
    stockActual: 10,
    stockMinimo: 5,
    ubicacion: 'Pasillo 1',
    activo: true,
    ...cambios,
  });

  const lista = (items: ArticuloMostrador[], hayMas = false): BusquedaMostrador => ({
    tipo: 'LISTA',
    items,
    hayMas,
  });

  /** Una llamada al servicio falso: la respuesta la da el test cuando quiere. */
  interface Llamada {
    q: string;
    respuesta: Subject<BusquedaMostrador>;
  }

  /** Un POST de venta al servicio falso: la respuesta la da el test cuando quiere. */
  interface Cobro {
    pedido: ConfirmarVentaRequest;
    respuesta: Subject<VentaCobrada>;
  }

  /** Un POST de presupuesto al servicio falso: la respuesta la da el test cuando quiere. */
  interface Guardado {
    pedido: EmitirPresupuestoRequest;
    respuesta: Subject<PresupuestoEmitido>;
  }

  /** Un POST de remito al servicio falso: la respuesta la da el test cuando quiere. */
  interface Emision {
    pedido: EmitirRemitoRequest;
    respuesta: Subject<RemitoEmitido>;
  }

  let fixture: ComponentFixture<Mostrador>;
  let llamadas: Llamada[];
  let cobros: Cobro[];
  let guardados: Guardado[];
  let emitidos: Emision[];
  let claves: number;
  let cajas: { actual: ReturnType<typeof vi.fn> };
  let formasApi: { listar: ReturnType<typeof vi.fn> };
  let clientesApi: {
    buscar: ReturnType<typeof vi.fn>;
    crear: ReturnType<typeof vi.fn>;
    cuenta: ReturnType<typeof vi.fn>;
    obtener: ReturnType<typeof vi.fn>;
  };
  /** C3: qué comprobante sale al cobrar. Por defecto la ferretería no factura, como antes de la fase 5; null: la lectura falla. */
  let facturacionDelServidor: FacturacionDelMostrador | null;
  /** T11: el estado HTTP con que falla `GET /api/clientes/{id}`; null lo responde con el cliente. */
  let falloLeerCliente: number | null;
  /** Si un test la arma, la lectura del cliente del ticket responde cuando él lo decide (una respuesta lenta). */
  let respuestaClienteLenta: Subject<Cliente> | null;
  let leerFacturacion: ReturnType<typeof vi.fn>;
  /** S12: la configuración de ARCA del Dueño. Por defecto sin certificado; null: la lectura falla. */
  let configuracionArca: ConfiguracionFacturacion | null;
  let leerConfiguracionArca: ReturnType<typeof vi.fn>;
  /** C4: los avisos de facturación del Dueño. Por defecto no hay nada para revisar. */
  let avisosDelServidor: AvisosFacturacion;
  let leerAvisos: ReturnType<typeof vi.fn>;
  /** C7: las ventas de la caja abierta a las que les llegó el CAE. Por defecto ninguna. */
  let provisorias: ProvisoriaAutorizada[];
  let leerProvisorias: ReturnType<typeof vi.fn>;
  /** La hoja fiscal que se reimprime cuando llega el CAE (GET /ventas/{id}). */
  let obtenerHoja: ReturnType<typeof vi.fn>;
  /** Los datos de la ferretería para la hoja del presupuesto o del remito recién guardado (informacion-y-links, S1). */
  let leerEmisor: ReturnType<typeof vi.fn>;

  /** CU-10: por defecto la caja está abierta hoy, como la encuentra el vendedor al empezar. */
  const cajaAbierta: CajaVigente = {
    estado: 'ABIERTA',
    caja: { id: 4, estado: 'ABIERTA', fechaApertura: '2026-10-16T08:00:00', abiertaPor: 'Ana', efectivoInicial: 0 },
    efectivoPropuesto: 0,
    hayCierreAnterior: true,
  };

  async function crear(
    vigente: CajaVigente = cajaAbierta,
    formas: FormaPago[] = FORMAS_BASE,
    rol: 'DUENO' | 'VENDEDOR' = 'VENDEDOR',
    topeDescuento: number | null = null,
    totalArticulos = 1,
  ) {
    // jsdom no implementa scrollIntoView.
    Element.prototype.scrollIntoView =
      vi.fn() as unknown as typeof Element.prototype.scrollIntoView;
    llamadas = [];
    cobros = [];
    guardados = [];
    emitidos = [];
    claves = 0;
    const servicio = {
      buscar: vi.fn((q: string) => {
        const respuesta = new Subject<BusquedaMostrador>();
        llamadas.push({ q, respuesta });
        return respuesta;
      }),
    };
    leerFacturacion = vi.fn(() =>
      facturacionDelServidor ? of(facturacionDelServidor) : throwError(() => new HttpErrorResponse({ status: 500 })),
    );
    leerConfiguracionArca = vi.fn(() =>
      configuracionArca ? of(configuracionArca) : throwError(() => new HttpErrorResponse({ status: 500 })),
    );
    leerAvisos = vi.fn(() => of(avisosDelServidor));
    leerProvisorias = vi.fn(() => of(provisorias));
    leerEmisor = vi.fn(() => of(emisorDePrueba()));
    obtenerHoja = vi.fn((ventaId: number) =>
      of(ventaDePrueba({ ventaId, numero: ventaId / 10, fiscal: fiscalDe(documentoB({ numero: 45 })) })),
    );
    const ventas = {
      cobrar: vi.fn((pedido: ConfirmarVentaRequest) => {
        const respuesta = new Subject<VentaCobrada>();
        cobros.push({ pedido, respuesta });
        return respuesta;
      }),
      facturacion: leerFacturacion,
      provisoriasAutorizadas: leerProvisorias,
    };
    const presupuestosApi = {
      emitir: vi.fn((pedido: EmitirPresupuestoRequest) => {
        const respuesta = new Subject<PresupuestoEmitido>();
        guardados.push({ pedido, respuesta });
        return respuesta;
      }),
    };
    const remitosApi = {
      emitir: vi.fn((pedido: EmitirRemitoRequest) => {
        const respuesta = new Subject<RemitoEmitido>();
        emitidos.push({ pedido, respuesta });
        return respuesta;
      }),
    };
    cajas = { actual: vi.fn(() => of(vigente)) };
    formasApi = { listar: vi.fn(() => of(formas)) };
    // S5: por defecto la ferretería ya tiene artículos, así que el panel «Primeros pasos» no aparece.
    const articulosApi = {
      buscar: vi.fn(() =>
        of({ items: [], page: 0, size: 1, totalElements: totalArticulos, totalPages: totalArticulos } as PageResponse<Articulo>),
      ),
    };
    clientesApi = {
      buscar: vi.fn(() =>
        of({ items: [CLIENTE_ANA, CLIENTE_NORTE], page: 0, size: 8, totalElements: 2, totalPages: 1 } as PageResponse<Cliente>),
      ),
      crear: vi.fn(),
      // La cuenta corriente del cliente del ticket (CC-017): por defecto sin habilitar; los tests la fijan.
      cuenta: vi.fn(() => of({ habilitada: false })),
      // T11: el cliente de un ticket sin su condición de IVA (viene de un presupuesto o un remito).
      // Un id que el mock no conoce falla con un mensaje claro, no con un TypeError más adelante.
      obtener: vi.fn((id: number) => {
        if (respuestaClienteLenta) return respuestaClienteLenta;
        if (falloLeerCliente !== null) return throwError(() => new HttpErrorResponse({ status: falloLeerCliente! }));
        const cliente = [CLIENTE_ANA, CLIENTE_NORTE].find((c) => c.id === id);
        if (cliente === undefined) throw new Error(`El mock de obtener no conoce el cliente ${id}`);
        return of(cliente);
      }),
    };
    const sesion = signal({
      userId: 7,
      nombre: 'Ana',
      email: 'ana@x.com',
      rol,
      ferreteriaId: 3,
      ferreteriaNombre: 'Tornillo',
      topeDescuento,
    });
    await TestBed.configureTestingModule({
      imports: [Mostrador],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: MostradorService, useValue: servicio },
        { provide: VentaService, useValue: ventas },
        { provide: PresupuestosService, useValue: presupuestosApi },
        { provide: RemitosService, useValue: remitosApi },
        { provide: EmisorService, useValue: { obtener: leerEmisor } },
        { provide: CajaService, useValue: cajas },
        { provide: FormasPagoService, useValue: formasApi },
        { provide: ArticulosService, useValue: articulosApi },
        { provide: ClientesService, useValue: clientesApi },
        { provide: FacturacionService, useValue: { configuracion: leerConfiguracionArca } },
        { provide: AvisosFacturacionService, useValue: { leer: leerAvisos } },
        { provide: VentasService, useValue: { factura: obtenerHoja } },
        { provide: AuthService, useValue: { sesion: sesion.asReadonly() } },
        { provide: GENERAR_CLAVE, useValue: () => `clave-${++claves}` },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Mostrador);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const campo = () => el().querySelector('input')!;
  const filas = () => Array.from(el().querySelectorAll<HTMLElement>('[role="option"]'));

  async function escribir(valor: string) {
    campo().value = valor;
    campo().dispatchEvent(new Event('input'));
    await refrescar();
  }

  async function enter() {
    campo().dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
    );
    await refrescar();
  }

  async function avanzar(ms: number) {
    vi.advanceTimersByTime(ms);
    await refrescar();
  }

  async function responder(indice: number, resultado: BusquedaMostrador) {
    llamadas[indice].respuesta.next(resultado);
    llamadas[indice].respuesta.complete();
    await refrescar();
  }

  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
    facturacionDelServidor = { factura: false, ambiente: null, letras: null };
    falloLeerCliente = null;
    respuestaClienteLenta = null;
    configuracionArca = configuracionConCertificado(null);
    avisosDelServidor = { rechazadas: 0, pendientesViejas: 0, enRevision: 0, bloqueo: null };
    provisorias = [];
  });
  afterEach(() => vi.useRealTimers());

  const configuracionConCertificado = (credencial: CredencialArca | null): ConfiguracionFacturacion => ({
    ambiente: 'HOMOLOGACION',
    puntoVenta: 3,
    puntoVentaEditable: false,
    motivoPuntoVentaTrabado: null,
    facturacionHabilitada: false,
    simulador: false,
    credencial,
    pedido: null,
    avisos: [],
  });
  const certificado = (cambios: Partial<CredencialArca>): CredencialArca => ({
    cuit: '30999999995',
    emisor: 'Computadores Test',
    ambiente: 'HOMOLOGACION',
    vigenteDesde: '2024-11-26',
    vigenteHasta: '2026-11-26',
    huella: 'AB:CD',
    estado: 'VIGENTE',
    diasParaVencer: 29,
    ...cambios,
  });

  const renglonesTicket = () =>
    Array.from(el().querySelectorAll<HTMLElement>('app-ticket-lista .renglon'));

  describe('primeros pasos (S5)', () => {
    const panel = () => el().querySelector('app-primeros-pasos .primeros-pasos');

    it('el Dueño de una ferretería sin artículos lo ve en el Mostrador, con los links', async () => {
      await crear(cajaAbierta, FORMAS_BASE, 'DUENO', null, 0);

      expect(panel()?.querySelector('h2')?.textContent?.trim()).toBe('Primeros pasos');
      expect(Array.from(panel()!.querySelectorAll('a')).map((a) => a.getAttribute('href'))).toEqual([
        '/configuracion/datos-fiscales',
        '/configuracion/facturacion',
        '/configuracion/precios',
        '/importaciones',
        '/articulos/nuevo',
        '/configuracion/formas-pago',
        '/usuarios',
        '/caja',
      ]);
    });

    it('con artículos el Dueño no lo ve', async () => {
      await crear(cajaAbierta, FORMAS_BASE, 'DUENO', null, 12);

      expect(panel()).toBeNull();
    });

    it('el Vendedor no lo ve aunque no haya artículos', async () => {
      await crear(cajaAbierta, FORMAS_BASE, 'VENDEDOR', null, 0);

      expect(panel()).toBeNull();
    });
  });

  describe('vencimiento del certificado de ARCA (S12)', () => {
    const aviso = () => el().querySelector('.aviso-certificado');
    const dicho = () => (aviso()?.textContent ?? '').replace(/\s+/g, ' ').trim();

    it('al Dueño, con el certificado por vencer, le avisa sin frenar nada y lo lleva a Facturación', async () => {
      configuracionArca = configuracionConCertificado(certificado({ estado: 'POR_VENCER' }));
      await crear(cajaAbierta, FORMAS_BASE, 'DUENO');

      expect(leerConfiguracionArca).toHaveBeenCalledTimes(1);
      expect(dicho()).toBe('El certificado de ARCA vence el 26/11/2026: renovalo en Facturación.');
      expect(aviso()!.getAttribute('role')).toBe('status');
      expect(aviso()!.querySelector('a')!.getAttribute('href')).toBe('/configuracion/facturacion');
    });

    it('con el certificado vencido dice que venció', async () => {
      configuracionArca = configuracionConCertificado(
        certificado({ vigenteHasta: '2026-10-20', estado: 'VENCIDA', diasParaVencer: -8 }),
      );
      await crear(cajaAbierta, FORMAS_BASE, 'DUENO');

      expect(dicho()).toBe('El certificado de ARCA venció el 20/10/2026: renovalo en Facturación.');
    });

    it('con el certificado vigente, o si la lectura falla, no avisa nada', async () => {
      configuracionArca = configuracionConCertificado(certificado({ estado: 'VIGENTE', diasParaVencer: 400 }));
      await crear(cajaAbierta, FORMAS_BASE, 'DUENO');
      expect(aviso()).toBeNull();

      TestBed.resetTestingModule();
      configuracionArca = null;
      await crear(cajaAbierta, FORMAS_BASE, 'DUENO');
      expect(leerConfiguracionArca).toHaveBeenCalledTimes(1);
      expect(aviso()).toBeNull();
    });

    it('el Vendedor nunca lo pide', async () => {
      configuracionArca = configuracionConCertificado(certificado({ estado: 'POR_VENCER' }));
      await crear(cajaAbierta, FORMAS_BASE, 'VENDEDOR');

      expect(leerConfiguracionArca).not.toHaveBeenCalled();
      expect(aviso()).toBeNull();
    });
  });

  describe('búsqueda', () => {
    it('tiene el foco en el campo al abrir la pantalla', async () => {
      await crear();

      expect(document.activeElement).toBe(campo());
    });

    it('muestra la botonera con las etiquetas a la vista y las teclas solo en aria-keyshortcuts (R-038)', async () => {
      await crear();

      const botones = Array.from(el().querySelectorAll('app-botonera button')).map((b) =>
        b.textContent!.replace(/\s+/g, ' ').trim(),
      );
      expect(botones).toEqual([
        'Buscar',
        'Cantidad',
        'Descuento',
        'Dto. total',
        'Cobrar',
        'Presupuesto',
        'Remito',
        'Cliente',
        'Quitar',
        'Vaciar',
      ]);
      expect(el().textContent).not.toContain('F2 buscar · Esc limpiar');
    });

    it('la botonera va en la barra de acciones de arriba, con el título de la pantalla', async () => {
      await crear();

      const barra = el().querySelector('app-barra-acciones')!;
      expect(barra).not.toBeNull();
      expect(barra.querySelector('h1')?.textContent?.trim()).toBe('Mostrador');
      expect(barra.querySelector('app-botonera [role="toolbar"]')?.getAttribute('aria-label')).toBe(
        'Acciones del mostrador',
      );
    });

    it('al tipear busca tras 250 ms sin teclear, una sola vez y con el último texto', async () => {
      await crear();

      await escribir('tor');
      await avanzar(100);
      await escribir('torn');
      await avanzar(249);
      expect(llamadas).toHaveLength(0);
      await avanzar(1);

      expect(llamadas.map((l) => l.q)).toEqual(['torn']);
    });

    it('Enter busca de inmediato y cancela el debounce: no hay llamada duplicada', async () => {
      await crear();
      await escribir('7790001');

      await enter();
      expect(llamadas.map((l) => l.q)).toEqual(['7790001']);
      await avanzar(500);

      expect(llamadas).toHaveLength(1);
    });

    it('Enter con el campo vacío o en blanco no busca ni muestra error', async () => {
      await crear();

      await enter();
      await escribir('   ');
      await enter();
      await avanzar(500);

      expect(llamadas).toHaveLength(0);
      expect(el().querySelector('[role="alert"]')).toBeNull();
    });

    it('al borrar todo el texto limpia el resultado sin llamar al backend', async () => {
      await crear();
      await escribir('tor');
      await avanzar(250);
      await responder(0, lista([articulo(1), articulo(2)]));
      expect(filas()).toHaveLength(2);

      await escribir('');
      await avanzar(500);

      expect(filas()).toHaveLength(0);
      expect(llamadas).toHaveLength(1);
    });

    it('borrar el texto cancela la búsqueda pendiente', async () => {
      await crear();
      await escribir('tor');

      await escribir('');
      await avanzar(500);

      expect(llamadas).toHaveLength(0);
    });

    it('descarta una respuesta vieja que llega después de una búsqueda más nueva', async () => {
      await crear();
      await escribir('tor');
      await avanzar(250);
      await escribir('torn');
      await avanzar(250);
      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'torn']);

      await responder(1, lista([articulo(2, { descripcion: 'Tornillo' })]));
      await responder(0, lista([articulo(1, { descripcion: 'Tornado' }), articulo(3)]));

      expect(filas()).toHaveLength(1);
      expect(filas()[0].textContent).toContain('Tornillo');
    });
  });

  describe('escaneo con el lector sobre texto tipeado (R-016, decisión 14)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({
      tipo: 'EXACTO',
      items: [a],
      hayMas: false,
    });

    /** Tipea tecla por tecla (keydown + input) con `gap` ms entre una y otra. */
    async function teclear(texto: string, gap: number) {
      for (const caracter of texto) {
        vi.advanceTimersByTime(gap);
        campo().dispatchEvent(
          new KeyboardEvent('keydown', { key: caracter, bubbles: true, cancelable: true }),
        );
        campo().value += caracter;
        campo().dispatchEvent(new Event('input'));
        await refrescar();
      }
    }

    async function enterTras(gap: number) {
      vi.advanceTimersByTime(gap);
      await enter();
    }

    /** El vendedor tipeó «tor» despacio y ya está la LISTA en pantalla. */
    async function conListaDeTor() {
      await crear();
      await teclear('tor', 150);
      await avanzar(250);
      await responder(0, lista([articulo(1), articulo(2)]));
      expect(filas()).toHaveLength(2);
    }

    it('«tor» tipeado despacio, LISTA, y un escaneo rápido de 7790001 + Enter busca solo 7790001 y muestra la ficha', async () => {
      await conListaDeTor();

      await avanzar(1000);
      await teclear('7790001', 10);
      await enterTras(10);
      expect(llamadas.map((l) => l.q)).toEqual(['tor', '7790001']);
      await responder(1, exacto(articulo(7, { descripcion: 'Tornillo 1/4' })));

      expect(el().querySelector('app-ficha-articulo')!.textContent).toContain('Tornillo 1/4');
      expect(filas()).toHaveLength(0);
      expect(campo().value).toBe('7790001');
      expect(campo().selectionStart).toBe(0);
      expect(campo().selectionEnd).toBe('7790001'.length);
    });

    it('con otro texto previo y otro código también reemplaza (triangulación)', async () => {
      await crear();
      await teclear('martillo', 120);
      await avanzar(300);
      await responder(0, lista([articulo(1)]));

      await avanzar(2000);
      await teclear('TOR-014', 8);
      await enterTras(8);

      expect(llamadas.map((l) => l.q)).toEqual(['martillo', 'TOR-014']);
    });

    it('no hay llamadas intermedias ni duplicadas por el debounce durante la ráfaga', async () => {
      await conListaDeTor();

      await avanzar(1000);
      await teclear('7790001', 10);
      await enterTras(10);
      await avanzar(1000);

      expect(llamadas.map((l) => l.q)).toEqual(['tor', '7790001']);
    });

    it('la respuesta tardía de una búsqueda anterior en vuelo no pisa el resultado del escaneo', async () => {
      await crear();
      await teclear('tor', 150);
      await avanzar(250);
      expect(llamadas.map((l) => l.q)).toEqual(['tor']);

      await avanzar(500);
      await teclear('7790001', 10);
      await enterTras(10);
      await responder(1, exacto(articulo(7, { descripcion: 'Tornillo 1/4' })));
      await responder(0, lista([articulo(1), articulo(2)]));

      expect(llamadas.map((l) => l.q)).toEqual(['tor', '7790001']);
      expect(el().querySelector('app-ficha-articulo')!.textContent).toContain('Tornillo 1/4');
      expect(filas()).toHaveLength(0);
    });

    it('tipeo humano de «7790001» + Enter sobre «tor» no se toca: busca «tor7790001»', async () => {
      await conListaDeTor();

      await avanzar(1000);
      await teclear('7790001', 120);
      await enterTras(120);

      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'tor7790001']);
    });

    it('un escaneo con el campo vacío sigue buscando solo el código', async () => {
      await crear();

      await teclear('7790001', 10);
      await enterTras(10);

      expect(llamadas.map((l) => l.q)).toEqual(['7790001']);
    });
  });

  describe('ficha y lista con teclado', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({
      tipo: 'EXACTO',
      items: [a],
      hayMas: false,
    });

    const flecha = async (key: 'ArrowDown' | 'ArrowUp') => {
      campo().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      await refrescar();
    };

    const resaltada = () => filas().findIndex((f) => f.getAttribute('aria-selected') === 'true');
    const ficha = () => el().querySelector('app-ficha-articulo');

    async function buscarLista(items: ArticuloMostrador[], q = 'tor') {
      await escribir(q);
      await enter();
      await responder(llamadas.length - 1, lista(items));
    }

    it('un escaneo con Enter hace una llamada y muestra la ficha directa para un EXACTO', async () => {
      await crear();
      await escribir('7790001');

      await enter();
      await responder(0, exacto(articulo(1, { descripcion: 'Tornillo 1/4', precioFinal: 1234.5 })));

      expect(llamadas.map((l) => l.q)).toEqual(['7790001']);
      expect(ficha()).not.toBeNull();
      expect(ficha()!.textContent).toContain('Tornillo 1/4');
      expect(ficha()!.textContent).toContain('1.234,50');
      expect(filas()).toHaveLength(0);
      // CU-07: el escaneo con Enter además agrega 1 unidad al ticket.
      expect(renglonesTicket()).toHaveLength(1);
      expect(renglonesTicket()[0].textContent).toContain('Tornillo 1/4');
    });

    it('tras un EXACTO por Enter deja el texto seleccionado para que el próximo escaneo lo reemplace', async () => {
      await crear();
      await escribir('7790001');
      await enter();

      await responder(0, exacto(articulo(1)));

      expect(campo().selectionStart).toBe(0);
      expect(campo().selectionEnd).toBe('7790001'.length);
    });

    it('una LISTA muestra las filas con precio, stock y ubicación y la primera resaltada', async () => {
      await crear();

      await buscarLista([
        articulo(1, {
          precioFinal: 1234.5,
          stockActual: 2.5,
          unidad: 'METRO',
          ubicacion: 'Pasillo 2',
        }),
        articulo(2),
        articulo(3),
      ]);

      expect(filas()).toHaveLength(3);
      expect(resaltada()).toBe(0);
      expect(filas()[0].textContent).toContain('1.234,50');
      expect(filas()[0].textContent).toContain('2,5 metros');
      expect(filas()[0].textContent).toContain('Pasillo 2');
      // La ficha acompaña a la fila resaltada (H-17).
      expect(ficha()).not.toBeNull();
    });

    it('flecha abajo dos veces y Enter abren la ficha de la tercera fila', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2), articulo(3, { descripcion: 'Tercero' })]);

      await flecha('ArrowDown');
      await flecha('ArrowDown');
      expect(resaltada()).toBe(2);
      await enter();

      expect(ficha()!.textContent).toContain('Tercero');
      expect(llamadas).toHaveLength(1);
      expect(filas()).toHaveLength(0);
    });

    it('flecha arriba en la primera fila y flecha abajo en la última se quedan donde están', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)]);

      await flecha('ArrowUp');
      expect(resaltada()).toBe(0);
      await flecha('ArrowDown');
      await flecha('ArrowDown');
      expect(resaltada()).toBe(1);
      await flecha('ArrowUp');

      expect(resaltada()).toBe(0);
    });

    it('con una sola fila (LISTA de uno) la muestra resaltada y Enter abre la ficha', async () => {
      await crear();
      await buscarLista([articulo(7, { descripcion: 'Martillo', ubicacion: 'Estante 3' })]);

      expect(filas()).toHaveLength(1);
      expect(resaltada()).toBe(0);
      expect(filas()[0].textContent).toContain('Estante 3');
      expect(ficha()).not.toBeNull();
      await enter();

      expect(ficha()!.textContent).toContain('Martillo');
    });

    it('si se editó el texto desde la última búsqueda, Enter busca de nuevo en vez de abrir la fila', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)], 'tor');

      await escribir('torn');
      await enter();

      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'torn']);
      // No abrió ninguna fila: sigue la lista (con su resaltada en la ficha) y nada entró al ticket.
      expect(filas()).toHaveLength(2);
      expect(renglonesTicket()).toHaveLength(0);
    });

    it('con la ficha abierta las flechas no mueven nada', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)]);
      await enter();
      expect(ficha()).not.toBeNull();

      await flecha('ArrowDown');
      await flecha('ArrowDown');
      await flecha('ArrowUp');

      expect(ficha()).not.toBeNull();
      expect(filas()).toHaveLength(0);
    });

    it('con la ficha abierta, Enter vuelve a buscar el mismo texto para refrescar el stock', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)]);
      await enter();

      await enter();

      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'tor']);
    });

    it('el foco queda siempre en el campo al buscar, mover y abrir', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)]);
      expect(document.activeElement).toBe(campo());

      await flecha('ArrowDown');
      expect(document.activeElement).toBe(campo());
      await enter();

      expect(document.activeElement).toBe(campo());
    });

    it('un clic en una fila abre su ficha', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2, { descripcion: 'Segundo' })]);

      filas()[1].click();
      await refrescar();

      expect(ficha()!.textContent).toContain('Segundo');
    });
  });

  describe('ticket: Enter y escaneos (CU-07, R-023 a R-026)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({
      tipo: 'EXACTO',
      items: [a],
      hayMas: false,
    });
    const sinResultados: BusquedaMostrador = { tipo: 'SIN_RESULTADOS', items: [], hayMas: false };
    const mensaje = () => el().querySelector('.mensaje')?.textContent?.trim() ?? null;

    const flecha = async (key: 'ArrowDown' | 'ArrowUp') => {
      campo().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      await refrescar();
    };

    /** Escanea `codigo`: lo escribe, Enter, y el servidor responde `resultado`. */
    async function escanear(codigo: string, resultado: BusquedaMostrador) {
      await escribir(codigo);
      await enter();
      await responder(llamadas.length - 1, resultado);
    }

    const descripciones = () =>
      renglonesTicket().map((r) => r.querySelector('.descripcion')!.textContent!.trim());
    const cantidades = () =>
      renglonesTicket().map((r) => r.querySelector('.cantidad')!.textContent!.trim());

    it('«7790001» + Enter deja 1 renglón de 1 unidad', async () => {
      await crear();

      await escanear('7790001', exacto(articulo(1, { descripcion: 'Tornillo' })));

      expect(descripciones()).toEqual(['Tornillo']);
      expect(cantidades()).toEqual(['1']);
    });

    it('un EXACTO que llega por el debounce al tipear no agrega nada al ticket', async () => {
      await crear();

      await escribir('TOR-014');
      await avanzar(250);
      await responder(0, exacto(articulo(1)));

      expect(el().querySelector('app-ficha-articulo')).not.toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
    });

    it('una lista de 3 filas, flecha abajo y Enter agregan 1 unidad de la 2ª fila', async () => {
      await crear();
      await escribir('tor');
      await enter();
      await responder(0, lista([articulo(1), articulo(2, { descripcion: 'Segundo' }), articulo(3)]));

      await flecha('ArrowDown');
      await enter();

      expect(descripciones()).toEqual(['Segundo']);
      expect(cantidades()).toEqual(['1']);
      expect(llamadas).toHaveLength(1);
    });

    it('un clic en una fila de la lista también la agrega al ticket', async () => {
      await crear();
      await escribir('tor');
      await enter();
      await responder(0, lista([articulo(1), articulo(2, { descripcion: 'Segundo' })]));

      filas()[1].click();
      await refrescar();

      expect(descripciones()).toEqual(['Segundo']);
    });

    it('Enter con el debounce pendiente es una sola llamada y un solo renglón', async () => {
      await crear();
      await escribir('7790001');

      await enter();
      await avanzar(500);
      await responder(0, exacto(articulo(1)));
      await avanzar(500);

      expect(llamadas).toHaveLength(1);
      expect(renglonesTicket()).toHaveLength(1);
      expect(cantidades()).toEqual(['1']);
    });

    it('el mismo código 3 veces es 1 renglón de 3 unidades', async () => {
      await crear();

      await escanear('7790001', exacto(articulo(1)));
      await escanear('7790001', exacto(articulo(1)));
      await escanear('7790001', exacto(articulo(1)));

      expect(renglonesTicket()).toHaveLength(1);
      expect(cantidades()).toEqual(['3']);
    });

    it('dos escaneos seguidos (20 ms) quedan en orden [A, B] aunque B esté listo antes', async () => {
      await crear();

      await escribir('A');
      await enter();
      vi.advanceTimersByTime(20);
      await escribir('B');
      await enter();
      // El escaneo de B espera su turno: no se pierde ni cancela al de A.
      expect(llamadas.map((l) => l.q)).toEqual(['A']);
      await responder(0, exacto(articulo(1, { descripcion: 'A' })));
      expect(llamadas.map((l) => l.q)).toEqual(['A', 'B']);
      await responder(1, exacto(articulo(2, { descripcion: 'B' })));

      expect(descripciones()).toEqual(['A', 'B']);
      expect(cantidades()).toEqual(['1', '1']);
    });

    it('5 escaneos seguidos de artículos distintos dan 5 renglones', async () => {
      await crear();
      for (let i = 1; i <= 5; i++) {
        await escribir(`COD${i}`);
        await enter();
      }

      for (let i = 0; i < 5; i++) {
        await responder(i, exacto(articulo(i + 1)));
      }

      expect(llamadas).toHaveLength(5);
      expect(descripciones()).toEqual([1, 2, 3, 4, 5].map((i) => `Artículo ${i}`));
    });

    it('el mismo artículo dos veces seguidas, sin esperar la respuesta, da 1 renglón de 2 unidades', async () => {
      await crear();
      await escribir('7790001');
      await enter();
      await enter();

      await responder(0, exacto(articulo(1)));
      await responder(1, exacto(articulo(1)));

      expect(renglonesTicket()).toHaveLength(1);
      expect(cantidades()).toEqual(['2']);
    });

    it('un escaneo cuya respuesta llega tarde, después de tipear otra cosa, igual se agrega', async () => {
      await crear();
      await escribir('7790001');
      await enter();

      await escribir('torn');
      await responder(0, exacto(articulo(1, { descripcion: 'Tornillo' })));

      expect(descripciones()).toEqual(['Tornillo']);
      // La pantalla es de lo último que se tipeó: la ficha del escaneo viejo no la pisa.
      expect(el().querySelector('app-ficha-articulo')).toBeNull();
    });

    it('un dado de baja elegido de la lista no se agrega y avisa en rojo', async () => {
      await crear();
      await escanear('A', exacto(articulo(1)));

      await escanear('BAJA', lista([articulo(9, { descripcion: 'Taladro viejo', activo: false })]));
      await enter();

      expect(renglonesTicket()).toHaveLength(1);
      const aviso = el().querySelector('.aviso')!;
      expect(aviso.textContent!.trim()).toBe('"Taladro viejo" está dado de baja.');
      expect(aviso.classList.contains('error')).toBe(true);
      expect(aviso.getAttribute('role')).toBe('alert');
    });

    it('un EXACTO dado de baja (no debería llegar) tampoco se agrega', async () => {
      await crear();

      await escanear('BAJA', exacto(articulo(9, { descripcion: 'Taladro viejo', activo: false })));

      expect(renglonesTicket()).toHaveLength(0);
      expect(el().querySelector('.aviso')!.textContent!.trim()).toBe('"Taladro viejo" está dado de baja.');
    });

    it('un artículo a $0 no se agrega y avisa en rojo', async () => {
      await crear();

      await escanear('ARA', lista([articulo(8, { descripcion: 'Arandela suelta', precioFinal: 0 })]));
      await enter();

      expect(renglonesTicket()).toHaveLength(0);
      const aviso = el().querySelector('.aviso')!;
      expect(aviso.textContent!.trim()).toBe('"Arandela suelta" no tiene precio.');
      expect(aviso.classList.contains('error')).toBe(true);
    });

    it('después del aviso de baja, agregar un activo lo saca', async () => {
      await crear();
      await escanear('BAJA', lista([articulo(9, { descripcion: 'Taladro viejo', activo: false })]));
      await enter();

      await escanear('A', exacto(articulo(1)));

      expect(renglonesTicket()).toHaveLength(1);
      expect(el().querySelector('.aviso')).toBeNull();
    });

    it('SIN_RESULTADOS no agrega y muestra el mensaje de no encontrado', async () => {
      await crear();
      await escanear('A', exacto(articulo(1)));

      await escanear('NADA', sinResultados);

      expect(renglonesTicket()).toHaveLength(1);
      expect(mensaje()).toBe('No encontramos ningún artículo con ese código o descripción.');
    });

    it('un error 5xx al escanear no agrega, avisa y el próximo escaneo anda', async () => {
      await crear();
      await escanear('A', exacto(articulo(1)));
      await escribir('B');
      await enter();

      llamadas[1].respuesta.error(new HttpErrorResponse({ status: 500 }));
      await refrescar();
      expect(renglonesTicket()).toHaveLength(1);
      expect(el().querySelector('[role="alert"]')!.textContent).toContain(
        'No se pudo buscar. Probá de nuevo.',
      );

      await escanear('C', exacto(articulo(3)));

      expect(renglonesTicket()).toHaveLength(2);
    });

    it('sin lista a la vista, las flechas mueven el renglón elegido del ticket', async () => {
      await crear();
      await escanear('A', exacto(articulo(1)));
      await escanear('B', exacto(articulo(2)));
      const elegido = () =>
        renglonesTicket().findIndex((r) => r.getAttribute('aria-current') === 'true');
      expect(elegido()).toBe(1);

      await flecha('ArrowUp');
      expect(elegido()).toBe(0);
      await flecha('ArrowUp');
      expect(elegido()).toBe(0);
      await flecha('ArrowDown');

      expect(elegido()).toBe(1);
    });

    it('un renglón sin stock suficiente muestra la advertencia en el ticket', async () => {
      await crear();

      await escanear('A', exacto(articulo(1, { stockActual: 0 })));

      expect(renglonesTicket()[0].textContent).toContain('Sin stock suficiente');
    });

    it('el ticket total aparece en el formato de montos', async () => {
      await crear();

      await escanear('A', exacto(articulo(1, { precioFinal: 1210 })));
      await escanear('A', exacto(articulo(1, { precioFinal: 1210 })));

      expect(el().querySelector('.total')!.textContent!.replace(/\s/g, ' ')).toContain(
        '$ 2.420,00',
      );
    });

    it('debajo del total cuenta renglones (no «artículos»: 2 unidades + 2,5 m no son 3 de nada)', async () => {
      await crear();

      await escanear('A', exacto(articulo(1)));
      await escanear('A', exacto(articulo(1)));
      await escanear('B', exacto(articulo(2)));

      expect(el().querySelector('.conteo')!.textContent!.replace(/\s+/g, ' ').trim()).toBe(
        '2 renglones',
      );
    });
  });

  describe('teclas, diálogos y foco (CU-07, R-028 a R-032, R-038, R-039)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({
      tipo: 'EXACTO',
      items: [a],
      hayMas: false,
    });

    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const dentroDelDialogo = () => !!dialogo()?.contains(document.activeElement);
    const total = () => el().querySelector('.total')!.textContent!.replace(/\s/g, ' ').trim();
    const descripciones = () =>
      renglonesTicket().map((r) => r.querySelector('.descripcion')!.textContent!.trim());
    const cantidades = () =>
      renglonesTicket().map((r) => r.querySelector('.cantidad')!.textContent!.trim());
    const botonera = (etiqueta: string) =>
      Array.from(el().querySelectorAll<HTMLButtonElement>('app-botonera button')).find((b) =>
        b.textContent!.includes(etiqueta),
      )!;
    /** S4: el aviso que dice por qué la acción intentada no se puede usar. */
    const motivoDelBotonera = () =>
      (el().querySelector('.motivo-botonera')?.textContent ?? '').replace(/\s+/g, ' ').trim();
    const sinCaja: CajaVigente = { estado: 'SIN_CAJA', caja: null, efectivoPropuesto: 0, hayCierreAnterior: false };
    const botonDelDialogo = (texto: string) =>
      Array.from(dialogo()!.querySelectorAll<HTMLButtonElement>('button')).find(
        (b) => b.textContent!.trim() === texto,
      )!;

    async function tecla(key: string, destino: HTMLElement = document.activeElement as HTMLElement) {
      const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      destino.dispatchEvent(evento);
      await refrescar();
      return evento;
    }

    async function escanear(codigo: string, a: ArticuloMostrador) {
      await escribir(codigo);
      await enter();
      await responder(llamadas.length - 1, exacto(a));
    }

    /** Ticket con «Artículo 1» ($ 100) y «Artículo 2» ($ 200,50), elegido el último. */
    async function conTicket() {
      await crear();
      await escanear('A', articulo(1, { precioFinal: 100 }));
      await escanear('B', articulo(2, { precioFinal: 200.5, unidad: 'METRO' }));
    }

    async function escribirEnDialogo(valor: string) {
      const input = dialogo()!.querySelector('input')!;
      input.value = valor;
      input.dispatchEvent(new Event('input'));
      await refrescar();
    }

    it('F3 con el ticket vacío no hace nada, pero anula la acción del navegador', async () => {
      await crear();

      const evento = await tecla('F3');

      expect(dialogo()).toBeNull();
      expect(evento.defaultPrevented).toBe(true);
    });

    it('F3 abre la cantidad del último renglón con el foco adentro y la cantidad preseleccionada', async () => {
      await conTicket();

      await tecla('F3');

      expect(dialogo()!.textContent).toContain('Artículo 2');
      expect(dentroDelDialogo()).toBe(true);
      expect(dialogo()!.querySelector('input')!.value).toBe('1');
    });

    it('F3 + 4 + Enter cambia la cantidad y recalcula el total', async () => {
      await conTicket();
      await tecla('F3');

      await escribirEnDialogo('4');
      await tecla('Enter');

      expect(dialogo()).toBeNull();
      expect(cantidades()).toEqual(['1', '4 m']);
      expect(total()).toBe('$ 902,00');
    });

    it('F3 usa el renglón elegido con las flechas, no el último', async () => {
      await conTicket();
      await tecla('ArrowUp', campo());

      await tecla('F3');

      expect(dialogo()!.textContent).toContain('Artículo 1');
    });

    it('un clic en un renglón lo elige: F3 usa ese renglón y el foco vuelve al buscador', async () => {
      await conTicket();
      const primero = fixture.nativeElement.querySelectorAll('.renglon')[0] as HTMLElement;

      primero.click();
      await refrescar();

      expect(primero.classList).toContain('elegido');
      expect(document.activeElement).toBe(campo());
      await tecla('F3');
      expect(dialogo()!.textContent).toContain('Artículo 1');
    });

    it('una cantidad inválida no cierra el diálogo ni cambia el ticket', async () => {
      await conTicket();
      await tecla('F3');

      await escribirEnDialogo('0');
      await tecla('Enter');

      expect(dialogo()).not.toBeNull();
      expect(dialogo()!.textContent).toContain('La cantidad tiene que ser mayor que 0.');
      expect(cantidades()).toEqual(['1', '1 m']);
    });

    it('F3 + Esc cancela sin cambiar la cantidad', async () => {
      await conTicket();
      await tecla('F3');
      await escribirEnDialogo('9');

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(cantidades()).toEqual(['1', '1 m']);
    });

    it('F10 + Enter quita el renglón y baja el total', async () => {
      await conTicket();

      const evento = await tecla('F10');
      expect(evento.defaultPrevented).toBe(true);
      expect(dialogo()!.textContent).toContain('¿Quitar «Artículo 2» del ticket?');
      expect(dentroDelDialogo()).toBe(true);
      await tecla('Enter');

      expect(dialogo()).toBeNull();
      expect(descripciones()).toEqual(['Artículo 1']);
      expect(total()).toBe('$ 100,00');
    });

    it('F10 + Esc no quita nada', async () => {
      await conTicket();
      await tecla('F10');

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(descripciones()).toEqual(['Artículo 1', 'Artículo 2']);
    });

    it('F10 con el ticket vacío no abre nada y anula la acción del navegador', async () => {
      await crear();

      const evento = await tecla('F10');

      expect(dialogo()).toBeNull();
      expect(evento.defaultPrevented).toBe(true);
    });

    it('«Vaciar» pide confirmación con el foco en Cancelar; cancelar no cambia nada', async () => {
      await conTicket();

      botonera('Vaciar').click();
      await refrescar();

      expect(dialogo()!.querySelector('h2')!.textContent).toContain('¿Vaciar el ticket?');
      expect(dialogo()!.textContent).toContain('Se pierden 2 renglones.');
      expect(document.activeElement).toBe(botonDelDialogo('Cancelar'));
      botonDelDialogo('Cancelar').click();
      await refrescar();
      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(2);
    });

    it('confirmar «Vaciar» limpia el ticket y el localStorage y renueva la clave', async () => {
      await conTicket();
      expect(JSON.parse(localStorage.getItem('ticket:3:7')!).clave).toBe('clave-1');

      botonera('Vaciar').click();
      await refrescar();
      botonDelDialogo('Vaciar').click();
      await refrescar();

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
      expect(localStorage.getItem('ticket:3:7')).toBeNull();
      await escanear('C', articulo(3));
      expect(JSON.parse(localStorage.getItem('ticket:3:7')!).clave).toBe('clave-2');
    });

    it('ninguna tecla (Esc, Supr, Backspace, las F) vacía el ticket ni abre «Vaciar»', async () => {
      await conTicket();
      const teclas = ['Escape', 'Delete', 'Backspace', ...Array.from({ length: 12 }, (_, i) => `F${i + 1}`)];

      for (const key of teclas) {
        await tecla(key, campo());
        // Los diálogos que abren F3, F4 y F10 se cierran con Esc, que tampoco toca el ticket.
        if (dialogo()) {
          expect(dialogo()!.textContent).not.toContain('Vaciar el ticket');
          await tecla('Escape');
        }
      }

      expect(descripciones()).toEqual(['Artículo 1', 'Artículo 2']);
    });

    it('Esc sin diálogo limpia el buscador y 5 Esc seguidos dejan el ticket intacto', async () => {
      await conTicket();
      await escribir('algo');

      for (let i = 0; i < 5; i++) await tecla('Escape', campo());

      expect(campo().value).toBe('');
      expect(descripciones()).toEqual(['Artículo 1', 'Artículo 2']);
      expect(total()).toBe('$ 300,50');
    });

    it('Esc con un diálogo abierto lo cierra primero y no toca el buscador', async () => {
      await conTicket();
      await escribir('algo');
      await tecla('F4', campo());
      expect(dialogo()).not.toBeNull();

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(campo().value).toBe('algo');
      expect(renglonesTicket()).toHaveLength(2);
    });

    it('con un diálogo abierto, F2, F3, F4 y F10 se anulan pero no hacen nada', async () => {
      await conTicket();
      await tecla('F3');

      for (const key of ['F2', 'F3', 'F4', 'F10']) {
        const evento = await tecla(key);
        expect(evento.defaultPrevented).toBe(true);
      }

      expect(el().querySelectorAll('[role="dialog"], [role="alertdialog"]')).toHaveLength(1);
      expect(dialogo()!.textContent).toContain('Cantidad de Artículo 2');
      expect(dentroDelDialogo()).toBe(true);
    });

    it('al cerrar un diálogo el foco vuelve al buscador', async () => {
      await conTicket();
      await tecla('F10');
      expect(dentroDelDialogo()).toBe(true);

      await tecla('Escape');

      expect(document.activeElement).toBe(campo());
    });

    it('el foco que el buscador recupera con retraso no le roba el foco al diálogo recién abierto (H-18)', async () => {
      await conTicket();
      // El buscador pierde el foco sin destino: programa recuperarlo en el próximo turno.
      campo().blur();
      await tecla('F10');
      expect(dentroDelDialogo()).toBe(true);

      await avanzar(5);

      expect(dentroDelDialogo()).toBe(true);
      expect(document.activeElement).not.toBe(campo());
      await tecla('Enter');
      expect(dialogo()).toBeNull();
      expect(descripciones()).toEqual(['Artículo 1']);
    });

    it('Esc, ↓ y F10 seguidos dejan el foco en el diálogo y Enter confirma (H-18)', async () => {
      await conTicket();
      await tecla('ArrowUp', campo());

      await tecla('Escape', campo());
      await tecla('ArrowDown', campo());
      await tecla('F10');
      await avanzar(5);

      expect(dentroDelDialogo()).toBe(true);
      await tecla('Enter');
      expect(dialogo()).toBeNull();
      expect(descripciones()).toEqual(['Artículo 1']);
    });

    it('un escaneo con un diálogo abierto se descarta y avisa', async () => {
      await conTicket();
      await tecla('F3');
      const antes = llamadas.length;
      const input = dialogo()!.querySelector('input')!;

      for (const c of '7790001') {
        vi.advanceTimersByTime(5);
        input.dispatchEvent(new KeyboardEvent('keydown', { key: c, bubbles: true, cancelable: true }));
        input.value += c;
      }
      vi.advanceTimersByTime(5);
      await tecla('Enter', input);

      expect(el().textContent).toContain('Cerrá el diálogo antes de escanear.');
      expect(llamadas).toHaveLength(antes);
      expect(renglonesTicket()).toHaveLength(2);
      expect(cantidades()).toEqual(['1', '1 m']);
      expect(dialogo()).not.toBeNull();
    });

    it('una respuesta que llega con el diálogo abierto no le roba el foco', async () => {
      await conTicket();
      await escribir('C');
      await enter();
      await tecla('F3', campo());

      await responder(llamadas.length - 1, exacto(articulo(3)));

      expect(dentroDelDialogo()).toBe(true);
      expect(renglonesTicket()).toHaveLength(3);
    });

    it('la botonera dispara lo mismo que la tecla', async () => {
      await conTicket();

      botonera('Cantidad').click();
      await refrescar();
      expect(dialogo()!.textContent).toContain('Cantidad de Artículo 2');
      await tecla('Escape');

      botonera('Quitar').click();
      await refrescar();
      expect(dialogo()!.textContent).toContain('¿Quitar «Artículo 2» del ticket?');
      await tecla('Escape');

      botonera('Cobrar').click();
      await refrescar();
      expect(dialogo()!.textContent).toContain('Cobrar');
      await tecla('Escape');

      botonera('Buscar').click();
      await refrescar();
      expect(document.activeElement).toBe(campo());
    });

    it('con un diálogo abierto Tab no sale de él, y al cerrarlo el foco vuelve al código y no al botón que lo abrió', async () => {
      await conTicket();
      botonera('Cobrar').focus();
      botonera('Cobrar').click();
      await refrescar();

      const cobrar = botonDelDialogo('Cobrar');
      cobrar.focus();
      await tecla('Tab');
      expect(dentroDelDialogo()).toBe(true);

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(document.activeElement).toBe(campo());
    });

    it('con el ticket vacío la botonera apaga cantidad, cobrar, quitar y vaciar y explica por qué', async () => {
      await crear();

      for (const etiqueta of ['Cantidad', 'Cobrar', 'Quitar', 'Vaciar']) {
        expect(botonera(etiqueta).getAttribute('aria-disabled')).toBe('true');
        // S4: el clic tiene que llegar para poder explicar, así que no va el atributo disabled.
        expect(botonera(etiqueta).hasAttribute('disabled')).toBe(false);
      }
      expect(botonera('Buscar').hasAttribute('aria-disabled')).toBe(false);

      botonera('Cobrar').click();
      await refrescar();

      expect(motivoDelBotonera()).toBe('Agregá un renglón para usar esta acción.');
      expect(dialogo()).toBeNull();
      expect(cobros).toHaveLength(0);
    });

    it('S4: un botón apagado no ejecuta la acción y dice por qué en un aviso role=status enlazado', async () => {
      await crear();

      botonera('Cantidad').click();
      await refrescar();

      const aviso = el().querySelector('.motivo-botonera')!;
      expect(aviso.getAttribute('role')).toBe('status');
      expect(aviso.textContent!.trim()).toBe('Agregá un renglón para usar esta acción.');
      expect(botonera('Cantidad').getAttribute('aria-describedby')).toBe(aviso.id);
      expect(botonera('Cobrar').hasAttribute('aria-describedby')).toBe(false);
      expect(dialogo()).toBeNull();
    });

    it('S4: la tecla de una acción apagada dice lo mismo que su botón, y no hace nada', async () => {
      await crear();

      const evento = await tecla('F3', campo());

      expect(evento.defaultPrevented).toBe(true);
      expect(motivoDelBotonera()).toBe('Agregá un renglón para usar esta acción.');
      expect(dialogo()).toBeNull();
    });

    it('S4: con la caja cerrada, Cobrar dice que hay que abrirla (con la tecla y con el botón)', async () => {
      await crear(sinCaja);
      await escanear('M', articulo(1, { precioFinal: 1500 }));

      await tecla('F4', campo());

      expect(motivoDelBotonera()).toBe('Abrí la caja para vender.');
      expect(dialogo()).toBeNull();
      expect(cobros).toHaveLength(0);

      // El aviso se va solo y el botón lo vuelve a decir.
      await avanzar(5000);
      expect(el().querySelector('.motivo-botonera')).toBeNull();
      botonera('Cobrar').click();
      await refrescar();
      expect(motivoDelBotonera()).toBe('Abrí la caja para vender.');
      expect(dialogo()).toBeNull();
    });

    it('S4: con un diálogo abierto ninguna acción se ejecuta y se dice que hay que cerrarlo', async () => {
      await crear();
      await tecla('F9', campo());
      expect(dialogo()!.textContent).toContain('Cliente');

      botonera('Buscar').click();
      await refrescar();

      expect(motivoDelBotonera()).toBe('Cerrá el diálogo para seguir con el ticket.');
      expect(dialogo()!.textContent).toContain('Cliente');

      await tecla('F3', campo());

      expect(motivoDelBotonera()).toBe('Cerrá el diálogo para seguir con el ticket.');
      expect(dialogo()!.textContent).toContain('Cliente');
    });

    it('S4: el motivo no le roba el foco a la caja de búsqueda y se va con la próxima acción que sí se puede', async () => {
      await crear();
      expect(document.activeElement).toBe(campo());

      botonera('Cantidad').click();
      await refrescar();

      expect(motivoDelBotonera()).toBe('Agregá un renglón para usar esta acción.');
      expect(document.activeElement).toBe(campo());

      botonera('Cliente').click();
      await refrescar();

      expect(el().querySelector('.motivo-botonera')).toBeNull();
      expect(dialogo()).not.toBeNull();
    });
  });

  describe('cobro (CU-07, R-034, R-035)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({
      tipo: 'EXACTO',
      items: [a],
      hayMas: false,
    });

    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const avisos = () => normal(el().querySelector('.aviso')?.textContent);
    const descripciones = () =>
      renglonesTicket().map((r) => r.querySelector('.descripcion')!.textContent!.trim());
    const botonDelDialogo = (texto: string) =>
      Array.from(dialogo()!.querySelectorAll<HTMLButtonElement>('button')).find(
        (b) => b.textContent!.trim() === texto,
      )!;

    async function tecla(key: string, destino: HTMLElement = document.activeElement as HTMLElement) {
      destino.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      await refrescar();
    }

    async function escanear(codigo: string, a: ArticuloMostrador) {
      await escribir(codigo);
      await enter();
      await responder(llamadas.length - 1, exacto(a));
    }

    const martillo = (cambios: Partial<ArticuloMostrador> = {}) =>
      articulo(1, { descripcion: 'Martillo', precioFinal: 1500, ...cambios });

    /** Ticket de 2 martillos ($ 1.500,00 c/u = $ 3.000,00) listo para cobrar. */
    async function conMartillos() {
      await crear();
      await escanear('M', martillo());
      await escanear('M', martillo());
    }

    function venta(numero: number, total: number, sinStock: VentaCobrada['venta']['sinStock'] = []) {
      return {
        venta: {
          ventaId: 99,
          numero,
          total,
          fecha: '2026-10-15T10:00:00',
          medioPago: 'EFECTIVO',
          renglones: [],
          sinStock,
          facturable: true,
        },
        repetida: false,
      } as VentaCobrada;
    }

    async function cobrarOk(indice: number, v: VentaCobrada) {
      cobros[indice].respuesta.next(v);
      cobros[indice].respuesta.complete();
      await refrescar();
    }

    async function fallarCobro(indice: number, status: number, cuerpo: unknown = null) {
      cobros[indice].respuesta.error(new HttpErrorResponse({ status, error: cuerpo }));
      await refrescar();
    }

    const rechazo = (status: number, motivo: string, articulos: unknown[], extra = {}) => ({
      status,
      message: 'rechazo',
      timestamp: '2026-10-15T10:00:00',
      motivo,
      articulos,
      ...extra,
    });

    it('F4 + Enter envía la venta en efectivo con la clave del ticket', async () => {
      await conMartillos();

      await tecla('F4');
      await tecla('Enter');

      expect(cobros).toHaveLength(1);
      expect(cobros[0].pedido).toEqual({
        clave: 'clave-1',
        renglones: [{ articuloId: 1, cantidad: 2, precioUnitario: 1500 }],
        totalMostrado: 3000,
        formaPagoId: 1,
      });
    });

    it('el diálogo de cobro muestra cuántos renglones tiene el ticket', async () => {
      await conMartillos();

      await tecla('F4');

      expect(normal(dialogo()!.querySelector('.conteo')?.textContent)).toBe('1 renglón');
    });

    it('F4, → y Enter venden con DÉBITO', async () => {
      await conMartillos();

      await tecla('F4');
      await tecla('ArrowRight');
      await tecla('Enter');

      expect(cobros[0].pedido.formaPagoId).toBe(2);
    });

    describe('formas de pago con recargo (GDS-11)', () => {
      const visa6 = formaDe(7, 'Visa 6 cuotas', 'CREDITO', 10);
      const conVisa = [...FORMAS_BASE, visa6];

      /** Ticket de 2 martillos ($ 3.000,00) con esas formas. */
      async function conMartillosYFormas(formas: FormaPago[]) {
        await crear(cajaAbierta, formas);
        await escanear('M', martillo());
        await escanear('M', martillo());
      }

      const opcionesDelCobro = () =>
        Array.from(dialogo()!.querySelectorAll('button[role="radio"]')).map((b) => normal(b.textContent));

      function elegirOpcion(texto: string) {
        Array.from(dialogo()!.querySelectorAll<HTMLButtonElement>('button[role="radio"]'))
          .find((b) => b.textContent!.includes(texto))!
          .click();
      }

      it('lee las formas activas al abrir y las ofrece en el cobro, en su orden', async () => {
        await conMartillosYFormas(conVisa);

        await tecla('F4');

        expect(formasApi.listar).toHaveBeenCalledWith(true);
        expect(opcionesDelCobro()).toEqual([
          'Efectivo',
          'Débito',
          'Crédito',
          'Transferencia',
          'Visa 6 cuotas +10 %',
          'Mixto',
        ]);
      });

      it('F4 vuelve a leer las formas: si el Dueño cambió un recargo, el cobro ya lo trae', async () => {
        await conMartillosYFormas(conVisa);
        formasApi.listar.mockReturnValue(of([...FORMAS_BASE, formaDe(7, 'Visa 6 cuotas', 'CREDITO', 25)]));

        await tecla('F4');
        elegirOpcion('Visa 6 cuotas');
        await refrescar();

        expect(formasApi.listar).toHaveBeenCalledTimes(2);
        expect(normal(dialogo()!.querySelector('.total')?.textContent)).toBe('$ 3.750,00');
      });

      it('sin formas cargadas (no se pudieron leer) F4 avisa, no abre el cobro y las vuelve a pedir', async () => {
        await crear(cajaAbierta, []);
        await escanear('M', martillo());

        await tecla('F4');

        expect(dialogo()).toBeNull();
        expect(normal(el().textContent)).toContain('No se pudieron cargar las formas de pago.');
        expect(formasApi.listar).toHaveBeenCalledTimes(2);
      });

      it('con una forma con recargo manda su id y el total mostrado con el recargo, sin tocar los renglones', async () => {
        await conMartillosYFormas(conVisa);

        await tecla('F4');
        elegirOpcion('Visa 6 cuotas');
        await refrescar();
        expect(normal(dialogo()!.querySelector('.total')?.textContent)).toBe('$ 3.300,00');
        await tecla('Enter');

        expect(cobros).toHaveLength(1);
        expect(cobros[0].pedido).toEqual({
          clave: 'clave-1',
          renglones: [{ articuloId: 1, cantidad: 2, precioUnitario: 1500 }],
          totalMostrado: 3300,
          formaPagoId: 7,
        });
      });

      it('el aviso de venta cobrada muestra el total con el recargo', async () => {
        await conMartillosYFormas(conVisa);
        await tecla('F4');
        elegirOpcion('Visa 6 cuotas');
        await refrescar();
        await tecla('Enter');

        await cobrarOk(0, venta(12, 3300));

        expect(normal(el().textContent)).toContain('Venta N.º 12 cobrada: $ 3.300,00');
      });

      it('el pago mixto manda las partes sin recargo y el total mostrado con el recargo de la parte que lo tiene', async () => {
        await conMartillosYFormas(conVisa);
        await tecla('F4');
        elegirOpcion('Mixto');
        await refrescar();
        const cargar = (id: number, texto: string) => {
          const campo = dialogo()!.querySelector<HTMLInputElement>(`input[data-forma="${id}"]`)!;
          campo.value = texto;
          campo.dispatchEvent(new Event('input', { bubbles: true }));
        };
        cargar(1, '1000');
        cargar(7, '2000');
        await refrescar();

        await tecla('Enter');

        expect(cobros[0].pedido.formaPagoId).toBeUndefined();
        expect(cobros[0].pedido.pagos).toEqual([
          { formaPagoId: 1, monto: 1000 },
          { formaPagoId: 7, monto: 2000 },
        ]);
        expect(cobros[0].pedido.totalMostrado).toBe(3200);
      });

      it('un 409 vuelve a leer las formas: el total pudo cambiar por un recargo nuevo', async () => {
        await conMartillosYFormas(conVisa);
        await tecla('F4');
        elegirOpcion('Visa 6 cuotas');
        await refrescar();
        await tecla('Enter');
        formasApi.listar.mockClear();

        await fallarCobro(0, 409, rechazo(409, 'PRECIOS_CAMBIADOS', [], { total: 3750 }));

        expect(formasApi.listar).toHaveBeenCalledTimes(1);
        expect(dialogo()).not.toBeNull();
      });

      it('un 400 por una forma de pago inválida vuelve a leer las formas y deja el cobro abierto con el mensaje', async () => {
        await conMartillosYFormas(conVisa);
        await tecla('F4');
        elegirOpcion('Visa 6 cuotas');
        await refrescar();
        await tecla('Enter');
        formasApi.listar.mockClear();
        formasApi.listar.mockReturnValue(of(FORMAS_BASE));

        await fallarCobro(0, 400, {
          status: 400,
          message: 'Datos inválidos',
          errors: { formaPagoId: 'La forma de pago no es válida.' },
        });

        expect(formasApi.listar).toHaveBeenCalledTimes(1);
        expect(normal(dialogo()!.textContent)).toContain('La forma de pago no es válida.');
        expect(opcionesDelCobro()).not.toContain('Visa 6 cuotas +10 %');
      });
    });

    describe('venta no facturable (NF-003)', () => {
      const noFacturable = (numero: number, total: number): VentaCobrada => {
        const base = venta(numero, total);
        return { ...base, venta: { ...base.venta, facturable: false } };
      };

      async function abrirCobroNoFacturable() {
        await conMartillos();
        await tecla('F4');
        (dialogo()!.querySelector('input[type="checkbox"]') as HTMLInputElement).click();
        await refrescar();
      }

      it('con la casilla tildada el POST manda facturable false y nada más cambia', async () => {
        await abrirCobroNoFacturable();

        await tecla('Enter');

        expect(cobros).toHaveLength(1);
        expect(cobros[0].pedido).toEqual({
          clave: 'clave-1',
          renglones: [{ articuloId: 1, cantidad: 2, precioUnitario: 1500 }],
          totalMostrado: 3000,
          formaPagoId: 1,
          facturable: false,
        });
      });

      it('sin tildar el POST no manda la marca', async () => {
        await conMartillos();
        await tecla('F4');

        await tecla('Enter');

        expect('facturable' in cobros[0].pedido).toBe(false);
      });

      it('el aviso de la venta cobrada dice que no es facturable', async () => {
        await abrirCobroNoFacturable();
        await tecla('Enter');

        await cobrarOk(0, noFacturable(12, 3000));

        expect(avisos()).toBe('Venta N.º 12 cobrada (no facturable): $ 3.000,00');
      });

      it('el aviso de una venta facturable no cambia', async () => {
        await conMartillos();
        await tecla('F4');
        await tecla('Enter');

        await cobrarOk(0, venta(12, 3000));

        expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.000,00');
      });

      it('la casilla vuelve destildada en el cobro siguiente', async () => {
        await abrirCobroNoFacturable();
        await tecla('Escape');
        await tecla('F4');

        expect((dialogo()!.querySelector('input[type="checkbox"]') as HTMLInputElement).checked).toBe(false);
      });
    });

    it('un 201 cierra el diálogo, vacía el ticket y avisa con el número y el total', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await cobrarOk(0, venta(12, 3630));

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
      expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.630,00');
      expect(el().querySelector('.aviso')!.getAttribute('role')).toBe('status');
      expect(localStorage.getItem('ticket:3:7')).toBeNull();
      expect(document.activeElement).toBe(campo());
    });

    it('un escaneo que vuelve con el POST en vuelo queda en el ticket con clave nueva (W-1)', async () => {
      await conMartillos();
      await escribir('S');
      await enter();
      await tecla('F4');
      await tecla('Enter');
      expect(cobros).toHaveLength(1);

      await responder(
        llamadas.length - 1,
        exacto(articulo(2, { descripcion: 'Serrucho', precioFinal: 100 })),
      );
      await cobrarOk(0, venta(12, 3000));

      expect(descripciones()).toEqual(['Serrucho']);
      expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.000,00');
      const guardado = JSON.parse(localStorage.getItem('ticket:3:7')!);
      expect(guardado.clave).toBe('clave-2');
      expect(guardado.renglones.map((r: { articuloId: number }) => r.articuloId)).toEqual([2]);
    });

    it('si el escaneo en vuelo era del mismo artículo, queda solo la unidad de más (W-1)', async () => {
      await conMartillos();
      await escribir('M');
      await enter();
      await tecla('F4');
      await tecla('Enter');

      await responder(llamadas.length - 1, exacto(martillo()));
      await cobrarOk(0, venta(12, 3000));

      expect(descripciones()).toEqual(['Martillo']);
      expect(el().querySelector('app-ticket-lista .cantidad')!.textContent!.trim()).toBe('1');
      await tecla('F4');
      await tecla('Enter');
      expect(cobros[1].pedido.clave).toBe('clave-2');
      expect(cobros[1].pedido.renglones[0].cantidad).toBe(1);
    });

    describe('aviso de venta cobrada (se oculta a los 5 s)', () => {
      async function cobrarYa(indice: number, numero: number) {
        await tecla('F4');
        await tecla('Enter');
        await cobrarOk(indice, venta(numero, 3000));
      }

      it('se oculta a los 5 segundos y no antes', async () => {
        await conMartillos();
        await cobrarYa(0, 12);

        await avanzar(4999);
        expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.000,00');
        await avanzar(1);
        expect(el().querySelector('.aviso')).toBeNull();
      });

      it('escanear no lo borra antes de tiempo', async () => {
        await conMartillos();
        await cobrarYa(0, 12);

        await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
        await avanzar(2000);

        expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.000,00');
      });

      it('una venta nueva antes de los 5 s arranca su propio plazo', async () => {
        await conMartillos();
        await cobrarYa(0, 12);
        await avanzar(3000);
        await escanear('M', martillo());
        await cobrarYa(1, 13);

        await avanzar(3000);
        expect(avisos()).toBe('Venta N.º 13 cobrada: $ 3.000,00');
        await avanzar(2000);
        expect(el().querySelector('.aviso')).toBeNull();
      });

      it('el temporizador viejo no borra un aviso distinto que apareció después', async () => {
        await conMartillos();
        await cobrarYa(0, 12);
        await avanzar(2000);
        // S4: F4 con el ticket vacío ya no deja un aviso del ticket: explica el motivo. El aviso nuevo lo deja un
        // artículo dado de baja, que se queda hasta el próximo renglón.
        await tecla('F4');
        expect(el().querySelector('.motivo-botonera')).not.toBeNull();
        await escanear('B', articulo(9, { activo: false }));
        expect(avisos()).toBe('"Artículo 9" está dado de baja.');

        await avanzar(3500);

        expect(avisos()).toBe('"Artículo 9" está dado de baja.');
      });

      it('al destruir el componente no queda el temporizador', async () => {
        await conMartillos();
        await cobrarYa(0, 12);

        const pendientes = vi.getTimerCount();

        fixture.destroy();

        // El del aviso y el de la consulta de cada minuto por el CAE de la caja abierta (C7).
        expect(vi.getTimerCount()).toBe(pendientes - 2);
      });
    });

    describe('vuelto en el aviso de venta cobrada (H-05)', () => {
      async function cobrarRecibiendo(recibido: string | null, numero = 2) {
        await tecla('F4');
        if (recibido !== null) {
          const input = dialogo()!.querySelector('input')!;
          input.value = recibido;
          input.dispatchEvent(new Event('input'));
          await refrescar();
        }
        await tecla('Enter');
        await cobrarOk(0, venta(numero, 3000));
      }

      it('con vuelto muestra «Venta N.º 2 cobrada · Vuelto $ 565,00» con el vuelto destacado', async () => {
        await conMartillos();

        await cobrarRecibiendo('3565');

        expect(avisos()).toBe('Venta N.º 2 cobrada · Vuelto $ 565,00');
        expect(normal(el().querySelector('.aviso .vuelto')?.textContent)).toBe('$ 565,00');
      });

      it('con vuelto el aviso no se oculta a los 5 s: queda hasta el próximo escaneo', async () => {
        await conMartillos();
        await cobrarRecibiendo('3565');

        await avanzar(60000);
        expect(avisos()).toBe('Venta N.º 2 cobrada · Vuelto $ 565,00');

        await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
        expect(el().querySelector('.aviso')).toBeNull();
      });

      it('con vuelto, Esc despeja el aviso', async () => {
        await conMartillos();
        await cobrarRecibiendo('3565');

        await tecla('Escape', campo());

        expect(el().querySelector('.aviso')).toBeNull();
      });

      it('con el importe exacto no hay vuelto y el aviso se oculta a los 5 s', async () => {
        await conMartillos();
        await cobrarRecibiendo(null);

        expect(avisos()).toBe('Venta N.º 2 cobrada: $ 3.000,00');
        expect(el().querySelector('.aviso .vuelto')).toBeNull();
        await avanzar(5000);
        expect(el().querySelector('.aviso')).toBeNull();
      });

      it('con otro medio de pago no hay vuelto', async () => {
        await conMartillos();
        await tecla('F4');
        await tecla('ArrowRight');
        await tecla('Enter');
        await cobrarOk(0, venta(2, 3000));

        expect(avisos()).toBe('Venta N.º 2 cobrada: $ 3.000,00');
      });
    });

    describe('aviso de sin stock (H-17)', () => {
      async function cobrarSinStock() {
        await conMartillos();
        await tecla('F4');
        await tecla('Enter');
        await cobrarOk(
          0,
          venta(12, 3000, [{ articuloId: 1, descripcion: 'Taladro', stockActual: -4 }]),
        );
      }

      it('va aparte de la venta, como advertencia y no como éxito', async () => {
        await cobrarSinStock();

        const advertencia = el().querySelector('.aviso.advertencia');
        expect(normal(advertencia?.textContent)).toBe('Sin stock: "Taladro" quedó en -4.');
        expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.000,00');
      });

      it('no se oculta solo a los 5 s: queda hasta el próximo escaneo', async () => {
        await cobrarSinStock();

        await avanzar(60000);
        expect(el().querySelector('.aviso.advertencia')).not.toBeNull();

        await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
        expect(el().querySelector('.aviso')).toBeNull();
      });
    });

    it('el aviso no bloquea el siguiente escaneo y el ticket nuevo usa otra clave', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');
      await cobrarOk(0, venta(12, 3630));

      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));

      expect(descripciones()).toEqual(['Serrucho']);
      expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.630,00');
      await tecla('F4');
      await tecla('Enter');
      expect(cobros[1].pedido.clave).toBe('clave-2');
    });

    it('una venta repetida (200) se trata igual que una nueva', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await cobrarOk(0, { ...venta(12, 3000), repetida: true });

      expect(renglonesTicket()).toHaveLength(0);
      expect(avisos()).toBe('Venta N.º 12 cobrada: $ 3.000,00');
    });

    it('la advertencia de sinStock de la respuesta se muestra junto al aviso', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await cobrarOk(
        0,
        venta(12, 3000, [{ articuloId: 1, descripcion: 'Martillo', stockActual: -3 }]),
      );

      expect(avisos()).toContain('Venta N.º 12 cobrada: $ 3.000,00');
      expect(normal(el().textContent)).toContain('Sin stock: "Martillo" quedó en -3.');
    });

    it('un doble Enter envía un solo POST', async () => {
      await conMartillos();
      await tecla('F4');

      await tecla('Enter');
      await tecla('Enter');

      expect(cobros).toHaveLength(1);
    });

    it('mientras la venta está en vuelo, Esc no cierra el diálogo', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await tecla('Escape');

      expect(dialogo()).not.toBeNull();
      expect(renglonesTicket()).toHaveLength(1);
    });

    it('un 409 de precios actualiza el renglón, avisa, muestra el total nuevo y Enter confirma de nuevo', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(
        0,
        409,
        rechazo(
          409,
          'PRECIOS_CAMBIADOS',
          [
            {
              articuloId: 1,
              descripcion: 'Martillo',
              precioMostrado: 1500,
              precioActual: 1650,
              totalRenglon: 3300,
            },
          ],
          { total: 3300 },
        ),
      );

      expect(dialogo()).not.toBeNull();
      expect(normal(dialogo()!.textContent)).toContain('$ 3.300,00');
      expect(normal(dialogo()!.textContent)).toContain(
        'Cambió el precio de "Martillo": ahora $ 1.650,00.',
      );
      expect(normal(renglonesTicket()[0].textContent)).toContain('$ 1.650,00');
      expect(dialogo()!.querySelector('input')!.value).toBe('3.300,00');

      await tecla('Enter');

      expect(cobros).toHaveLength(2);
      expect(cobros[1].pedido.clave).toBe('clave-1');
      expect(cobros[1].pedido.renglones[0].precioUnitario).toBe(1650);
      expect(cobros[1].pedido.totalMostrado).toBe(3300);
    });

    it('un 409 con varios precios nuevos los cuenta', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(
        0,
        409,
        rechazo(
          409,
          'PRECIOS_CAMBIADOS',
          [
            { articuloId: 1, precioMostrado: 1500, precioActual: 1650, totalRenglon: 1650 },
            { articuloId: 2, precioMostrado: 100, precioActual: 110, totalRenglon: 110 },
          ],
          { total: 1760 },
        ),
      );

      expect(normal(dialogo()!.textContent)).toContain(
        'Cambiaron los precios de 2 artículos. Revisá el total y confirmá de nuevo.',
      );
      expect(normal(dialogo()!.textContent)).toContain('$ 1.760,00');
    });

    it('un 422 por baja conserva el ticket, cierra el diálogo, marca el renglón y deja quitarlo', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 422, {
        ...rechazo(422, 'ARTICULO_DADO_DE_BAJA', [{ articuloId: 1, descripcion: 'Martillo' }]),
        message: '"Martillo" fue dado de baja. Sacalo del ticket para seguir.',
      });

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(2);
      expect(avisos()).toBe('"Martillo" fue dado de baja. Sacalo del ticket para seguir.');
      expect(el().querySelector('.aviso')!.getAttribute('role')).toBe('alert');
      expect(normal(renglonesTicket()[0].textContent)).toContain('Artículo dado de baja');
      expect(renglonesTicket()[1].querySelector('.problema')).toBeNull();
      expect(document.activeElement).toBe(campo());

      // El renglón con problema quedó elegido: F10 propone quitar ese, sin tocar las flechas (H-06).
      await tecla('F10');
      expect(dialogo()!.textContent).toContain('¿Quitar «Martillo» del ticket?');
      await tecla('Enter');
      expect(descripciones()).toEqual(['Serrucho']);
    });

    it('un 422 por artículo sin precio conserva el ticket y marca el renglón', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 422, {
        ...rechazo(422, 'ARTICULO_SIN_PRECIO', [{ articuloId: 1, descripcion: 'Martillo' }]),
        message: '"Martillo" no tiene precio. Sacalo del ticket y pedile al dueño que le cargue el costo.',
      });

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(2);
      expect(avisos()).toBe('"Martillo" no tiene precio. Sacalo del ticket y pedile al dueño que le cargue el costo.');
      expect(normal(renglonesTicket()[0].textContent)).toContain('Sin precio');
      expect(renglonesTicket()[1].querySelector('.problema')).toBeNull();
    });

    it('un 404 también deja elegido el renglón inexistente (H-06)', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 404, rechazo(404, 'ARTICULO_INEXISTENTE', [{ articuloId: 1 }]));

      await tecla('F10');
      expect(dialogo()!.textContent).toContain('¿Quitar «Martillo» del ticket?');
    });

    it('un 404 ubica el renglón por id y lo nombra con la descripción del ticket', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 404, {
        ...rechazo(404, 'ARTICULO_INEXISTENTE', [{ articuloId: 2 }]),
        message: 'Hay un artículo del ticket que ya no está disponible',
      });

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(2);
      expect(avisos()).toBe('"Serrucho" ya no está disponible. Sacalo del ticket para seguir.');
      expect(normal(renglonesTicket()[1].textContent)).toContain('Ya no está disponible');
      expect(renglonesTicket()[0].querySelector('.problema')).toBeNull();
    });

    it('un 422 por contenido distinto muestra el mensaje del servidor y conserva el ticket', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 422, {
        ...rechazo(422, 'CLAVE_CON_OTRO_CONTENIDO', []),
        message: 'Esta venta ya se registró con otros datos',
      });

      expect(dialogo()).toBeNull();
      expect(avisos()).toBe('Esta venta ya se registró con otros datos');
      expect(renglonesTicket()).toHaveLength(1);
    });

    it('un 422 por Consumidor Final sin datos deja el cobro abierto con el mensaje y el ticket intacto (GDS-32)', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      const mensaje =
        'Desde $ 1.000,00 la venta a Consumidor Final necesita identificar al comprador: elegí un cliente con DNI, CUIT o CUIL.';
      await fallarCobro(0, 422, { ...rechazo(422, 'CONSUMIDOR_FINAL_SIN_DATOS', []), message: mensaje });

      expect(dialogo()).not.toBeNull();
      expect(normal(dialogo()!.querySelector('.aviso')?.textContent)).toBe(mensaje);
      expect(renglonesTicket()).toHaveLength(1);
    });

    it('un 422 por cliente sin datos fiscales deja el cobro abierto con el mensaje y el ticket intacto (D4)', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      const mensaje = 'Para facturarle a Ana Gómez falta su CUIT o su domicilio: completalos en Clientes.';
      await fallarCobro(0, 422, { ...rechazo(422, 'CLIENTE_SIN_DATOS_FISCALES', []), message: mensaje });

      expect(dialogo()).not.toBeNull();
      expect(normal(dialogo()!.querySelector('.aviso')?.textContent)).toBe(mensaje);
      expect(renglonesTicket()).toHaveLength(1);
    });

    it.each([
      ['un error de red', 0],
      ['un 500', 500],
    ])('%s conserva ticket y clave, avisa en el diálogo y el reintento usa la misma clave', async (_n, status) => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, status, null);

      expect(dialogo()).not.toBeNull();
      expect(normal(dialogo()!.textContent)).toContain('No se pudo cobrar. Probá de nuevo.');
      expect(renglonesTicket()).toHaveLength(1);
      await tecla('Enter');
      expect(cobros).toHaveLength(2);
      expect(cobros[1].pedido.clave).toBe('clave-1');
    });

    it('un 503 muestra el message del servidor en el diálogo, conserva ticket y clave y el reintento usa la misma clave', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 503, { status: 503, message: 'El sistema está ocupado, reintentá.' });

      expect(dialogo()).not.toBeNull();
      expect(normal(dialogo()!.textContent)).toContain('El sistema está ocupado, reintentá.');
      expect(normal(dialogo()!.textContent)).not.toContain('No se pudo cobrar');
      expect(renglonesTicket()).toHaveLength(1);
      await tecla('Enter');
      expect(cobros).toHaveLength(2);
      expect(cobros[1].pedido.clave).toBe('clave-1');
    });

    it('un 503 sin cuerpo cae en el texto fijo «El sistema está ocupado, reintentá.»', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 503, null);

      expect(normal(dialogo()!.textContent)).toContain('El sistema está ocupado, reintentá.');
      expect(normal(dialogo()!.textContent)).not.toContain('No se pudo cobrar');
    });

    it('un 400 por un renglón muestra el mensaje del servidor, marca ese renglón y conserva ticket y clave', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 400, {
        status: 400,
        message: 'Datos inválidos',
        errors: { 'renglones[1].cantidad': 'La cantidad debe ser mayor a 0' },
      });

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(2);
      expect(avisos()).toBe('La cantidad debe ser mayor a 0');
      expect(el().querySelector('.aviso')!.getAttribute('role')).toBe('alert');
      expect(normal(renglonesTicket()[1].textContent)).toContain('La cantidad debe ser mayor a 0');
      expect(renglonesTicket()[0].querySelector('.problema')).toBeNull();

      await tecla('F4');
      await tecla('Enter');
      expect(cobros[1].pedido.clave).toBe('clave-1');
    });

    it('después de un 400, cambiar el descuento total saca la marca del tope y deja la de cantidad (QA 05/11, A-5)', async () => {
      await crear();
      await escanear('M', martillo());
      await escanear('S', articulo(2, { descripcion: 'Serrucho', precioFinal: 100 }));
      await tecla('F4');
      await tecla('Enter');
      await fallarCobro(0, 400, {
        status: 400,
        message: 'Datos inválidos',
        errors: {
          'renglones[0].descuentoPorcentaje': 'Tu tope de descuento es 10 %.',
          'renglones[1].cantidad': 'Serrucho se vende por unidad: usá un número entero.',
        },
      });
      expect(normal(renglonesTicket()[0].querySelector('.problema')?.textContent)).toBe('Tu tope de descuento es 10 %.');

      await tecla('F6');
      const campo = el().querySelector<HTMLInputElement>('app-dialogo-descuento input')!;
      campo.value = '5';
      campo.dispatchEvent(new Event('input'));
      await refrescar();
      await tecla('Enter');

      expect(renglonesTicket()[0].querySelector('.problema')).toBeNull();
      expect(normal(renglonesTicket()[1].querySelector('.problema')?.textContent)).toBe(
        'Serrucho se vende por unidad: usá un número entero.',
      );
    });

    it('un 400 que no es de un renglón muestra los mensajes del servidor en el diálogo', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 400, {
        status: 400,
        message: 'Datos inválidos',
        errors: { formaPagoId: 'La forma de pago no es válida.' },
      });

      expect(dialogo()).not.toBeNull();
      expect(normal(dialogo()!.textContent)).toContain('La forma de pago no es válida.');
      expect(normal(dialogo()!.textContent)).not.toContain('No se pudo cobrar');
      expect(renglonesTicket()[0].querySelector('.problema')).toBeNull();
    });

    it('un 400 sin errores por campo muestra el mensaje del servidor', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 400, { status: 400, message: 'El cuerpo del pedido no es válido' });

      expect(normal(dialogo()!.textContent)).toContain('El cuerpo del pedido no es válido');
    });

    it('un 400 con un cuerpo que no se entiende cae al mensaje genérico', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(0, 400, null);

      expect(normal(dialogo()!.textContent)).toContain('No se pudo cobrar. Probá de nuevo.');
    });

    it('cancelar el diálogo después de un error deja el ticket para volver a intentar', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');
      await fallarCobro(0, 500);

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(1);
      expect(document.activeElement).toBe(campo());
    });
  });

  describe('aviso al salir (CU-07, R-037)', () => {
    const salir = () => {
      const evento = new Event('beforeunload', { cancelable: true }) as BeforeUnloadEvent;
      window.dispatchEvent(evento);
      return evento;
    };

    async function escanear(codigo: string, a: ArticuloMostrador) {
      await escribir(codigo);
      await enter();
      await responder(llamadas.length - 1, { tipo: 'EXACTO', items: [a], hayMas: false });
    }

    it('con un renglón pide confirmación al cerrar o recargar', async () => {
      await crear();
      await escanear('A', articulo(1));

      const evento = salir();

      expect(evento.defaultPrevented).toBe(true);
      expect(evento.returnValue).toBe(false);
    });

    it('con el ticket vacío no pide confirmación', async () => {
      await crear();

      const evento = salir();

      expect(evento.defaultPrevented).toBe(false);
    });

    it('recién cobrado no pide confirmación', async () => {
      await crear();
      await escanear('A', articulo(1));
      campo().dispatchEvent(new KeyboardEvent('keydown', { key: 'F4', bubbles: true, cancelable: true }));
      await refrescar();
      document.activeElement!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
      );
      await refrescar();
      cobros[0].respuesta.next({
        venta: { ventaId: 1, numero: 1, total: 170, fecha: '', medioPago: 'EFECTIVO', renglones: [], sinStock: [], facturable: true },
        repetida: false,
      });
      await refrescar();

      expect(salir().defaultPrevented).toBe(false);
    });

    it('no bloquea la navegación interna: no registra nada del lado del router', async () => {
      await crear();
      await escanear('A', articulo(1));

      // Cambiar de ruta no dispara beforeunload: solo el cierre o la recarga de la página lo hacen.
      expect(el().querySelector('a[href]')).toBeNull();
    });
  });

  describe('meta de la fase (CU-07, R-042)', () => {
    it('5 escaneos + F4 + Enter dan un POST con 5 renglones en efectivo y dejan el ticket vacío', async () => {
      await crear();
      for (let i = 1; i <= 5; i++) {
        await escribir(`COD${i}`);
        await enter();
        await responder(llamadas.length - 1, {
          tipo: 'EXACTO',
          items: [articulo(i, { precioFinal: 100 * i })],
          hayMas: false,
        });
      }

      campo().dispatchEvent(new KeyboardEvent('keydown', { key: 'F4', bubbles: true, cancelable: true }));
      await refrescar();
      document.activeElement!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
      );
      await refrescar();

      expect(cobros).toHaveLength(1);
      expect(cobros[0].pedido.renglones.map((r) => r.articuloId)).toEqual([1, 2, 3, 4, 5]);
      expect(cobros[0].pedido.formaPagoId).toBe(1);
      expect(cobros[0].pedido.totalMostrado).toBe(1500);

      cobros[0].respuesta.next({
        venta: { ventaId: 1, numero: 7, total: 1500, fecha: '', medioPago: 'EFECTIVO', renglones: [], sinStock: [], facturable: true },
        repetida: false,
      });
      await refrescar();

      expect(renglonesTicket()).toHaveLength(0);
      expect(document.activeElement).toBe(campo());
    });
  });

  describe('atajos', () => {
    let boton: HTMLButtonElement;

    beforeEach(() => {
      boton = document.createElement('button');
      document.body.append(boton);
    });

    afterEach(() => boton.remove());

    const tecla = async (key: string, destino: HTMLElement = campo()) => {
      const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      destino.dispatchEvent(evento);
      await refrescar();
      return evento;
    };

    const ficha = () => el().querySelector('app-ficha-articulo');

    it('F2 con el foco en un botón lo pasa al campo y evita la acción del navegador', async () => {
      await crear();
      boton.focus();
      expect(document.activeElement).toBe(boton);

      const evento = await tecla('F2', boton);

      expect(document.activeElement).toBe(campo());
      expect(evento.defaultPrevented).toBe(true);
    });

    it('F2 no tiene otro efecto: no borra el texto, no busca ni cierra la ficha', async () => {
      await crear();
      await escribir('7790001');
      await tecla('Enter');
      await responder(0, { tipo: 'EXACTO', items: [articulo(1)], hayMas: false });
      boton.focus();

      await tecla('F2', boton);
      await avanzar(500);

      expect(campo().value).toBe('7790001');
      expect(llamadas).toHaveLength(1);
      expect(ficha()).not.toBeNull();
    });

    it('Esc con la ficha visible y texto escrito deja todo vacío y el foco en el campo', async () => {
      await crear();
      await escribir('7790001');
      await tecla('Enter');
      await responder(0, { tipo: 'EXACTO', items: [articulo(1)], hayMas: false });
      expect(ficha()).not.toBeNull();
      boton.focus();

      const evento = await tecla('Escape', boton);

      expect(campo().value).toBe('');
      expect(ficha()).toBeNull();
      expect(filas()).toHaveLength(0);
      expect(el().querySelector('[role="alert"]')).toBeNull();
      expect(document.activeElement).toBe(campo());
      expect(evento.defaultPrevented).toBe(true);
    });

    it('Esc limpia también una lista y cancela la búsqueda pendiente o en vuelo', async () => {
      await crear();
      await escribir('tor');
      await avanzar(250);
      await responder(0, lista([articulo(1), articulo(2)]));
      expect(filas()).toHaveLength(2);
      await escribir('torn');
      await avanzar(250);
      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'torn']);

      await tecla('Escape');
      await responder(1, lista([articulo(3)]));
      await avanzar(500);

      expect(campo().value).toBe('');
      expect(filas()).toHaveLength(0);
      expect(llamadas).toHaveLength(2);
    });

    it('F8 con el ticket vacío dice por qué no puede y anula la acción del navegador, como F4', async () => {
      await crear();
      await escribir('tor');
      await avanzar(250);
      await responder(0, lista([articulo(1)]));

      const evento = await tecla('F8');
      await avanzar(500);

      expect(evento.defaultPrevented).toBe(true);
      expect(el().textContent).toContain('Agregá un renglón para usar esta acción.');
      expect(el().querySelector('[role="dialog"], [role="alertdialog"]')).toBeNull();
      expect(campo().value).toBe('tor');
      expect(guardados).toHaveLength(0);
    });

    it('F4 ya no es inerte: con el ticket vacío dice por qué no puede y anula la acción del navegador (R-032)', async () => {
      await crear();
      await escribir('tor');
      await avanzar(250);
      await responder(0, lista([articulo(1)]));

      const evento = await tecla('F4');
      await avanzar(500);

      expect(evento.defaultPrevented).toBe(true);
      expect(el().textContent).toContain('Agregá un renglón para usar esta acción.');
      expect(el().querySelector('[role="dialog"], [role="alertdialog"]')).toBeNull();
      expect(campo().value).toBe('tor');
      expect(filas()).toHaveLength(1);
      expect(llamadas).toHaveLength(1);
    });
  });

  describe('mensajes y errores', () => {
    let boton: HTMLButtonElement;
    let enlace: HTMLAnchorElement;

    beforeEach(() => {
      boton = document.createElement('button');
      enlace = document.createElement('a');
      enlace.href = '/rubros';
      document.body.append(boton, enlace);
    });

    afterEach(() => {
      boton.remove();
      enlace.remove();
    });

    const mensaje = () => el().querySelector('.mensaje')?.textContent?.trim() ?? null;
    const rolDelMensaje = () => el().querySelector('.mensaje')?.getAttribute('role') ?? null;
    const errorHttp = (status: number) => new HttpErrorResponse({ status });

    async function fallar(indice: number, status = 500) {
      llamadas[indice].respuesta.error(errorHttp(status));
      await refrescar();
    }

    async function buscar(q: string) {
      await escribir(q);
      await enter();
    }

    it('SIN_RESULTADOS se ve como advertencia roja, para que se note con el lector (H-17)', async () => {
      await crear();
      await buscar('martillo');

      await responder(0, { tipo: 'SIN_RESULTADOS', items: [], hayMas: false });

      expect(el().querySelector('.mensaje')!.classList.contains('error')).toBe(true);
    });

    it('SIN_RESULTADOS con texto muestra el mensaje de no encontrado', async () => {
      await crear();
      await buscar('martillo');

      await responder(0, { tipo: 'SIN_RESULTADOS', items: [], hayMas: false });

      expect(mensaje()).toBe('No encontramos ningún artículo con ese código o descripción.');
      expect(el().textContent).not.toContain('Artículo dado de baja');
    });

    it('«no encontrado» se anuncia como status (no interrumpe al que tipea); el error de red, como alert', async () => {
      await crear();
      await buscar('martillo');
      await responder(0, { tipo: 'SIN_RESULTADOS', items: [], hayMas: false });
      expect(rolDelMensaje()).toBe('status');

      await buscar('red');
      await fallar(1);
      expect(rolDelMensaje()).toBe('alert');
    });

    it('un mensaje desaparece cuando la búsqueda siguiente trae resultados', async () => {
      await crear();
      await buscar('martillo');
      await responder(0, { tipo: 'SIN_RESULTADOS', items: [], hayMas: false });
      expect(mensaje()).not.toBeNull();

      await buscar('tornillo');
      await responder(1, lista([articulo(1)]));

      expect(mensaje()).toBeNull();
      expect(filas()).toHaveLength(1);
    });

    it('una LISTA con hayMas muestra el aviso junto a las 20 filas', async () => {
      await crear();
      await buscar('tor');
      const veinte = Array.from({ length: 20 }, (_, i) => articulo(i + 1));

      await responder(0, lista(veinte, true));

      expect(filas()).toHaveLength(20);
      expect(el().textContent).toContain(
        'Hay más resultados. Escribí más para afinar la búsqueda.',
      );
    });

    it('un error 500 muestra «No se pudo buscar. Probá de nuevo.» y el foco sigue en el campo', async () => {
      await crear();
      await buscar('tor');

      await fallar(0);

      expect(mensaje()).toBe('No se pudo buscar. Probá de nuevo.');
      expect(document.activeElement).toBe(campo());
    });

    it('un error de red muestra el mismo mensaje', async () => {
      await crear();
      await buscar('tor');

      await fallar(0, 0);

      expect(mensaje()).toBe('No se pudo buscar. Probá de nuevo.');
    });

    it('después de un error la pantalla sigue andando: la búsqueda siguiente funciona', async () => {
      await crear();
      await buscar('tor');
      await fallar(0);

      await buscar('torn');
      await responder(1, lista([articulo(2, { descripcion: 'Tornillo' })]));

      expect(llamadas.map((l) => l.q)).toEqual(['tor', 'torn']);
      expect(mensaje()).toBeNull();
      expect(filas()).toHaveLength(1);
    });

    it('un error saca el resultado anterior de la pantalla', async () => {
      await crear();
      await buscar('tor');
      await responder(0, lista([articulo(1)]));
      expect(filas()).toHaveLength(1);

      await buscar('torn');
      await fallar(1);

      expect(filas()).toHaveLength(0);
      expect(mensaje()).toBe('No se pudo buscar. Probá de nuevo.');
    });

    it('un 401 no muestra mensaje propio: lo trata el interceptor de sesión', async () => {
      await crear();
      await buscar('tor');

      await fallar(0, 401);

      expect(mensaje()).toBeNull();
    });

    it('tras cada búsqueda, con resultado o con error, el campo recupera el foco', async () => {
      await crear();
      await buscar('tor');
      boton.focus();
      await responder(0, lista([articulo(1)]));
      expect(document.activeElement).toBe(campo());

      await buscar('torn');
      boton.focus();
      await fallar(1);

      expect(document.activeElement).toBe(campo());
    });

    it('un clic en una parte vacía de la pantalla devuelve el foco al campo', async () => {
      await crear();

      campo().blur();
      await avanzar(0);

      expect(document.activeElement).toBe(campo());
    });

    it.each([
      ['un botón (Salir)', () => boton],
      ['un enlace (Rubros)', () => enlace],
    ])('el foco que pasa a %s no se le roba', async (_nombre, destino) => {
      await crear();

      destino().focus();
      await avanzar(0);

      expect(document.activeElement).toBe(destino());
    });
  });

  describe('la ficha sigue lo que el vendedor tiene elegido (H-17, H-19)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({
      tipo: 'EXACTO',
      items: [a],
      hayMas: false,
    });
    const nombreFicha = () =>
      el().querySelector('app-ficha-articulo .descripcion')?.textContent?.trim() ?? null;
    const flecha = async (key: 'ArrowDown' | 'ArrowUp') => {
      campo().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      await refrescar();
    };

    async function escanear(codigo: string, a: ArticuloMostrador) {
      await escribir(codigo);
      await enter();
      await responder(llamadas.length - 1, exacto(a));
    }

    async function buscarLista(items: ArticuloMostrador[]) {
      await escribir('tor');
      await enter();
      await responder(llamadas.length - 1, lista(items));
    }

    it('↑ y ↓ sobre el ticket cambian la ficha al renglón elegido', async () => {
      await crear();
      await escanear('A', articulo(1, { ubicacion: 'Pasillo 9' }));
      await escanear('B', articulo(2));
      expect(nombreFicha()).toBe('Artículo 2');

      await flecha('ArrowUp');
      expect(nombreFicha()).toBe('Artículo 1');
      expect(el().querySelector('app-ficha-articulo .ubicacion')!.textContent).toContain('Pasillo 9');

      await flecha('ArrowDown');
      expect(nombreFicha()).toBe('Artículo 2');
    });

    it('con la lista de resultados abierta, la ficha muestra el resultado resaltado', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2), articulo(3)]);
      expect(nombreFicha()).toBe('Artículo 1');

      await flecha('ArrowDown');

      expect(nombreFicha()).toBe('Artículo 2');
      expect(renglonesTicket()).toHaveLength(0);
    });

    it('al elegir de la lista la ficha queda en el artículo agregado', async () => {
      await crear();
      await buscarLista([articulo(1), articulo(2)]);
      await flecha('ArrowDown');

      await enter();

      expect(nombreFicha()).toBe('Artículo 2');
      expect(filas()).toHaveLength(0);
    });

    it('con el ticket vacío y la lista abierta no asoma «El ticket está vacío» debajo', async () => {
      await crear();
      expect(el().querySelector('app-ticket-lista .vacio')).not.toBeNull();

      await buscarLista([articulo(1), articulo(2)]);

      expect(el().querySelector('app-ticket-lista .vacio')).toBeNull();
    });
  });

  describe('descuentos (GDS-12)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const dialogo = () => el().querySelector<HTMLElement>('app-dialogo-descuento [role="dialog"]');
    const campoDescuento = () => el().querySelector<HTMLInputElement>('app-dialogo-descuento input')!;
    const totalGrande = () => normal(el().querySelector('app-totales-ticket .total')?.textContent);

    async function tecla(key: string) {
      (document.activeElement as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }),
      );
      await refrescar();
    }

    async function conUnArticulo() {
      await crear();
      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1, { precioFinal: 1500 })));
    }

    async function descuentoDelDialogo(valor: string) {
      campoDescuento().value = valor;
      campoDescuento().dispatchEvent(new Event('input'));
      await refrescar();
      await tecla('Enter');
    }

    it('F7 abre el descuento del renglón elegido y al confirmar el renglón lo dice y el total baja', async () => {
      await conUnArticulo();

      await tecla('F7');

      expect(normal(dialogo()?.querySelector('h2')?.textContent)).toBe('Descuento de Artículo 1');
      await descuentoDelDialogo('10');
      expect(dialogo()).toBeNull();
      expect(normal(el().querySelector('app-ticket-lista .descuento')?.textContent)).toBe('10 % de descuento');
      expect(totalGrande()).toBe('$ 1.350,00');
    });

    it('F6 abre el descuento de todo el ticket y al confirmar baja el total', async () => {
      await conUnArticulo();

      await tecla('F6');

      expect(normal(dialogo()?.querySelector('h2')?.textContent)).toBe('Descuento de todo el ticket');
      await descuentoDelDialogo('10');
      expect(dialogo()).toBeNull();
      expect(totalGrande()).toBe('$ 1.350,00');
      expect(normal(el().querySelector('app-totales-ticket .descuento')?.textContent)).toBe('Descuento −$ 150,00');
    });

    it('CU-07: con tope, el diálogo no deja pasarlo y lo avisa', async () => {
      await crear(cajaAbierta, FORMAS_BASE, 'VENDEDOR', 10);
      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1, { precioFinal: 1500 })));

      await tecla('F7');
      expect(normal(dialogo()?.querySelector('.ayuda')?.textContent)).toContain('Tu tope es 10 %.');
      await descuentoDelDialogo('15');

      expect(normal(dialogo()?.querySelector('.error')?.textContent)).toBe('Tu tope de descuento es 10 %.');
      expect(totalGrande()).toBe('$ 1.500,00');
    });

    describe('con tope, el diálogo mira el descuento combinado (arreglos-confusos, S6)', () => {
      async function vendedorConTopeDe10() {
        await crear(cajaAbierta, FORMAS_BASE, 'VENDEDOR', 10);
        await escribir('M');
        await enter();
        await responder(llamadas.length - 1, exacto(articulo(1, { precioFinal: 1500 })));
      }

      it('F6: con 10 % en el renglón, 5 % general avisa en el diálogo y no aplica', async () => {
        await vendedorConTopeDe10();
        await tecla('F7');
        await descuentoDelDialogo('10');

        await tecla('F6');
        await descuentoDelDialogo('5');

        expect(normal(dialogo()?.querySelector('.error')?.textContent)).toBe(
          'Sumado al descuento del renglón, pasa tu tope de descuento de 10 %.',
        );
        expect(totalGrande()).toBe('$ 1.350,00');
      });

      it('F7: con 5 % general, 6 % en el renglón avisa en el diálogo y no aplica', async () => {
        await vendedorConTopeDe10();
        await tecla('F6');
        await descuentoDelDialogo('5');

        await tecla('F7');
        await descuentoDelDialogo('6');

        expect(normal(dialogo()?.querySelector('.error')?.textContent)).toBe(
          'Sumado al descuento general, pasa tu tope de descuento de 10 %.',
        );
        expect(totalGrande()).toBe('$ 1.425,00');
      });

      it('en un ticket que sale de un presupuesto no combina: sus descuentos ya están autorizados y el servidor decide', async () => {
        localStorage.setItem(
          'ticket:3:7',
          JSON.stringify({
            v: 1,
            clave: 'clave-p',
            elegido: 0,
            presupuestoId: 8,
            presupuestoNumero: 12,
            descuentoGlobalBp: 500,
            renglones: [
              {
                articuloId: 1,
                codigoInterno: 'COD-1',
                descripcion: 'Martillo',
                unidad: 'UNIDAD',
                precioCentavos: 150000,
                cantidadMilesimas: 1000,
                descuentoBp: 1000,
                stockActual: 10,
                stockMinimo: 5,
              },
            ],
          }),
        );
        await crear(cajaAbierta, FORMAS_BASE, 'VENDEDOR', 10);

        // 10 % y 5 % dan 14,5 %: lo autorizó quien emitió el presupuesto, y el servidor lo acepta.
        await tecla('F6');
        await descuentoDelDialogo('5');

        expect(dialogo()).toBeNull();
      });
    });

    it('Esc cierra el diálogo sin aplicar nada', async () => {
      await conUnArticulo();
      await tecla('F7');

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(totalGrande()).toBe('$ 1.500,00');
    });

    it('sin renglones los descuentos no abren nada: dicen por qué y los botones están apagados', async () => {
      await crear();

      await tecla('F7');
      await tecla('F6');

      expect(dialogo()).toBeNull();
      expect(normal(el().querySelector('.motivo-botonera')?.textContent)).toBe(
        'Agregá un renglón para usar esta acción.',
      );
      const botones = Array.from(el().querySelectorAll<HTMLButtonElement>('app-botonera button'));
      for (const texto of ['Descuento', 'Dto. total']) {
        expect(botones.find((b) => normal(b.textContent).includes(texto))!.getAttribute('aria-disabled')).toBe('true');
      }
    });

    it('un descuento inválido se avisa en el diálogo y no cierra', async () => {
      await conUnArticulo();
      await tecla('F7');

      await descuentoDelDialogo('100');

      expect(dialogo()).not.toBeNull();
      expect(normal(el().querySelector('app-dialogo-descuento [role="alert"]')?.textContent)).toBe(
        'El descuento tiene que estar entre 0 y 99,99 %.',
      );
      expect(totalGrande()).toBe('$ 1.500,00');
    });

    it('al cobrar manda los dos descuentos y el total ya descontado', async () => {
      await conUnArticulo();
      await tecla('F7');
      await descuentoDelDialogo('10');
      await tecla('F6');
      await descuentoDelDialogo('10');

      await tecla('F4');
      await tecla('Enter');

      expect(cobros).toHaveLength(1);
      expect(cobros[0].pedido.renglones[0].descuentoPorcentaje).toBe(10);
      expect(cobros[0].pedido.descuentoPorcentaje).toBe(10);
      expect(cobros[0].pedido.totalMostrado).toBe(1215);
    });
  });

  describe('pago mixto (GDS-07)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();

    async function tecla(key: string) {
      (document.activeElement as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }),
      );
      await refrescar();
    }

    async function cobrandoUnArticulo() {
      await crear();
      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1, { precioFinal: 1500 })));
      await tecla('F4');
      Array.from(el().querySelectorAll<HTMLButtonElement>('button[role="radio"]'))
        .find((b) => b.textContent!.includes('Mixto'))!
        .click();
      await refrescar();
    }

    function cargar(formaPagoId: number, texto: string) {
      const campoMonto = el().querySelector<HTMLInputElement>(`input[data-forma="${formaPagoId}"]`)!;
      campoMonto.value = texto;
      campoMonto.dispatchEvent(new Event('input', { bubbles: true }));
    }

    it('cobra con dos formas: manda la lista de pagos y no la forma única', async () => {
      await cobrandoUnArticulo();
      cargar(1, '500');
      await refrescar();

      await tecla('Enter');
      await tecla('Enter');

      expect(cobros).toHaveLength(1);
      expect(cobros[0].pedido.formaPagoId).toBeUndefined();
      expect(cobros[0].pedido.pagos).toEqual([
        { formaPagoId: 1, monto: 500 },
        { formaPagoId: 2, monto: 1000 },
      ]);
      expect(cobros[0].pedido.totalMostrado).toBe(1500);
    });

    it('no cobra si los pagos no suman el total', async () => {
      await cobrandoUnArticulo();
      cargar(1, '500');
      await refrescar();

      (el().querySelector('app-dialogo-cobro .cobrar') as HTMLButtonElement).click();
      await refrescar();

      expect(cobros).toHaveLength(0);
      expect(normal(el().querySelector('app-dialogo-cobro [role="alert"]')?.textContent)).toBe(
        'Los pagos tienen que sumar el total.',
      );
    });
  });

  describe('caja (CU-10)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const avisoCaja = () => el().querySelector<HTMLElement>('.aviso-caja');
    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const sinCaja: CajaVigente = { estado: 'SIN_CAJA', caja: null, efectivoPropuesto: 0, hayCierreAnterior: false };
    const deAyer: CajaVigente = {
      estado: 'DE_OTRO_DIA',
      caja: { id: 3, estado: 'DE_OTRO_DIA', fechaApertura: '2026-10-15T08:00:00', abiertaPor: 'Ana', efectivoInicial: 0 },
      efectivoPropuesto: 0,
      hayCierreAnterior: true,
    };

    async function tecla(key: string) {
      (document.activeElement as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }),
      );
      await refrescar();
    }

    async function conUnArticulo(vigente: CajaVigente) {
      await crear(vigente);
      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1, { precioFinal: 1500 })));
    }

    it('con la caja abierta hoy no hay aviso de caja y se cobra como siempre', async () => {
      await conUnArticulo(cajaAbierta);

      expect(avisoCaja()).toBeNull();
      await tecla('F4');
      expect(dialogo()).not.toBeNull();
    });

    it('sin caja abierta avisa «Abrí la caja para vender» con un botón que lleva a abrirla', async () => {
      await crear(sinCaja);

      expect(normal(avisoCaja()?.textContent)).toContain('Abrí la caja para vender.');
      const enlace = avisoCaja()!.querySelector<HTMLAnchorElement>('a')!;
      expect(normal(enlace.textContent)).toBe('Abrir la caja');
      expect(enlace.getAttribute('href')).toBe('/caja');
    });

    it('sin caja abierta F4 no abre el cobro: avisa que hay que abrirla y no manda nada', async () => {
      await conUnArticulo(sinCaja);

      await tecla('F4');

      expect(dialogo()).toBeNull();
      expect(cobros).toHaveLength(0);
      expect(normal(el().querySelector('.motivo-botonera')?.textContent)).toBe('Abrí la caja para vender.');
    });

    it('con la caja de otro día avisa que hay que cerrarla y no deja cobrar', async () => {
      await conUnArticulo(deAyer);

      expect(normal(avisoCaja()?.textContent)).toContain(
        'La caja del 15/10 sigue abierta. Cerrala antes de vender.',
      );
      expect(normal(avisoCaja()!.querySelector('a')!.textContent)).toBe('Cerrar la caja');
      await tecla('F4');
      expect(dialogo()).toBeNull();
      expect(cobros).toHaveLength(0);
    });

    it('sin caja el botón Cobrar queda apagado y al tocarlo dice que hay que abrir la caja', async () => {
      await conUnArticulo(sinCaja);

      const cobrar = Array.from(el().querySelectorAll<HTMLButtonElement>('app-botonera button')).find((b) =>
        normal(b.textContent).includes('Cobrar'),
      )!;
      expect(cobrar.getAttribute('aria-disabled')).toBe('true');
      expect(cobrar.hasAttribute('disabled')).toBe(false);

      cobrar.click();
      await refrescar();

      expect(dialogo()).toBeNull();
      expect(cobros).toHaveLength(0);
      expect(normal(el().querySelector('.motivo-botonera')?.textContent)).toBe('Abrí la caja para vender.');
    });

    it('si el backend rechaza por CAJA_CERRADA, cierra el cobro, conserva el ticket y muestra el aviso de caja', async () => {
      await conUnArticulo(cajaAbierta);
      await tecla('F4');
      await tecla('Enter');
      cajas.actual.mockReturnValue(of(sinCaja));

      cobros[0].respuesta.error(
        new HttpErrorResponse({
          status: 422,
          error: { status: 422, message: 'Abrí la caja para vender.', motivo: 'CAJA_CERRADA', articulos: [] },
        }),
      );
      await refrescar();

      expect(dialogo()).toBeNull();
      expect(el().querySelectorAll('app-ticket-lista .descripcion')).toHaveLength(1);
      expect(normal(el().querySelector('.aviso')?.textContent)).toBe('Abrí la caja para vender.');
      expect(normal(avisoCaja()?.textContent)).toContain('Abrí la caja para vender.');
    });
  });

  describe('presupuesto (GDS-13, PP-007)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const dialogoPresupuesto = () => el().querySelector<HTMLElement>('app-dialogo-presupuesto [role="dialog"]');
    const hoja = () => el().querySelector<HTMLElement>('app-documento-no-fiscal-imprimible [role="dialog"]');
    const aviso = () => normal(el().querySelector('.aviso')?.textContent);
    const totalGrande = () => normal(el().querySelector('app-totales-ticket .total')?.textContent);
    const sinCaja: CajaVigente = { estado: 'SIN_CAJA', caja: null, efectivoPropuesto: 0, hayCierreAnterior: false };

    async function tecla(key: string, destino: HTMLElement = document.activeElement as HTMLElement) {
      const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      destino.dispatchEvent(evento);
      await refrescar();
      return evento;
    }

    async function conMartillos(vigente: CajaVigente = cajaAbierta) {
      await crear(vigente);
      for (let i = 0; i < 2; i++) {
        await escribir('M');
        await enter();
        await responder(llamadas.length - 1, exacto(articulo(1, { descripcion: 'Martillo', precioFinal: 1500 })));
      }
    }

    function presupuestoGuardado(numero: number, total: number): PresupuestoEmitido {
      return {
        presupuesto: {
          presupuestoId: 8,
          numero,
          estado: 'PENDIENTE',
          fecha: '2026-10-18T10:00:00',
          vence: '2026-11-02',
          vencido: false,
          subtotal: total,
          descuentoPorcentaje: 0,
          descuento: 0,
          neto: 0,
          iva: 0,
          total,
          renglones: [],
        },
        repetido: false,
      };
    }

    async function guardarOk(indice: number, p: PresupuestoEmitido) {
      guardados[indice].respuesta.next(p);
      guardados[indice].respuesta.complete();
      await refrescar();
    }

    async function fallarGuardado(indice: number, status: number, cuerpo: unknown = null) {
      guardados[indice].respuesta.error(new HttpErrorResponse({ status, error: cuerpo }));
      await refrescar();
    }

    const rechazo = (status: number, motivo: string, articulos: unknown[], message = 'rechazo') => ({
      status,
      message,
      timestamp: '2026-10-18T10:00:00',
      motivo,
      articulos,
    });

    it('F8 con renglones abre «Guardar presupuesto», anula la acción del navegador y no manda nada todavía', async () => {
      await conMartillos();

      const evento = await tecla('F8');

      expect(evento.defaultPrevented).toBe(true);
      expect(normal(dialogoPresupuesto()?.querySelector('h2')?.textContent)).toBe('Guardar presupuesto');
      expect(guardados).toHaveLength(0);
    });

    it('el botón F8 Presupuesto abre lo mismo, y está apagado con el ticket vacío', async () => {
      await crear();
      const boton = () =>
        Array.from(el().querySelectorAll<HTMLButtonElement>('app-botonera button')).find((b) =>
          normal(b.textContent).includes('Presupuesto'),
        )!;
      expect(boton().getAttribute('aria-disabled')).toBe('true');

      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1)));
      expect(boton().hasAttribute('aria-disabled')).toBe(false);
      boton().click();
      await refrescar();

      expect(dialogoPresupuesto()).not.toBeNull();
    });

    it('no exige caja abierta: sin caja F8 abre el diálogo y el botón está habilitado', async () => {
      await conMartillos(sinCaja);

      await tecla('F8');

      expect(dialogoPresupuesto()).not.toBeNull();
    });

    it('Enter envía el presupuesto con la clave del ticket, el total sin recargo y 15 días', async () => {
      await conMartillos();

      await tecla('F8');
      await tecla('Enter');

      expect(guardados).toHaveLength(1);
      expect(guardados[0].pedido).toEqual({
        clave: 'clave-1',
        renglones: [{ articuloId: 1, cantidad: 2, precioUnitario: 1500 }],
        totalMostrado: 3000,
        diasValidez: 15,
      });
    });

    it('manda el cliente y las observaciones que se escribieron en el diálogo', async () => {
      await conMartillos();
      await tecla('F8');
      const cliente = dialogoPresupuesto()!.querySelector<HTMLInputElement>('input[name="cliente"]')!;
      cliente.value = 'Obra Perez';
      cliente.dispatchEvent(new Event('input'));
      const validez = dialogoPresupuesto()!.querySelector<HTMLInputElement>('input[name="validez"]')!;
      validez.value = '30';
      validez.dispatchEvent(new Event('input'));

      await tecla('Enter');

      expect(guardados[0].pedido).toMatchObject({ clienteNombre: 'Obra Perez', diasValidez: 30 });
    });

    it('el pedido lleva los descuentos del ticket y el total ya descontado', async () => {
      await conMartillos();
      await tecla('F6');
      const campoDescuento = el().querySelector<HTMLInputElement>('app-dialogo-descuento input')!;
      campoDescuento.value = '10';
      campoDescuento.dispatchEvent(new Event('input'));
      await refrescar();
      await tecla('Enter');

      await tecla('F8');
      await tecla('Enter');

      expect(guardados[0].pedido.descuentoPorcentaje).toBe(10);
      expect(guardados[0].pedido.totalMostrado).toBe(2700);
    });

    it('al guardarse vacía el ticket, avisa el número y abre la hoja; Esc la cierra y devuelve el foco al buscador', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await guardarOk(0, presupuestoGuardado(12, 3000));

      expect(dialogoPresupuesto()).toBeNull();
      expect(hoja()).not.toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
      expect(aviso()).toBe('Presupuesto N.º 12 guardado: $ 3.000,00 Ver el presupuesto');
      expect(localStorage.getItem('ticket:3:7')).toBeNull();

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(document.activeElement).toBe(campo());
    });

    it('el aviso de presupuesto guardado queda con su link hasta el próximo renglón (se imprime o se abre después)', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');
      await guardarOk(0, presupuestoGuardado(12, 3000));
      await tecla('Escape');

      await avanzar(5000);

      expect(aviso()).toContain('Presupuesto N.º 12 guardado');
      const link = el().querySelector<HTMLAnchorElement>('.aviso a')!;
      expect(normal(link.textContent)).toBe('Ver el presupuesto');
      expect(link.getAttribute('href')).toBe('/presupuestos/8');
    });

    describe('la hoja al guardar (informacion-y-links, S1)', () => {
      let imprimir: ReturnType<typeof vi.spyOn>;
      beforeEach(() => (imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined)));
      afterEach(() => imprimir.mockRestore());

      it('es la hoja del presupuesto guardado, con los datos de la ferretería de hoy: Enter imprime', async () => {
        await conMartillos();
        await tecla('F8');
        await tecla('Enter');
        await guardarOk(0, presupuestoGuardado(12, 3000));

        expect(leerEmisor).toHaveBeenCalledTimes(1);
        expect(normal(hoja()!.querySelector('h2')!.textContent)).toBe('Presupuesto N.º 12');
        await tecla('Enter', hoja()!);

        expect(imprimir).toHaveBeenCalledTimes(1);
      });

      it('si falla el guardado no se abre la hoja', async () => {
        await conMartillos();
        await tecla('F8');
        await tecla('Enter');

        await fallarGuardado(0, 500);

        expect(hoja()).toBeNull();
        expect(leerEmisor).not.toHaveBeenCalled();
        expect(dialogoPresupuesto()).not.toBeNull();
      });

      it('sin los datos de la ferretería el presupuesto queda guardado, sin hoja, y avisa que se imprime desde su link', async () => {
        await conMartillos();
        leerEmisor.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
        await tecla('F8');
        await tecla('Enter');

        await guardarOk(0, presupuestoGuardado(12, 3000));

        expect(dialogo()).toBeNull();
        expect(renglonesTicket()).toHaveLength(0);
        expect(aviso()).toContain('Presupuesto N.º 12 guardado');
        expect(normal(el().querySelector('.aviso.advertencia')?.textContent)).toBe(
          'No se pudo abrir la hoja para imprimirlo: abrilo con «Ver el presupuesto» e imprimilo desde ahí.',
        );
        expect(document.activeElement).toBe(campo());
      });
    });

    it('con el POST en curso Esc no cierra el diálogo y Enter no manda otro', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await tecla('Escape');
      await tecla('Enter');

      expect(dialogoPresupuesto()).not.toBeNull();
      expect(guardados).toHaveLength(1);
    });

    it('Esc cierra el diálogo sin guardar y conserva el ticket', async () => {
      await conMartillos();
      await tecla('F8');

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(guardados).toHaveLength(0);
      expect(renglonesTicket()).toHaveLength(1);
    });

    it('con otro diálogo abierto F8 no abre el del presupuesto', async () => {
      await conMartillos();
      await tecla('F4');

      await tecla('F8');

      expect(dialogoPresupuesto()).toBeNull();
      expect(dialogo()).not.toBeNull();
      expect(guardados).toHaveLength(0);
    });

    it('si cambió un precio: actualiza el ticket, avisa en el diálogo y al volver a guardar manda el total nuevo con la misma clave', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await fallarGuardado(0, 409, {
        ...rechazo(409, 'PRECIOS_CAMBIADOS', [{ articuloId: 1, descripcion: 'Martillo', precioMostrado: 1500, precioActual: 1800 }]),
        total: 3600,
      });

      expect(dialogoPresupuesto()).not.toBeNull();
      expect(normal(dialogoPresupuesto()!.querySelector('[role="status"]')?.textContent)).toBe(
        'Cambió el precio de "Martillo": ahora $ 1.800,00.',
      );
      expect(totalGrande()).toBe('$ 3.600,00');
      await tecla('Enter', dialogoPresupuesto()!.querySelector('input')!);
      expect(guardados).toHaveLength(2);
      expect(guardados[1].pedido.clave).toBe('clave-1');
      expect(guardados[1].pedido.totalMostrado).toBe(3600);
    });

    it('si un artículo ya no se puede vender: cierra el diálogo, conserva el ticket, marca el renglón y avisa', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await fallarGuardado(
        0,
        422,
        rechazo(422, 'ARTICULO_DADO_DE_BAJA', [{ articuloId: 1, descripcion: 'Martillo' }], '"Martillo" fue dado de baja. Sacalo del ticket para seguir.'),
      );

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(1);
      expect(renglonesTicket()[0].textContent).toContain('Artículo dado de baja');
      expect(aviso()).toBe('"Martillo" fue dado de baja. Sacalo del ticket para seguir.');
    });

    it('si la clave ya se usó con otro contenido: cierra el diálogo y avisa con el mensaje del servidor', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await fallarGuardado(0, 422, rechazo(422, 'CLAVE_CON_OTRO_CONTENIDO', [], 'Este presupuesto ya se emitió con otros datos'));

      expect(dialogo()).toBeNull();
      expect(aviso()).toBe('Este presupuesto ya se emitió con otros datos');
    });

    it('un 400 muestra el mensaje del servidor dentro del diálogo y se puede volver a intentar', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await fallarGuardado(0, 400, { status: 400, message: 'Datos inválidos', errors: { clienteNombre: 'El nombre del cliente admite hasta 80 caracteres.' } });

      expect(normal(dialogoPresupuesto()!.querySelector('[role="status"]')?.textContent)).toBe(
        'El nombre del cliente admite hasta 80 caracteres.',
      );
      await tecla('Enter', dialogoPresupuesto()!.querySelector('input')!);
      expect(guardados).toHaveLength(2);
    });

    it('un error de red deja el diálogo abierto con un aviso para reintentar y no pierde el ticket', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await fallarGuardado(0, 500);

      expect(normal(dialogoPresupuesto()!.querySelector('[role="status"]')?.textContent)).toBe(
        'No se pudo guardar el presupuesto. Probá de nuevo.',
      );
      expect(renglonesTicket()).toHaveLength(1);
    });

    it('un 503 muestra el message del servidor dentro del diálogo, conserva el ticket y el reintento usa la misma clave', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await fallarGuardado(0, 503, { status: 503, message: 'El sistema está ocupado, reintentá.' });

      expect(normal(dialogoPresupuesto()!.querySelector('[role="status"]')?.textContent)).toBe(
        'El sistema está ocupado, reintentá.',
      );
      expect(renglonesTicket()).toHaveLength(1);
      await tecla('Enter', dialogoPresupuesto()!.querySelector('input')!);
      expect(guardados).toHaveLength(2);
      expect(guardados[1].pedido.clave).toBe('clave-1');
    });

    it('un 503 sin cuerpo cae en el texto fijo «El sistema está ocupado, reintentá.»', async () => {
      await conMartillos();
      await tecla('F8');
      await tecla('Enter');

      await fallarGuardado(0, 503, null);

      expect(normal(dialogoPresupuesto()!.querySelector('[role="status"]')?.textContent)).toBe(
        'El sistema está ocupado, reintentá.',
      );
    });
  });

  describe('remito (G55, RM-007)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const dialogoRemito = () => el().querySelector<HTMLElement>('app-dialogo-remito [role="dialog"]');
    const hoja = () => el().querySelector<HTMLElement>('app-documento-no-fiscal-imprimible [role="dialog"]');
    const aviso = () => normal(el().querySelector('.aviso')?.textContent);
    const advertencia = () => normal(el().querySelector('.aviso.advertencia')?.textContent);
    const totalGrande = () => normal(el().querySelector('app-totales-ticket .total')?.textContent);
    const sinCaja: CajaVigente = { estado: 'SIN_CAJA', caja: null, efectivoPropuesto: 0, hayCierreAnterior: false };
    const botonRemito = () =>
      Array.from(el().querySelectorAll<HTMLButtonElement>('app-botonera button')).find((b) =>
        normal(b.textContent).includes('Remito'),
      )!;

    async function tecla(key: string, destino: HTMLElement = document.activeElement as HTMLElement) {
      const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      destino.dispatchEvent(evento);
      await refrescar();
      return evento;
    }

    async function conMartillos(vigente: CajaVigente = cajaAbierta) {
      await crear(vigente);
      for (let i = 0; i < 2; i++) {
        await escribir('M');
        await enter();
        await responder(llamadas.length - 1, exacto(articulo(1, { descripcion: 'Martillo', precioFinal: 1500 })));
      }
    }

    async function abrirRemito() {
      botonRemito().click();
      await refrescar();
    }

    function remitoEmitido(numero: number, total: number, sinStock: RemitoEmitido['remito']['sinStock'] = []): RemitoEmitido {
      return {
        remito: {
          remitoId: 8,
          numero,
          estado: 'EMITIDO',
          fecha: '2026-10-18T10:00:00',
          subtotal: total,
          descuentoPorcentaje: 0,
          descuento: 0,
          neto: 0,
          iva: 0,
          total,
          renglones: [],
          sinStock,
        },
        repetido: false,
      };
    }

    async function emitirOk(indice: number, r: RemitoEmitido) {
      emitidos[indice].respuesta.next(r);
      emitidos[indice].respuesta.complete();
      await refrescar();
    }

    async function fallarEmision(indice: number, status: number, cuerpo: unknown = null) {
      emitidos[indice].respuesta.error(new HttpErrorResponse({ status, error: cuerpo }));
      await refrescar();
    }

    const rechazo = (status: number, motivo: string, articulos: unknown[], message = 'rechazo') => ({
      status,
      message,
      timestamp: '2026-10-18T10:00:00',
      motivo,
      articulos,
    });

    it('el botón Remito abre «Emitir remito» y no manda nada todavía; está apagado con el ticket vacío', async () => {
      await crear();
      expect(botonRemito().getAttribute('aria-disabled')).toBe('true');

      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1)));
      expect(botonRemito().hasAttribute('aria-disabled')).toBe(false);
      await abrirRemito();

      expect(normal(dialogoRemito()?.querySelector('h2')?.textContent)).toBe('Emitir remito');
      expect(emitidos).toHaveLength(0);
    });

    it('F8 sigue siendo el presupuesto: ninguna tecla abre el remito', async () => {
      await conMartillos();

      await tecla('F8');

      expect(dialogoRemito()).toBeNull();
      expect(el().querySelector('app-dialogo-presupuesto')).not.toBeNull();
    });

    it('no exige caja abierta: sin caja el botón está habilitado y abre el diálogo', async () => {
      await conMartillos(sinCaja);

      await abrirRemito();

      expect(dialogoRemito()).not.toBeNull();
    });

    it('Enter envía el remito con la clave del ticket y el total sin recargo, sin validez', async () => {
      await conMartillos();

      await abrirRemito();
      await tecla('Enter');

      expect(emitidos).toHaveLength(1);
      expect(emitidos[0].pedido).toEqual({
        clave: 'clave-1',
        renglones: [{ articuloId: 1, cantidad: 2, precioUnitario: 1500 }],
        totalMostrado: 3000,
      });
    });

    it('manda el cliente y las observaciones que se escribieron en el diálogo', async () => {
      await conMartillos();
      await abrirRemito();
      const cliente = dialogoRemito()!.querySelector<HTMLInputElement>('input[name="cliente"]')!;
      cliente.value = 'Obra Perez';
      cliente.dispatchEvent(new Event('input'));
      const observaciones = dialogoRemito()!.querySelector<HTMLInputElement>('input[name="observaciones"]')!;
      observaciones.value = 'Entrega el viernes';
      observaciones.dispatchEvent(new Event('input'));

      await tecla('Enter');

      expect(emitidos[0].pedido).toMatchObject({ clienteNombre: 'Obra Perez', observaciones: 'Entrega el viernes' });
    });

    it('el pedido lleva los descuentos del ticket y el total ya descontado', async () => {
      await conMartillos();
      await tecla('F6');
      const campoDescuento = el().querySelector<HTMLInputElement>('app-dialogo-descuento input')!;
      campoDescuento.value = '10';
      campoDescuento.dispatchEvent(new Event('input'));
      await refrescar();
      await tecla('Enter');

      await abrirRemito();
      await tecla('Enter');

      expect(emitidos[0].pedido.descuentoPorcentaje).toBe(10);
      expect(emitidos[0].pedido.totalMostrado).toBe(2700);
    });

    it('al emitirse vacía el ticket, avisa el número y abre la hoja; Esc la cierra y devuelve el foco al buscador', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await emitirOk(0, remitoEmitido(5, 3000));

      expect(dialogoRemito()).toBeNull();
      expect(hoja()).not.toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
      expect(aviso()).toBe('Remito N.º 5 emitido: $ 3.000,00 Ver el remito');
      expect(localStorage.getItem('ticket:3:7')).toBeNull();

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(document.activeElement).toBe(campo());
    });

    it('el aviso de remito emitido queda con su link hasta el próximo renglón (se imprime o se abre después)', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');
      await emitirOk(0, remitoEmitido(5, 3000));
      await tecla('Escape');

      await avanzar(5000);

      expect(aviso()).toContain('Remito N.º 5 emitido');
      const link = el().querySelector<HTMLAnchorElement>('.aviso a')!;
      expect(normal(link.textContent)).toBe('Ver el remito');
      expect(link.getAttribute('href')).toBe('/remitos/8');
    });

    describe('la hoja al emitir (informacion-y-links, S1)', () => {
      let imprimir: ReturnType<typeof vi.spyOn>;
      beforeEach(() => (imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined)));
      afterEach(() => imprimir.mockRestore());

      it('es la hoja del remito emitido, con los datos de la ferretería de hoy: Enter imprime', async () => {
        await conMartillos();
        await abrirRemito();
        await tecla('Enter');
        await emitirOk(0, remitoEmitido(5, 3000));

        expect(leerEmisor).toHaveBeenCalledTimes(1);
        expect(normal(hoja()!.querySelector('h2')!.textContent)).toBe('Remito N.º 5');
        await tecla('Enter', hoja()!);

        expect(imprimir).toHaveBeenCalledTimes(1);
      });

      it('si falla la emisión no se abre la hoja', async () => {
        await conMartillos();
        await abrirRemito();
        await tecla('Enter');

        await fallarEmision(0, 500);

        expect(hoja()).toBeNull();
        expect(leerEmisor).not.toHaveBeenCalled();
        expect(dialogoRemito()).not.toBeNull();
      });

      it('sin los datos de la ferretería el remito queda emitido, sin hoja, y avisa que se imprime desde su link', async () => {
        await conMartillos();
        leerEmisor.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
        await abrirRemito();
        await tecla('Enter');

        await emitirOk(0, remitoEmitido(5, 3000));

        expect(dialogo()).toBeNull();
        expect(aviso()).toContain('Remito N.º 5 emitido');
        expect(advertencia()).toBe(
          'No se pudo abrir la hoja para imprimirlo: abrilo con «Ver el remito» e imprimilo desde ahí.',
        );
        expect(document.activeElement).toBe(campo());
      });

      it('con sin stock, la hoja se abre igual y el aviso de sin stock queda', async () => {
        await conMartillos();
        await abrirRemito();
        await tecla('Enter');

        await emitirOk(0, remitoEmitido(5, 3000, [{ articuloId: 1, descripcion: 'Martillo', stockActual: -2 }]));

        expect(hoja()).not.toBeNull();
        expect(advertencia()).toBe('Sin stock: "Martillo" quedó en -2.');
      });
    });

    it('si quedó un artículo sin stock lo avisa aparte y el aviso queda hasta el próximo renglón', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await emitirOk(0, remitoEmitido(5, 3000, [{ articuloId: 1, descripcion: 'Martillo', stockActual: -1.5 }]));
      await avanzar(6000);

      expect(aviso()).toContain('Remito N.º 5 emitido');
      expect(advertencia()).toBe('Sin stock: "Martillo" quedó en -1,5.');
    });

    it('con el POST en curso Esc no cierra el diálogo y Enter no manda otro', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await tecla('Escape');
      await tecla('Enter');

      expect(dialogoRemito()).not.toBeNull();
      expect(emitidos).toHaveLength(1);
    });

    it('Esc cierra el diálogo sin emitir y conserva el ticket', async () => {
      await conMartillos();
      await abrirRemito();

      await tecla('Escape');

      expect(dialogo()).toBeNull();
      expect(emitidos).toHaveLength(0);
      expect(renglonesTicket()).toHaveLength(1);
    });

    it('con otro diálogo abierto el botón Remito no abre el del remito', async () => {
      await conMartillos();
      await tecla('F4');

      botonRemito().click();
      await refrescar();

      expect(dialogoRemito()).toBeNull();
      expect(dialogo()).not.toBeNull();
      expect(emitidos).toHaveLength(0);
    });

    it('si cambió un precio: actualiza el ticket, avisa en el diálogo y al volver a emitir manda el total nuevo con la misma clave', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await fallarEmision(0, 409, {
        ...rechazo(409, 'PRECIOS_CAMBIADOS', [{ articuloId: 1, descripcion: 'Martillo', precioMostrado: 1500, precioActual: 1800 }]),
        total: 3600,
      });

      expect(dialogoRemito()).not.toBeNull();
      expect(normal(dialogoRemito()!.querySelector('[role="status"]')?.textContent)).toBe(
        'Cambió el precio de "Martillo": ahora $ 1.800,00.',
      );
      expect(totalGrande()).toBe('$ 3.600,00');
      await tecla('Enter', dialogoRemito()!.querySelector('input')!);
      expect(emitidos).toHaveLength(2);
      expect(emitidos[1].pedido.clave).toBe('clave-1');
      expect(emitidos[1].pedido.totalMostrado).toBe(3600);
    });

    it('si un artículo ya no se puede vender: cierra el diálogo, conserva el ticket, marca el renglón y avisa', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await fallarEmision(
        0,
        422,
        rechazo(422, 'ARTICULO_DADO_DE_BAJA', [{ articuloId: 1, descripcion: 'Martillo' }], '"Martillo" fue dado de baja. Sacalo del ticket para seguir.'),
      );

      expect(dialogo()).toBeNull();
      expect(renglonesTicket()).toHaveLength(1);
      expect(renglonesTicket()[0].textContent).toContain('Artículo dado de baja');
      expect(aviso()).toBe('"Martillo" fue dado de baja. Sacalo del ticket para seguir.');
    });

    it('si la clave ya se usó con otro contenido: cierra el diálogo y avisa con el mensaje del servidor', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await fallarEmision(0, 422, rechazo(422, 'CLAVE_CON_OTRO_CONTENIDO', [], 'Este remito ya se emitió con otros datos'));

      expect(dialogo()).toBeNull();
      expect(aviso()).toBe('Este remito ya se emitió con otros datos');
    });

    it('un 400 muestra el mensaje del servidor dentro del diálogo y se puede volver a intentar', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await fallarEmision(0, 400, { status: 400, message: 'Datos inválidos', errors: { clienteNombre: 'El nombre del cliente admite hasta 80 caracteres.' } });

      expect(normal(dialogoRemito()!.querySelector('[role="status"]')?.textContent)).toBe(
        'El nombre del cliente admite hasta 80 caracteres.',
      );
      await tecla('Enter', dialogoRemito()!.querySelector('input')!);
      expect(emitidos).toHaveLength(2);
    });

    it('un error de red deja el diálogo abierto con un aviso para reintentar y no pierde el ticket', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await fallarEmision(0, 500);

      expect(normal(dialogoRemito()!.querySelector('[role="status"]')?.textContent)).toBe(
        'No se pudo emitir el remito. Probá de nuevo.',
      );
      expect(renglonesTicket()).toHaveLength(1);
    });

    it('un 503 muestra el message del servidor dentro del diálogo, conserva el ticket y el reintento usa la misma clave', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await fallarEmision(0, 503, { status: 503, message: 'El sistema está ocupado, reintentá.' });

      expect(normal(dialogoRemito()!.querySelector('[role="status"]')?.textContent)).toBe(
        'El sistema está ocupado, reintentá.',
      );
      expect(renglonesTicket()).toHaveLength(1);
      await tecla('Enter', dialogoRemito()!.querySelector('input')!);
      expect(emitidos).toHaveLength(2);
      expect(emitidos[1].pedido.clave).toBe('clave-1');
    });

    it('un 503 sin cuerpo cae en el texto fijo «El sistema está ocupado, reintentá.»', async () => {
      await conMartillos();
      await abrirRemito();
      await tecla('Enter');

      await fallarEmision(0, 503, null);

      expect(normal(dialogoRemito()!.querySelector('[role="status"]')?.textContent)).toBe(
        'El sistema está ocupado, reintentá.',
      );
    });
  });

  describe('venta desde un presupuesto (GDS-13, PP-006)', () => {
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const aviso = () => normal(el().querySelector('.aviso')?.textContent);
    const origen = () => el().querySelector('.origen');

    async function tecla(key: string) {
      (document.activeElement as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }),
      );
      await refrescar();
    }

    /** Un ticket que dejó «Pasar a venta»: 2 martillos de $ 1.500,00, del presupuesto N.º 12. */
    function ticketDelPresupuesto(extra: Record<string, unknown> = { presupuestoId: 8, presupuestoNumero: 12 }) {
      localStorage.setItem(
        'ticket:3:7',
        JSON.stringify({
          v: 1,
          clave: 'clave-p',
          elegido: 0,
          renglones: [
            {
              articuloId: 1,
              codigoInterno: 'COD-1',
              descripcion: 'Martillo',
              unidad: 'UNIDAD',
              precioCentavos: 150000,
              cantidadMilesimas: 2000,
              stockActual: 10,
              stockMinimo: 5,
            },
          ],
          ...extra,
        }),
      );
    }

    async function fallarCobro(status: number, cuerpo: unknown) {
      cobros[0].respuesta.error(new HttpErrorResponse({ status, error: cuerpo }));
      await refrescar();
    }

    it('un ticket que viene de un presupuesto dice de cuál', async () => {
      ticketDelPresupuesto();

      await crear();

      expect(normal(origen()?.textContent)).toBe('Venta del presupuesto N.º 12');
    });

    it('un ticket común no dice nada de presupuestos', async () => {
      ticketDelPresupuesto({});

      await crear();

      expect(origen()).toBeNull();
    });

    it('al cobrar manda el presupuesto del que sale la venta con la clave del ticket', async () => {
      ticketDelPresupuesto();
      await crear();

      await tecla('F4');
      await tecla('Enter');

      expect(cobros[0].pedido).toEqual({
        clave: 'clave-p',
        renglones: [{ articuloId: 1, cantidad: 2, precioUnitario: 1500 }],
        totalMostrado: 3000,
        formaPagoId: 1,
        presupuestoId: 8,
      });
    });

    it('al cobrarse el ticket se vacía y deja de decir de qué presupuesto era', async () => {
      ticketDelPresupuesto();
      await crear();
      await tecla('F4');
      await tecla('Enter');

      cobros[0].respuesta.next({
        venta: { ventaId: 99, numero: 5, total: 3000, fecha: '2026-10-18T10:00:00', renglones: [], sinStock: [], presupuestoId: 8, facturable: true },
        repetida: false,
      } as VentaCobrada);
      cobros[0].respuesta.complete();
      await refrescar();

      expect(origen()).toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
    });

    it.each([
      [409, 'Ese presupuesto fue rechazado.'],
      [409, 'Ese presupuesto ya se facturó.'],
      [404, 'Presupuesto no encontrado'],
    ])(
      'si el servidor responde %i «%s»: cierra el cobro, suelta el presupuesto, conserva el ticket y avisa',
      async (status, mensaje) => {
        ticketDelPresupuesto();
        await crear();
        await tecla('F4');
        await tecla('Enter');

        await fallarCobro(status, { status, message: mensaje, timestamp: '2026-10-18T10:00:00' });

        expect(dialogo()).toBeNull();
        expect(origen()).toBeNull();
        expect(renglonesTicket()).toHaveLength(1);
        expect(aviso()).toBe(`${mensaje} El ticket sigue como una venta común.`);
      },
    );

    it('después de soltarlo, el reintento vende sin el presupuesto y con la misma clave', async () => {
      ticketDelPresupuesto();
      await crear();
      await tecla('F4');
      await tecla('Enter');
      await fallarCobro(409, { status: 409, message: 'Ese presupuesto fue rechazado.' });

      await tecla('F4');
      await tecla('Enter');

      expect(cobros).toHaveLength(2);
      expect(cobros[1].pedido.presupuestoId).toBeUndefined();
      expect(cobros[1].pedido.clave).toBe('clave-p');
    });

    it('un 409 en un ticket que no viene de un presupuesto sigue siendo un error de cobro común', async () => {
      ticketDelPresupuesto({});
      await crear();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(409, { status: 409, message: 'Conflicto' });

      expect(dialogo()).not.toBeNull();
      expect(el().textContent).toContain('No se pudo cobrar. Probá de nuevo.');
    });
  });

  describe('venta desde un remito (RM-006)', () => {
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const dialogo = () => el().querySelector<HTMLElement>('[role="dialog"], [role="alertdialog"]');
    const aviso = () => normal(el().querySelector('.aviso')?.textContent);
    const origen = () => el().querySelector('.origen');

    async function tecla(key: string) {
      (document.activeElement as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }),
      );
      await refrescar();
    }

    /** Un ticket que dejó «Pasar a venta»: 2 martillos de $ 1.500,00, del remito N.º 5. */
    function ticketDelRemito(extra: Record<string, unknown> = { remitoId: 8, remitoNumero: 5 }) {
      localStorage.setItem(
        'ticket:3:7',
        JSON.stringify({
          v: 1,
          clave: 'clave-r',
          elegido: 0,
          renglones: [
            {
              articuloId: 1,
              codigoInterno: 'COD-1',
              descripcion: 'Martillo',
              unidad: 'UNIDAD',
              precioCentavos: 150000,
              cantidadMilesimas: 2000,
              stockActual: 10,
              stockMinimo: 5,
            },
          ],
          ...extra,
        }),
      );
    }

    async function fallarCobro(status: number, cuerpo: unknown) {
      cobros[0].respuesta.error(new HttpErrorResponse({ status, error: cuerpo }));
      await refrescar();
    }

    it('un ticket que viene de un remito dice de cuál', async () => {
      ticketDelRemito();

      await crear();

      expect(normal(origen()?.textContent)).toBe('Venta del remito N.º 5');
    });

    it('un ticket común no dice nada de remitos', async () => {
      ticketDelRemito({});

      await crear();

      expect(origen()).toBeNull();
    });

    it('al cobrar manda el remito del que sale la venta con la clave del ticket', async () => {
      ticketDelRemito();
      await crear();

      await tecla('F4');
      await tecla('Enter');

      expect(cobros[0].pedido).toEqual({
        clave: 'clave-r',
        renglones: [{ articuloId: 1, cantidad: 2, precioUnitario: 1500 }],
        totalMostrado: 3000,
        formaPagoId: 1,
        remitoId: 8,
      });
    });

    it('al cobrarse el ticket se vacía y deja de decir de qué remito era', async () => {
      ticketDelRemito();
      await crear();
      await tecla('F4');
      await tecla('Enter');

      cobros[0].respuesta.next({
        venta: { ventaId: 99, numero: 5, total: 3000, fecha: '2026-10-18T10:00:00', renglones: [], sinStock: [], remitoId: 8, facturable: true },
        repetida: false,
      } as VentaCobrada);
      cobros[0].respuesta.complete();
      await refrescar();

      expect(origen()).toBeNull();
      expect(renglonesTicket()).toHaveLength(0);
    });

    it.each([
      [409, 'Ese remito fue anulado.'],
      [409, 'Ese remito ya se facturó.'],
      [404, 'Remito no encontrado'],
    ])(
      'si el servidor responde %i «%s»: cierra el cobro, suelta el remito, conserva el ticket y avisa',
      async (status, mensaje) => {
        ticketDelRemito();
        await crear();
        await tecla('F4');
        await tecla('Enter');

        await fallarCobro(status, { status, message: mensaje, timestamp: '2026-10-18T10:00:00' });

        expect(dialogo()).toBeNull();
        expect(origen()).toBeNull();
        expect(renglonesTicket()).toHaveLength(1);
        expect(aviso()).toBe(`${mensaje} El ticket sigue como una venta común.`);
      },
    );

    it('un 403 por devolver stock siendo Vendedor muestra el mensaje en el cobro y conserva el remito y el ticket', async () => {
      const mensaje = 'Facturar menos de lo que entregó el remito devuelve stock: lo tiene que hacer el Dueño.';
      ticketDelRemito();
      await crear();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(403, { status: 403, message: mensaje, timestamp: '2026-11-02T10:00:00' });

      expect(dialogo()).not.toBeNull();
      expect(normal(el().querySelector('app-dialogo-cobro .aviso')?.textContent)).toBe(mensaje);
      expect(normal(origen()?.textContent)).toBe('Venta del remito N.º 5');
      expect(renglonesTicket()).toHaveLength(1);
    });

    it('después de soltarlo, el reintento vende sin el remito y con la misma clave', async () => {
      ticketDelRemito();
      await crear();
      await tecla('F4');
      await tecla('Enter');
      await fallarCobro(409, { status: 409, message: 'Ese remito fue anulado.' });

      await tecla('F4');
      await tecla('Enter');

      expect(cobros).toHaveLength(2);
      expect(cobros[1].pedido.remitoId).toBeUndefined();
      expect(cobros[1].pedido.clave).toBe('clave-r');
    });

    it('un 409 en un ticket que no viene de un remito sigue siendo un error de cobro común', async () => {
      ticketDelRemito({});
      await crear();
      await tecla('F4');
      await tecla('Enter');

      await fallarCobro(409, { status: 409, message: 'Conflicto' });

      expect(dialogo()).not.toBeNull();
      expect(el().textContent).toContain('No se pudo cobrar. Probá de nuevo.');
    });
  });

  describe('cliente (F9, CL-005)', () => {
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const dialogoCliente = () => el().querySelector<HTMLElement>('app-dialogo-cliente [role="dialog"]');
    const clienteDelTicket = () => normal(el().querySelector('.cliente-ticket')?.textContent);

    async function tecla(key: string, destino: HTMLElement = document.activeElement as HTMLElement) {
      const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      destino.dispatchEvent(evento);
      await refrescar();
      return evento;
    }

    async function conMartillos() {
      await crear();
      for (let i = 0; i < 2; i++) {
        await escribir('M');
        await enter();
        await responder(llamadas.length - 1, exacto(articulo(1, { descripcion: 'Martillo', precioFinal: 1500 })));
      }
    }

    /** Abre F9 y elige al segundo de la lista (Ferretería Norte SRL) con ↓ y Enter. */
    async function elegirNorte() {
      await tecla('F9');
      await tecla('ArrowDown');
      await tecla('Enter');
    }

    it('F9 abre «Cliente», anula la acción del navegador y también sirve con el ticket vacío', async () => {
      await crear();

      const evento = await tecla('F9');

      expect(evento.defaultPrevented).toBe(true);
      expect(normal(dialogoCliente()?.querySelector('h2')?.textContent)).toBe('Cliente');
    });

    it('el botón F9 Cliente abre lo mismo y está siempre habilitado', async () => {
      await crear();
      const boton = Array.from(el().querySelectorAll<HTMLButtonElement>('app-botonera button')).find((b) =>
        normal(b.textContent).includes('Cliente'),
      )!;
      expect(boton.hasAttribute('aria-disabled')).toBe(false);

      boton.click();
      await refrescar();

      expect(dialogoCliente()).not.toBeNull();
    });

    it('con un diálogo abierto, F9 no abre otro', async () => {
      await conMartillos();
      await tecla('F4');
      expect(el().querySelector('app-dialogo-cobro')).not.toBeNull();

      await tecla('F9', el().querySelector('app-dialogo-cobro [role="dialog"]') as HTMLElement);

      expect(dialogoCliente()).toBeNull();
    });

    it('sin cliente no hay renglón de cliente: la venta es a Consumidor Final', async () => {
      await conMartillos();

      expect(el().querySelector('.cliente-ticket')).toBeNull();
    });

    it('elegir un cliente cierra el diálogo, lo muestra en el ticket y devuelve el foco al buscador', async () => {
      await conMartillos();

      await elegirNorte();

      expect(dialogoCliente()).toBeNull();
      expect(clienteDelTicket()).toContain('Cliente: Ferretería Norte SRL');
      expect(document.activeElement).toBe(campo());
    });

    it('el cliente se puede elegir antes del primer renglón y queda al agregar artículos', async () => {
      await crear();
      await elegirNorte();

      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1)));

      expect(clienteDelTicket()).toContain('Ferretería Norte SRL');
    });

    it('«Quitar» en el ticket vuelve a Consumidor Final', async () => {
      await conMartillos();
      await elegirNorte();

      (el().querySelector('.cliente-ticket button') as HTMLButtonElement).click();
      await refrescar();

      expect(el().querySelector('.cliente-ticket')).toBeNull();
    });

    it('«Quitar cliente» del diálogo también vuelve a Consumidor Final y cierra', async () => {
      await conMartillos();
      await elegirNorte();
      await tecla('F9');

      Array.from(el().querySelectorAll<HTMLButtonElement>('app-dialogo-cliente button'))
        .find((b) => normal(b.textContent) === 'Quitar cliente')!
        .click();
      await refrescar();

      expect(dialogoCliente()).toBeNull();
      expect(el().querySelector('.cliente-ticket')).toBeNull();
    });

    it('Esc cierra el diálogo sin cambiar el cliente', async () => {
      await conMartillos();
      await elegirNorte();
      await tecla('F9');

      await tecla('Escape');

      expect(dialogoCliente()).toBeNull();
      expect(clienteDelTicket()).toContain('Ferretería Norte SRL');
    });

    it('el cobro manda el clienteId, y tras cobrar el ticket queda sin cliente', async () => {
      await conMartillos();
      await elegirNorte();
      await tecla('F4');
      await tecla('Enter');

      expect(cobros[0].pedido.clienteId).toBe(2);
      cobros[0].respuesta.next({
        venta: { ventaId: 1, numero: 5, total: 3000, fecha: '', renglones: [], sinStock: [], facturable: true, clienteId: 2, clienteNombre: 'Ferretería Norte SRL' },
        repetida: false,
      } as VentaCobrada);
      cobros[0].respuesta.complete();
      await refrescar();

      expect(el().querySelector('.cliente-ticket')).toBeNull();
    });

    describe('cuenta corriente en F9 (CC-007)', () => {
      const conCuenta: Cliente = { ...CLIENTE_ANA, id: 5, razonSocial: 'Corralón Sur', cuentaCorrienteHabilitada: true, limiteCuentaCorriente: 5000, saldo: 1500 };
      const paraVendedor: Cliente = { ...conCuenta, limiteCuentaCorriente: undefined, saldo: undefined };

      async function abrirF9(rol: 'DUENO' | 'VENDEDOR', cliente: Cliente) {
        await crear(cajaAbierta, FORMAS_BASE, rol);
        clientesApi.buscar.mockReturnValue(of({ items: [cliente], page: 0, size: 8, totalElements: 1, totalPages: 1 } as PageResponse<Cliente>));
        await tecla('F9');
        await refrescar();
      }

      it('el Dueño ve el saldo y el límite junto al indicador «Cta. cte.»', async () => {
        await abrirF9('DUENO', conCuenta);

        expect(normal(dialogoCliente()?.textContent)).toContain('Cta. cte.');
        expect(normal(dialogoCliente()?.textContent)).toContain('Saldo $ 1.500,00 · Límite $ 5.000,00');
      });

      it('el Vendedor ve el indicador pero ninguna cifra', async () => {
        await abrirF9('VENDEDOR', paraVendedor);

        expect(normal(dialogoCliente()?.textContent)).toContain('Cta. cte.');
        expect(normal(dialogoCliente()?.textContent)).not.toMatch(/Saldo|Límite|1\.500|5\.000/);
      });
    });

    it('una venta sin cliente no manda clienteId', async () => {
      await conMartillos();
      await tecla('F4');
      await tecla('Enter');

      expect('clienteId' in cobros[0].pedido).toBe(false);
    });

    it('el presupuesto y el remito mandan el clienteId (y no el texto libre) cuando hay cliente', async () => {
      await conMartillos();
      await elegirNorte();

      await tecla('F8');
      await tecla('Enter');
      expect(guardados[0].pedido.clienteId).toBe(2);
      expect('clienteNombre' in guardados[0].pedido).toBe(false);
    });
  });
  describe('cuenta corriente en el cobro (CC-012, CC-013, CC-017)', () => {
    const CC = formaDe(9, 'Cuenta corriente', 'CUENTA_CORRIENTE');
    const FORMAS_CC = [...FORMAS_BASE, CC];
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const dialogoCobro = () => el().querySelector<HTMLElement>('app-dialogo-cobro [role="dialog"]');
    const opcion = (nombre: string) =>
      Array.from(el().querySelectorAll<HTMLButtonElement>('app-dialogo-cobro button[role="radio"]')).find((b) =>
        normal(b.textContent).startsWith(nombre),
      )!;
    const botonCobrar = () => el().querySelector<HTMLButtonElement>('app-dialogo-cobro button.cobrar')!;
    const razon = () => normal(el().querySelector('app-dialogo-cobro .razon-medio')?.textContent);
    const avisoDelDialogo = () => normal(el().querySelector('app-dialogo-cobro .aviso')?.textContent);
    const lineaCuenta = () => normal(el().querySelector('app-dialogo-cobro .cuenta-corriente')?.textContent);
    const renglones = () => el().querySelectorAll('app-ticket-lista .renglon').length;

    async function tecla(key: string, destino: HTMLElement = document.activeElement as HTMLElement) {
      destino.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      await refrescar();
    }

    /** Dos martillos ($ 3.000,00) con Ferretería Norte SRL (id 2) elegida con F9; el cobro todavía cerrado. */
    async function conClienteYMartillos(rol: 'DUENO' | 'VENDEDOR' = 'VENDEDOR', conCliente = true) {
      await crear(cajaAbierta, FORMAS_CC, rol);
      for (let i = 0; i < 2; i++) {
        await escribir('M');
        await enter();
        await responder(llamadas.length - 1, exacto(articulo(1, { descripcion: 'Martillo', precioFinal: 1500 })));
      }
      if (conCliente) {
        await tecla('F9');
        await tecla('ArrowDown');
        await tecla('Enter');
      }
    }

    const ALCANZA = { habilitada: true, alcanza: true };
    const DUENO_ALCANZA = { habilitada: true, alcanza: true, limite: 10000, saldo: 1000, disponible: 9000 };

    async function elegirCC() {
      opcion('Cuenta corriente').click();
      await refrescar();
    }

    async function fallarCobro(indice: number, status: number, cuerpo: unknown) {
      cobros[indice].respuesta.error(new HttpErrorResponse({ status, error: cuerpo }));
      await refrescar();
    }

    const limite = (cuentaCorriente: unknown) => ({
      status: 422,
      message: 'Esta venta supera el límite de cuenta corriente del cliente.',
      timestamp: 't',
      motivo: 'LIMITE_CUENTA_CORRIENTE',
      articulos: [],
      cuentaCorriente,
    });

    describe('pide la cuenta fresca al abrir el cobro', () => {
      it('con cliente pide la cuenta con el total sin recargo, en pesos', async () => {
        await conClienteYMartillos();

        await tecla('F4');

        expect(clientesApi.cuenta).toHaveBeenCalledTimes(1);
        expect(clientesApi.cuenta).toHaveBeenCalledWith(2, 3000);
      });

      it('sin cliente no pide nada y la opción dice que falta elegirlo', async () => {
        await conClienteYMartillos('VENDEDOR', false);

        await tecla('F4');

        expect(clientesApi.cuenta).not.toHaveBeenCalled();
        expect(razon()).toBe('Elegí un cliente para vender a cuenta corriente.');
      });

      it('mientras llega la respuesta la opción dice «Verificando…» y al llegar queda disponible', async () => {
        const respuesta = new Subject<unknown>();
        await conClienteYMartillos();
        clientesApi.cuenta.mockReturnValue(respuesta);

        await tecla('F4');
        expect(opcion('Cuenta corriente').getAttribute('aria-disabled')).toBe('true');
        expect(razon()).toBe('Verificando la cuenta corriente…');

        respuesta.next(ALCANZA);
        respuesta.complete();
        await refrescar();

        expect(opcion('Cuenta corriente').hasAttribute('aria-disabled')).toBe(false);
      });

      it('si la consulta falla la opción queda deshabilitada (falla cerrada) con su explicación', async () => {
        await conClienteYMartillos();
        clientesApi.cuenta.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

        await tecla('F4');

        expect(opcion('Cuenta corriente').getAttribute('aria-disabled')).toBe('true');
        expect(razon()).toBe('No se pudo verificar la cuenta corriente. Cerrá y reintentá.');
      });

      it('nunca cachea la cuenta: cada vez que se abre el cobro se pide de nuevo y no queda el dato anterior', async () => {
        await conClienteYMartillos();
        clientesApi.cuenta.mockReturnValue(of(ALCANZA));
        await tecla('F4');
        expect(opcion('Cuenta corriente').hasAttribute('aria-disabled')).toBe(false);
        await tecla('Escape');

        const pendiente = new Subject<unknown>();
        clientesApi.cuenta.mockReturnValue(pendiente);
        await tecla('F4');

        expect(clientesApi.cuenta).toHaveBeenCalledTimes(2);
        expect(razon()).toBe('Verificando la cuenta corriente…');
      });

      it('una respuesta atrasada de un cobro ya cerrado no pisa la del cobro nuevo', async () => {
        const vieja = new Subject<unknown>();
        const nueva = new Subject<unknown>();
        await conClienteYMartillos();
        clientesApi.cuenta.mockReturnValueOnce(vieja).mockReturnValueOnce(nueva);
        await tecla('F4');
        await tecla('Escape');
        await tecla('F4');

        nueva.next({ habilitada: false });
        nueva.complete();
        vieja.next(ALCANZA);
        vieja.complete();
        await refrescar();

        expect(razon()).toContain('no tiene cuenta corriente habilitada');
      });

      it('un precio que cambió en el servidor (409) vuelve a pedir la cuenta con el total nuevo', async () => {
        await conClienteYMartillos();
        await tecla('F4');
        await tecla('Enter');
        clientesApi.cuenta.mockClear();

        await fallarCobro(0, 409, {
          status: 409,
          message: 'Cambió el precio',
          timestamp: 't',
          motivo: 'PRECIOS_CAMBIADOS',
          total: 3600,
          articulos: [{ articuloId: 1, descripcion: 'Martillo', precioMostrado: 1500, precioActual: 1800 }],
        });

        expect(clientesApi.cuenta).toHaveBeenCalledTimes(1);
        expect(clientesApi.cuenta).toHaveBeenCalledWith(2, 3600);
      });
    });

    describe('rechazo por el límite (422 LIMITE_CUENTA_CORRIENTE)', () => {
      const CIFRAS = { puedeAutorizar: true, saldo: 4000, limite: 5000, disponible: 1000, importe: 3000, exceso: 2000 };

      async function cobrarACuenta(rol: 'DUENO' | 'VENDEDOR', cuentaInicial: unknown) {
        await conClienteYMartillos(rol);
        clientesApi.cuenta.mockReturnValue(of(cuentaInicial));
        await tecla('F4');
        await elegirCC();
        await tecla('Enter');
      }

      it('el cobro a cuenta corriente manda formaPagoId y clienteId, sin autorizaExceso', async () => {
        await cobrarACuenta('VENDEDOR', ALCANZA);

        expect(cobros[0].pedido.formaPagoId).toBe(9);
        expect(cobros[0].pedido.clienteId).toBe(2);
        expect('autorizaExceso' in cobros[0].pedido).toBe(false);
      });

      it('el Dueño: el diálogo sigue abierto, el ticket intacto y ve el exceso con «Autorizar exceso y cobrar»', async () => {
        await cobrarACuenta('DUENO', DUENO_ALCANZA);
        clientesApi.cuenta.mockReturnValue(of({ habilitada: true, alcanza: false, limite: 5000, saldo: 4000, disponible: 1000 }));

        await fallarCobro(0, 422, limite(CIFRAS));

        expect(dialogoCobro()).not.toBeNull();
        expect(renglones()).toBe(1);
        expect(avisoDelDialogo()).toContain('Esta venta supera el límite de cuenta corriente del cliente.');
        expect(lineaCuenta()).toBe('Supera el límite por $ 2.000,00 (límite $ 5.000,00, saldo $ 4.000,00)');
        expect(normal(botonCobrar().textContent)).toBe('Autorizar exceso y cobrar');
      });

      it('al autorizar reenvía la venta con autorizaExceso y la MISMA clave y el mismo contenido', async () => {
        await cobrarACuenta('DUENO', DUENO_ALCANZA);
        clientesApi.cuenta.mockReturnValue(of({ habilitada: true, alcanza: false, limite: 5000, saldo: 4000, disponible: 1000 }));
        await fallarCobro(0, 422, limite(CIFRAS));

        botonCobrar().click();
        await refrescar();

        expect(cobros).toHaveLength(2);
        expect(cobros[1].pedido.autorizaExceso).toBe(true);
        expect(cobros[1].pedido.clave).toBe(cobros[0].pedido.clave);
        const { autorizaExceso: _flag, ...sinFlag } = cobros[1].pedido;
        expect(sinFlag).toEqual(cobros[0].pedido);
      });

      it('el Vendedor: lee «pedile al Dueño», no ve cifras ni el botón de autorizar y la opción se apaga', async () => {
        await cobrarACuenta('VENDEDOR', ALCANZA);
        clientesApi.cuenta.mockReturnValue(of({ habilitada: true, alcanza: false }));

        await fallarCobro(
          0,
          422,
          { ...limite({ puedeAutorizar: false }), message: 'Esta venta supera el límite de cuenta corriente del cliente. Pedile al Dueño que la autorice.' },
        );

        expect(dialogoCobro()).not.toBeNull();
        expect(renglones()).toBe(1);
        expect(avisoDelDialogo()).toContain('Pedile al Dueño que la autorice.');
        expect(normal(botonCobrar().textContent)).toBe('Cobrar');
        expect(lineaCuenta()).toBe('');
        expect(normal(dialogoCobro()!.textContent)).not.toMatch(/Supera|límite \$|saldo \$/);
        expect(opcion('Cuenta corriente').getAttribute('aria-disabled')).toBe('true');
        expect(elegidaEnElCobro()).toBe('Efectivo');
      });

      it('vuelve a pedir la cuenta tras el rechazo (el saldo cambió)', async () => {
        await cobrarACuenta('DUENO', DUENO_ALCANZA);
        clientesApi.cuenta.mockClear();

        await fallarCobro(0, 422, limite(CIFRAS));

        expect(clientesApi.cuenta).toHaveBeenCalledTimes(1);
        expect(clientesApi.cuenta).toHaveBeenCalledWith(2, 3000);
      });

      it('cerrar el cobro y abrirlo de nuevo no arrastra el aviso del límite', async () => {
        await cobrarACuenta('DUENO', DUENO_ALCANZA);
        await fallarCobro(0, 422, limite(CIFRAS));
        await tecla('Escape');

        clientesApi.cuenta.mockReturnValue(of(DUENO_ALCANZA));
        await tecla('F4');
        await elegirCC();

        expect(lineaCuenta()).toBe('Saldo $ 1.000,00 · Límite $ 10.000,00 · Disponible $ 9.000,00');
        expect(normal(botonCobrar().textContent)).toBe('Cobrar');
      });
    });

    describe('otros rechazos', () => {
      async function cobrarACuenta(rol: 'DUENO' | 'VENDEDOR' = 'VENDEDOR') {
        await conClienteYMartillos(rol);
        clientesApi.cuenta.mockReturnValue(of(rol === 'DUENO' ? DUENO_ALCANZA : ALCANZA));
        await tecla('F4');
        await elegirCC();
        await tecla('Enter');
      }

      it('CUENTA_CORRIENTE_NO_HABILITADA: sigue el cobro con el mensaje, el ticket intacto y la cuenta releída', async () => {
        await cobrarACuenta();
        clientesApi.cuenta.mockClear();
        clientesApi.cuenta.mockReturnValue(of({ habilitada: false }));

        await fallarCobro(0, 422, {
          status: 422,
          message: 'Ferretería Norte SRL no tiene cuenta corriente habilitada.',
          timestamp: 't',
          motivo: 'CUENTA_CORRIENTE_NO_HABILITADA',
          articulos: [],
        });

        expect(dialogoCobro()).not.toBeNull();
        expect(renglones()).toBe(1);
        expect(avisoDelDialogo()).toBe('Ferretería Norte SRL no tiene cuenta corriente habilitada.');
        expect(clientesApi.cuenta).toHaveBeenCalledTimes(1);
        expect(razon()).toContain('no tiene cuenta corriente habilitada');
        expect(elegidaEnElCobro()).toBe('Efectivo');
      });

      it('un 403 por autorizar siendo Vendedor muestra el mensaje del servidor sin perder el ticket', async () => {
        await cobrarACuenta();

        await fallarCobro(0, 403, { status: 403, message: 'Autorizar el exceso es solo del Dueño' });

        expect(dialogoCobro()).not.toBeNull();
        expect(renglones()).toBe(1);
        expect(avisoDelDialogo()).toBe('Autorizar el exceso es solo del Dueño');
      });

      it('un 400 por cliente obligatorio muestra el mensaje y el ticket sigue', async () => {
        await cobrarACuenta();

        await fallarCobro(0, 400, {
          status: 400,
          message: 'Datos inválidos',
          errors: { clienteId: 'Elegí el cliente para vender a cuenta corriente.' },
        });

        expect(avisoDelDialogo()).toContain('Elegí el cliente para vender a cuenta corriente.');
        expect(renglones()).toBe(1);
      });
    });

    describe('venta cobrada a cuenta corriente', () => {
      const cobrada = (pagos: { medio: string; monto: number }[], total = 3000): VentaCobrada =>
        ({
          venta: {
            ventaId: 50,
            numero: 7,
            total,
            fecha: '2026-10-15T10:00:00',
            pagos: pagos.map((p, i) => ({ formaPagoId: i + 1, formaPago: p.medio, recargoPorcentaje: 0, recargo: 0, ...p })),
            renglones: [],
            sinStock: [],
            facturable: true,
            clienteId: 2,
          },
          repetida: false,
        }) as unknown as VentaCobrada;

      async function cobrarYResponder(v: VentaCobrada) {
        await conClienteYMartillos();
        clientesApi.cuenta.mockReturnValue(of(ALCANZA));
        await tecla('F4');
        await elegirCC();
        await tecla('Enter');
        cobros[0].respuesta.next(v);
        cobros[0].respuesta.complete();
        await refrescar();
      }

      it('avisa «cobrada a cuenta corriente» con el total y sin vuelto', async () => {
        await cobrarYResponder(cobrada([{ medio: 'CUENTA_CORRIENTE', monto: 3000 }]));

        const aviso = normal(el().querySelector('.aviso')?.textContent);
        expect(aviso).toBe('Venta N.º 7 cobrada a cuenta corriente: $ 3.000,00');
        expect(el().querySelector('.vuelto')).toBeNull();
        expect(dialogoCobro()).toBeNull();
      });

      it('si solo una parte fue a cuenta corriente, lo dice con el monto de esa parte', async () => {
        await cobrarYResponder(
          cobrada([
            { medio: 'EFECTIVO', monto: 1000 },
            { medio: 'CUENTA_CORRIENTE', monto: 2000 },
          ]),
        );

        expect(normal(el().querySelector('.aviso')?.textContent)).toBe(
          'Venta N.º 7 cobrada: $ 3.000,00 ($ 2.000,00 a cuenta corriente)',
        );
      });

      it('una venta sin cuenta corriente conserva el aviso de siempre (regresión)', async () => {
        await cobrarYResponder(cobrada([{ medio: 'EFECTIVO', monto: 3000 }]));

        expect(normal(el().querySelector('.aviso')?.textContent)).toBe('Venta N.º 7 cobrada: $ 3.000,00');
      });

      it('la venta de una sola forma de cuenta corriente (medioPago) también se reconoce', async () => {
        const v = cobrada([]);
        (v.venta as { pagos?: unknown }).pagos = undefined;
        (v.venta as { medioPago?: string }).medioPago = 'CUENTA_CORRIENTE';
        await cobrarYResponder(v);

        expect(normal(el().querySelector('.aviso')?.textContent)).toBe('Venta N.º 7 cobrada a cuenta corriente: $ 3.000,00');
      });
    });

    function elegidaEnElCobro() {
      return normal(el().querySelector('app-dialogo-cobro button[role="radio"][aria-checked="true"]')?.textContent);
    }
  });

  describe('factura del mostrador (CU-07 paso 5, S2, S3, S4, S11)', () => {
    const LETRAS: FacturacionDelMostrador['letras'] = {
      CONSUMIDOR_FINAL: 'B',
      RESPONSABLE_INSCRIPTO: 'A',
      MONOTRIBUTISTA: 'A',
      EXENTO: 'B',
    };
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const rotulo = () => normal(el().querySelector('app-dialogo-cobro .comprobante')?.textContent) || null;
    const dialogoFactura = () => el().querySelector<HTMLElement>('app-factura-imprimible [role="dialog"]');
    const reimprimir = () =>
      Array.from(el().querySelectorAll<HTMLButtonElement>('.aviso button')).find(
        (b) => normal(b.textContent) === 'Reimprimir factura',
      );
    let imprimir: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
      imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);
    });
    afterEach(() => imprimir.mockRestore());

    async function tecla(key: string, destino: HTMLElement = document.activeElement as HTMLElement) {
      const evento = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      destino.dispatchEvent(evento);
      await refrescar();
      return evento;
    }

    /**
     * Factura habilitada en el simulador, con las letras del servidor; y el ticket de 2 martillos ($ 3.000,00). `tope` es el
     * tope de Consumidor Final que manda el servidor (#57); sin él, no viene.
     */
    async function conMartillos(ambiente: FacturacionDelMostrador['ambiente'] = 'SIMULADOR', factura = true, tope?: number) {
      facturacionDelServidor = factura ? { factura: true, ambiente, letras: LETRAS } : { factura: false, ambiente: null, letras: null };
      if (tope !== undefined) facturacionDelServidor = { ...facturacionDelServidor, topeConsumidorFinal: tope };
      await crear();
      for (let i = 0; i < 2; i++) {
        await escribir('M');
        await enter();
        await responder(llamadas.length - 1, exacto(articulo(1, { descripcion: 'Martillo', precioFinal: 1500 })));
      }
    }

    /** La respuesta del cobro, con o sin registro fiscal. */
    function cobrada(fiscal?: FiscalVenta): VentaCobrada {
      return {
        venta: {
          ventaId: 99,
          numero: 7,
          total: 3000,
          fecha: '2026-10-28T10:00:00',
          medioPago: 'EFECTIVO',
          renglones: [
            {
              renglon: 1,
              articuloId: 1,
              descripcion: 'Martillo',
              unidad: 'UNIDAD',
              cantidad: 2,
              precioUnitario: 1500,
              alicuotaIva: 'IVA_21',
              bruto: 3000,
              total: 3000,
              neto: 2479.34,
              iva: 520.66,
            },
          ],
          sinStock: [],
          facturable: true,
          subtotal: 3000,
          descuentoPorcentaje: 0,
          descuento: 0,
          recargo: 0,
          ...(fiscal ? { fiscal } : {}),
        },
        repetida: false,
      };
    }

    async function cobrarCon(respuesta: VentaCobrada, recibido: string | null = null) {
      await tecla('F4');
      if (recibido !== null) {
        const input = el().querySelector<HTMLInputElement>('app-dialogo-cobro input')!;
        input.value = recibido;
        input.dispatchEvent(new Event('input'));
        await refrescar();
      }
      // Bloque 2, S4: con facturación se elige con un clic, no con Enter.
      el().querySelector<HTMLButtonElement>('app-dialogo-cobro button.facturar')!.click();
      await refrescar();
      cobros[0].respuesta.next(respuesta);
      cobros[0].respuesta.complete();
      await refrescar();
    }

    it('lee qué comprobante sale al abrir el mostrador, una vez', async () => {
      await conMartillos();

      expect(leerFacturacion).toHaveBeenCalledTimes(1);
    });

    it('sin cliente el cobro dice «Factura B · Consumidor Final» con la marca del simulador', async () => {
      await conMartillos();

      await tecla('F4');

      expect(rotulo()).toBe('Factura B · Consumidor Final SIMULADO');
    });

    it('en homologación la marca es «HOMOLOGACIÓN»; en producción no hay marca', async () => {
      await conMartillos('HOMOLOGACION');
      await tecla('F4');
      expect(rotulo()).toBe('Factura B · Consumidor Final HOMOLOGACIÓN');

      TestBed.resetTestingModule();
      localStorage.clear();
      await conMartillos('PRODUCCION');
      await tecla('F4');
      expect(rotulo()).toBe('Factura B · Consumidor Final');
    });

    it('con un Responsable Inscripto elegido con F9 dice «Factura A · <razón social>», con la letra del servidor', async () => {
      await conMartillos();
      await tecla('F9');
      await tecla('ArrowDown');
      await tecla('Enter');

      await tecla('F4');

      expect(rotulo()).toBe('Factura A · Ferretería Norte SRL SIMULADO');
    });

    /** Un ticket guardado con cliente pero sin su condición de IVA: el que arma «Pasar a venta» de un presupuesto o remito. */
    function ticketSinCondicion(condicion?: Cliente['condicionIva']) {
      localStorage.setItem(
        'ticket:3:7',
        JSON.stringify({
          v: 1,
          clave: 'vieja',
          elegido: 0,
          clienteId: 2,
          clienteNombre: 'Ferretería Norte SRL',
          ...(condicion ? { clienteCondicionIva: condicion } : {}),
          presupuestoId: 5,
          presupuestoNumero: 12,
          renglones: [
            {
              articuloId: 1,
              codigoInterno: 'COD-1',
              descripcion: 'Martillo',
              unidad: 'UNIDAD',
              precioCentavos: 150000,
              cantidadMilesimas: 1000,
              stockActual: 10,
              stockMinimo: 5,
            },
          ],
        }),
      );
    }

    it('el cobro avisa desde que abre que el ticket llega al tope de Consumidor Final que mandó el servidor (#57)', async () => {
      await conMartillos('SIMULADOR', true, 3000);

      await tecla('F4');

      expect(normal(el().querySelector('app-dialogo-cobro .aviso-tope')?.textContent)).toBe(
        'Desde $ 3.000,00 la venta a Consumidor Final necesita identificar al comprador: elegí un cliente con DNI, CUIT o CUIL.',
      );
    });

    it('con un tope más alto que el ticket, el cobro no avisa nada (#57)', async () => {
      await conMartillos('SIMULADOR', true, 3000.01);

      await tecla('F4');

      expect(el().querySelector('app-dialogo-cobro .aviso-tope')).toBeNull();
    });

    it('un ticket con cliente pero sin su condición (de un presupuesto o remito) la pide y dice «Factura A · <razón social>»', async () => {
      ticketSinCondicion();
      facturacionDelServidor = { factura: true, ambiente: 'SIMULADOR', letras: LETRAS };
      await crear();

      await tecla('F4');

      expect(clientesApi.obtener).toHaveBeenCalledExactlyOnceWith(2);
      expect(rotulo()).toBe('Factura A · Ferretería Norte SRL SIMULADO');
    });

    it('si no se puede leer el cliente, el cobro dice solo «Factura» y la venta sigue', async () => {
      ticketSinCondicion();
      falloLeerCliente = 500;
      facturacionDelServidor = { factura: true, ambiente: 'PRODUCCION', letras: LETRAS };
      await crear();

      await tecla('F4');

      expect(rotulo()).toBe('Factura');
      expect(el().querySelector('app-dialogo-cobro')).not.toBeNull();
    });

    it('bloque 2, S4: con facturación el cobro pide elegir con un clic si se factura', async () => {
      facturacionDelServidor = { factura: true, ambiente: 'PRODUCCION', letras: LETRAS };
      await conMartillos();

      await tecla('F4');

      expect(el().querySelector('app-dialogo-cobro button.facturar')?.textContent?.trim()).toBe('Cobrar y facturar con ARCA');
      expect(el().querySelector('app-dialogo-cobro button.sin-factura')).not.toBeNull();
    });

    it('bloque 2, S4: si no se pudo leer la facturación también pide elegir: Enter nunca factura sin querer', async () => {
      facturacionDelServidor = null;
      await conMartillos();

      await tecla('F4');

      expect(el().querySelector('app-dialogo-cobro button.facturar')).not.toBeNull();
    });

    it('bloque 2, S4: si el servidor dijo que no factura, queda «Cobrar» con Enter', async () => {
      await conMartillos(null, false);

      await tecla('F4');

      expect(el().querySelector('app-dialogo-cobro button.facturar')).toBeNull();
      expect(el().querySelector('app-dialogo-cobro button.cobrar')?.getAttribute('data-tecla')).toBe('Enter');
    });

    it('con la condición ya en el ticket (armado con ella) no vuelve a pedir el cliente', async () => {
      ticketSinCondicion('RESPONSABLE_INSCRIPTO');
      facturacionDelServidor = { factura: true, ambiente: 'SIMULADOR', letras: LETRAS };
      await crear();

      await tecla('F4');

      expect(clientesApi.obtener).not.toHaveBeenCalled();
      expect(rotulo()).toBe('Factura A · Ferretería Norte SRL SIMULADO');
    });

    it('con el cliente elegido con F9 (ya trae su condición) tampoco lo vuelve a pedir', async () => {
      await conMartillos();
      await tecla('F9');
      await tecla('ArrowDown');
      await tecla('Enter');

      expect(clientesApi.obtener).not.toHaveBeenCalled();
    });

    it('si el cajero cambia de cliente con F9 antes de que llegue la lectura lenta, la respuesta tardía no lo pisa', async () => {
      ticketSinCondicion();
      respuestaClienteLenta = new Subject<Cliente>();
      facturacionDelServidor = { factura: true, ambiente: 'SIMULADOR', letras: LETRAS };
      await crear();
      expect(clientesApi.obtener).toHaveBeenCalledExactlyOnceWith(2);

      await tecla('F9');
      await tecla('Enter');
      respuestaClienteLenta.next(CLIENTE_NORTE);
      respuestaClienteLenta.complete();
      await refrescar();

      await tecla('F4');
      expect(rotulo()).toContain('Ana Gómez');
      expect(rotulo()).not.toContain('Norte');
    });

    it('un ticket guardado antes, con cliente pero sin su condición, dice solo «Factura» si el cliente no se puede leer', async () => {
      falloLeerCliente = 404;
      localStorage.setItem(
        'ticket:3:7',
        JSON.stringify({
          v: 1,
          clave: 'vieja',
          elegido: 0,
          clienteId: 2,
          clienteNombre: 'Ferretería Norte SRL',
          renglones: [
            {
              articuloId: 1,
              codigoInterno: 'COD-1',
              descripcion: 'Martillo',
              unidad: 'UNIDAD',
              precioCentavos: 150000,
              cantidadMilesimas: 1000,
              stockActual: 10,
              stockMinimo: 5,
            },
          ],
        }),
      );
      facturacionDelServidor = { factura: true, ambiente: 'PRODUCCION', letras: LETRAS };
      await crear();

      await tecla('F4');

      expect(rotulo()).toBe('Factura');
    });

    it('sin facturación habilitada (o si no se pudo leer) no hay rótulo ni «Autorizando con ARCA…», como antes', async () => {
      for (const servidor of [{ factura: false, ambiente: null, letras: null }, null]) {
        TestBed.resetTestingModule();
        localStorage.clear();
        facturacionDelServidor = servidor;
        await crear();
        await escribir('M');
        await enter();
        await responder(llamadas.length - 1, exacto(articulo(1, { descripcion: 'Martillo', precioFinal: 1500 })));

        await tecla('F4');
        expect(el().querySelector('app-dialogo-cobro .comprobante'), JSON.stringify(servidor)).toBeNull();
        await tecla('Enter');
        expect(normal(el().querySelector('app-dialogo-cobro')?.textContent)).not.toContain('Autorizando');
      }
    });

    it('mientras viaja la venta el cobro dice «Autorizando con ARCA…»', async () => {
      await conMartillos();
      await tecla('F4');

      el().querySelector<HTMLButtonElement>('app-dialogo-cobro button.facturar')!.click();
      await refrescar();

      expect(normal(el().querySelector('app-dialogo-cobro')?.textContent)).toContain('Autorizando con ARCA…');
    });

    it('con `fiscal` abre la factura: la hoja del documento, el vuelto fuera de lo que se imprime y el aviso persistente', async () => {
      await conMartillos();

      await cobrarCon(cobrada(fiscalDe(documentoB({ total: 3000 }))), '3565');

      expect(el().querySelector('app-dialogo-cobro')).toBeNull();
      const dialogo = dialogoFactura()!;
      expect(normal(dialogo.querySelector('#factura-titulo')?.textContent)).toBe('Factura B');
      const articulo = dialogo.querySelector('article.factura-imprimible')!;
      expect(normal(articulo.querySelector('.numero-fiscal')?.textContent)).toBe('N.º 00001-00000012');
      const vuelto = dialogo.querySelector('.vuelto')!;
      expect(normal(vuelto.textContent)).toBe('Vuelto $ 565,00');
      expect(articulo.contains(vuelto)).toBe(false);
      expect(document.activeElement?.textContent?.trim()).toBe('Imprimir');

      await avanzar(60000);
      expect(normal(el().querySelector('.aviso')?.textContent)).toContain('Venta N.º 7 cobrada · Vuelto $ 565,00');
      expect(reimprimir()).toBeDefined();
    });

    it('Enter imprime; un escaneo con la factura abierta no imprime y avisa adentro', async () => {
      await conMartillos();
      await cobrarCon(cobrada(fiscalDe(documentoB())));

      for (const c of '7790001') {
        vi.advanceTimersByTime(5);
        await tecla(c);
      }
      vi.advanceTimersByTime(5);
      await tecla('Enter');

      expect(imprimir).not.toHaveBeenCalled();
      expect(normal(dialogoFactura()!.querySelector('[role="status"]')?.textContent)).toBe(
        'Cerrá el diálogo antes de escanear.',
      );
      expect(normal(el().querySelector('.aviso')?.textContent)).toContain('Venta N.º 7 cobrada');

      vi.advanceTimersByTime(1000);
      await tecla('Enter', dialogoFactura()!);
      expect(imprimir).toHaveBeenCalledTimes(1);
    });

    it('Esc cierra la factura y el foco vuelve al buscador; «Reimprimir factura» la reabre sin pedir nada al servidor', async () => {
      await conMartillos();
      const respuesta = cobrada(fiscalDe(documentoX(MARCA_SIMULADO), { ambiente: 'SIMULADOR' }));
      await cobrarCon(respuesta);

      await tecla('Escape');
      expect(dialogoFactura()).toBeNull();
      expect(document.activeElement).toBe(campo());

      reimprimir()!.click();
      await refrescar();

      expect(normal(dialogoFactura()!.querySelector('.tipo')?.textContent)).toBe('DOCUMENTO NO VALIDO COMO FACTURA');
      expect(normal(dialogoFactura()!.querySelector('.numero-interno')?.textContent)).toBe('Venta N.º 7');
      expect(cobros).toHaveLength(1);
      expect(leerFacturacion).toHaveBeenCalledTimes(1);
    });

    it('el aviso con «Reimprimir factura» se va con el próximo renglón', async () => {
      await conMartillos();
      await cobrarCon(cobrada(fiscalDe(documentoB())));
      await tecla('Escape');

      await escribir('S');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(2, { descripcion: 'Serrucho', precioFinal: 100 })));

      expect(reimprimir()).toBeUndefined();
    });

    it('con el `fiscal` de la rama 3 pero sin `documento` tampoco se abre nada: todo como hoy', async () => {
      await conMartillos();
      const { documento: _documento, ...sinDocumento } = fiscalDe(documentoB());

      await cobrarCon(cobrada(sinDocumento as FiscalVenta));

      expect(dialogoFactura()).toBeNull();
      expect(reimprimir()).toBeUndefined();
      expect(normal(el().querySelector('.aviso')?.textContent)).toBe('Venta N.º 7 cobrada: $ 3.000,00');
      expect(document.activeElement).toBe(campo());
      await avanzar(5000);
      expect(el().querySelector('.aviso')).toBeNull();
    });

    it('sin `fiscal` en la respuesta no se abre nada: el aviso de siempre y el foco en el buscador', async () => {
      await conMartillos();

      await cobrarCon(cobrada());

      expect(dialogoFactura()).toBeNull();
      expect(reimprimir()).toBeUndefined();
      expect(normal(el().querySelector('.aviso')?.textContent)).toBe('Venta N.º 7 cobrada: $ 3.000,00');
      expect(document.activeElement).toBe(campo());
      await avanzar(5000);
      expect(el().querySelector('.aviso')).toBeNull();
    });
  });

  describe('avisos de facturación (rama 7: CU-16 punto 8 y «Reimprimir» de CU-07; S6, S11, S14)', () => {
    const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
    const avisoDueno = () => el().querySelector<HTMLElement>('.zona-busqueda .aviso-facturacion');
    const avisosCae = () => Array.from(el().querySelectorAll<HTMLElement>('.zona-busqueda .aviso-cae'));
    const textosCae = () => avisosCae().map((a) => normal(a.querySelector('.texto')?.textContent));
    const dialogoFactura = () => el().querySelector<HTMLElement>('app-factura-imprimible [role="dialog"]');
    const botonCae = (nombre: string, i = 0) =>
      Array.from(avisosCae()[i].querySelectorAll<HTMLButtonElement>('button')).find(
        (b) => normal(b.textContent) === nombre || b.getAttribute('aria-label') === nombre,
      )!;
    const venta12: ProvisoriaAutorizada = { ventaId: 120, numero: 12, factura: 'Factura B 0001-00000045' };
    const exacto = (a: ArticuloMostrador): BusquedaMostrador => ({ tipo: 'EXACTO', items: [a], hayMas: false });

    async function tecla(key: string, destino: HTMLElement = document.activeElement as HTMLElement) {
      destino.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      await refrescar();
    }

    it('el Dueño ve al abrir y cada minuto lo que tiene que revisar, debajo del buscador y sin sacarle el foco', async () => {
      avisosDelServidor = { rechazadas: 2, pendientesViejas: 1, enRevision: 0, bloqueo: null };
      await crear(cajaAbierta, FORMAS_BASE, 'DUENO');

      expect(leerAvisos).toHaveBeenCalledTimes(1);
      expect(Array.from(avisoDueno()!.querySelectorAll('a')).map((a) => [normal(a.textContent), a.getAttribute('href')])).toEqual([
        ['2 rechazadas', '/ventas?fiscal=RECHAZADAS'],
        ['1 pendiente de CAE desde hace más de 30 minutos', '/ventas?fiscal=PENDIENTES'],
      ]);
      expect(document.activeElement).toBe(campo());

      avisosDelServidor = { rechazadas: 0, pendientesViejas: 0, enRevision: 0, bloqueo: null };
      await avanzar(INTERVALO_AVISOS_MS);
      expect(leerAvisos).toHaveBeenCalledTimes(2);
      expect(avisoDueno()).toBeNull();
    });

    it('al Vendedor no se le piden los avisos del Dueño', async () => {
      avisosDelServidor = { rechazadas: 2, pendientesViejas: 1, enRevision: 1, bloqueo: null };
      await crear();
      await avanzar(INTERVALO_AVISOS_MS);

      expect(leerAvisos).not.toHaveBeenCalled();
      expect(avisoDueno()).toBeNull();
    });

    it('con la caja abierta pregunta cada minuto si llegó el CAE y avisa, al Vendedor y al Dueño', async () => {
      for (const rol of ['VENDEDOR', 'DUENO'] as const) {
        TestBed.resetTestingModule();
        provisorias = [];
        await crear(cajaAbierta, FORMAS_BASE, rol);
        expect(leerProvisorias, rol).toHaveBeenCalledTimes(1);
        expect(avisosCae()).toHaveLength(0);

        provisorias = [venta12];
        await avanzar(INTERVALO_AVISOS_MS);
        expect(leerProvisorias).toHaveBeenCalledTimes(2);
        expect(textosCae()).toEqual(['Llegó el CAE de la venta N.º 12']);
        expect(document.activeElement).toBe(campo());
      }
    });

    it('sin caja abierta no pregunta por el CAE; con la caja abierta de otro día, sí', async () => {
      provisorias = [venta12];
      await crear({ estado: 'SIN_CAJA', caja: null, efectivoPropuesto: 0, hayCierreAnterior: false });
      await avanzar(INTERVALO_AVISOS_MS);
      expect(leerProvisorias).not.toHaveBeenCalled();
      expect(avisosCae()).toHaveLength(0);

      TestBed.resetTestingModule();
      await crear({ ...cajaAbierta, estado: 'DE_OTRO_DIA' });
      expect(leerProvisorias).toHaveBeenCalledTimes(1);
      expect(textosCae()).toEqual(['Llegó el CAE de la venta N.º 12']);
    });

    it('«Reimprimir factura» abre la hoja fiscal de esa venta como la del cobro, el aviso se va y Esc vuelve al buscador', async () => {
      const imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);
      provisorias = [venta12];
      await crear();

      botonCae('Reimprimir factura').click();
      await refrescar();

      expect(obtenerHoja).toHaveBeenCalledWith(120);
      const dialogo = dialogoFactura()!;
      expect(normal(dialogo.querySelector('#factura-titulo')?.textContent)).toBe('Factura B');
      expect(normal(dialogo.querySelector('.numero-fiscal')?.textContent)).toBe('N.º 00001-00000045');
      expect(dialogo.querySelector('.vuelto')).toBeNull();
      expect(avisosCae()).toHaveLength(0);

      await tecla('Enter', dialogo);
      expect(imprimir).toHaveBeenCalledTimes(1);
      await tecla('Escape');
      expect(dialogoFactura()).toBeNull();
      expect(document.activeElement).toBe(campo());
      imprimir.mockRestore();
    });

    it('cerrar el aviso del CAE lo descarta y el foco vuelve al buscador', async () => {
      provisorias = [venta12];
      await crear();
      const cerrar = botonCae('Cerrar el aviso de la venta N.º 12');
      cerrar.focus();

      cerrar.click();
      await refrescar();

      expect(avisosCae()).toHaveLength(0);
      expect(obtenerHoja).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(campo());
    });

    it('la hoja del CAE que llega con un diálogo abierto no lo pisa y el aviso queda', async () => {
      provisorias = [venta12];
      await crear();
      const respuesta = new Subject<VentaConsultada>();
      obtenerHoja.mockReturnValueOnce(respuesta);

      botonCae('Reimprimir factura').click();
      await refrescar();
      await tecla('F9', campo());
      expect(el().querySelector('app-dialogo-cliente')).not.toBeNull();
      respuesta.next(ventaDePrueba({ ventaId: 120, numero: 12, fiscal: fiscalDe(documentoB({ numero: 45 })) }));
      respuesta.complete();
      await refrescar();

      expect(el().querySelector('app-dialogo-cliente')).not.toBeNull();
      expect(dialogoFactura()).toBeNull();
      expect(textosCae()).toEqual(['Llegó el CAE de la venta N.º 12']);
    });

    it('el aviso de venta cobrada sigue igual con un aviso del CAE a la vista', async () => {
      provisorias = [venta12];
      await crear();
      await escribir('M');
      await enter();
      await responder(llamadas.length - 1, exacto(articulo(1, { descripcion: 'Martillo', precioFinal: 1500 })));
      await tecla('F4', campo());
      await tecla('Enter');
      cobros[0].respuesta.next({
        venta: {
          ventaId: 99,
          numero: 7,
          total: 1500,
          fecha: '2026-10-29T10:00:00',
          medioPago: 'EFECTIVO',
          renglones: [],
          sinStock: [],
          facturable: true,
        },
        repetida: false,
      });
      cobros[0].respuesta.complete();
      await refrescar();

      expect(normal(el().querySelector('.aviso')?.textContent)).toBe('Venta N.º 7 cobrada: $ 1.500,00');
      expect(textosCae()).toEqual(['Llegó el CAE de la venta N.º 12']);
      await avanzar(5000);
      expect(el().querySelector('.aviso')).toBeNull();
      expect(textosCae()).toEqual(['Llegó el CAE de la venta N.º 12']);
    });

    it('al salir del mostrador deja de preguntar', async () => {
      await crear(cajaAbierta, FORMAS_BASE, 'DUENO');
      fixture.destroy();

      vi.advanceTimersByTime(INTERVALO_AVISOS_MS * 3);

      expect(leerAvisos).toHaveBeenCalledTimes(1);
      expect(leerProvisorias).toHaveBeenCalledTimes(1);
    });
  });
});
