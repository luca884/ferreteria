import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, Subject, Subscription, catchError, concatMap, map, switchMap, timer } from 'rxjs';
import { AccionBotonera, AVISO_BOTONERA_ID, Botonera } from './botonera';
import { AvisosCae } from './avisos-cae';
import { AvisosFacturacion } from './avisos-facturacion';
import { CajaBusqueda } from './caja-busqueda';
import { DialogoCantidad } from './dialogo-cantidad';
import { ConfirmacionCobro, CuentaDelCobro, DialogoCobro, RotuloFactura } from './dialogo-cobro';
import { DialogoConfirmacion } from './dialogo-confirmacion';
import { DialogoDescuento } from './dialogo-descuento';
import { AuthService } from '../../core/auth/auth.service';
import { Cliente } from '../clientes/cliente.models';
import { ClientesService } from '../clientes/clientes.service';
import { DialogoCliente } from './dialogo-cliente';
import { DialogoPresupuesto } from './dialogo-presupuesto';
import { DialogoRemito } from './dialogo-remito';
import { FichaArticulo } from './ficha-articulo';
import { HojaRenglon } from './hoja-renglon';
import { LISTA_ID, ListaResultados, filaId } from './lista-resultados';
import { ArticuloMostrador, BusquedaMostrador } from './mostrador.models';
import { accionDeTecla, atajosDeBotonera } from './mostrador.atajos';
import { MostradorService } from './mostrador.service';
import { PrimerosPasos } from './primeros-pasos';
import { AVISO_ESCANEO_CON_DIALOGO, GuardaLector } from './guarda-lector';
import { esOcupado, mensajeDe } from '../../core/api/api-error';
import { MontoPipe, formatearMonto } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { esCelular } from '../../shared/es-celular';
import { FacturaImprimible } from '../../shared/factura/factura-imprimible';
import { DocumentoNoFiscalImprimible } from '../../shared/documento-no-fiscal/documento-no-fiscal-imprimible';
import { DocumentoNoFiscal } from '../../shared/documento-no-fiscal/documento-no-fiscal.models';
import { EmisorService } from '../../shared/documento-no-fiscal/emisor.service';
import { DatosFiscales } from '../datos-fiscales/datos-fiscales.service';
import { VentaFacturada } from '../../shared/factura/factura.models';
import { aCentavos } from './centavos';
import {
  PROBLEMA_DADO_DE_BAJA,
  PROBLEMA_INEXISTENTE,
  PROBLEMA_SIN_PRECIO,
  aPresupuestoRequest,
  aRemitoRequest,
  aRequest,
} from './ticket';
import { TicketLista } from './ticket-lista';
import { TicketStore } from './ticket.store';
import { TotalesTicket } from './totales-ticket';
import {
  ConfirmarVentaRequest,
  ExcesoCuentaCorriente,
  FacturacionDelMostrador,
  VentaCobrada,
  VentaInvalida,
  VentaRechazada,
  VentaResponse,
} from './venta.models';
import { VentaService, invalidaDe, rechazoDe } from './venta.service';
import { RouterLink } from '@angular/router';
import { CajaVigente, avisoParaVender } from '../caja/caja.models';
import { CajaService } from '../caja/caja.service';
import { FormaPago } from '../formas-pago/forma-pago.models';
import { FormasPagoService } from '../formas-pago/formas-pago.service';
import { DatosPresupuesto, PresupuestoResponse, documentoDePresupuesto } from '../presupuestos/presupuesto.models';
import { PresupuestosService } from '../presupuestos/presupuestos.service';
import { DatosRemito, RemitoEmitido, documentoDeRemito } from '../remitos/remito.models';
import { RemitosService } from '../remitos/remitos.service';
import { VencimientoCertificado, vencimientoParaElMostrador } from '../facturacion/facturacion.models';
import { FacturacionService } from '../facturacion/facturacion.service';

const ESPERA_MS = 250;
/** Id del panel de avisos que la franja del celular abre y cierra (móvil-pulgar, T4). */
const AVISOS_PANEL_ID = 'avisos-mostrador';
/** Cuánto queda a la vista el aviso de venta cobrada. */
const AVISO_COBRADA_MS = 5000;
const MENSAJE_ERROR = 'No se pudo buscar. Probá de nuevo.';
const MENSAJE_SIN_RESULTADOS = 'No encontramos ningún artículo con ese código o descripción.';
const MENSAJE_COBRO_FALLIDO = 'No se pudo cobrar. Probá de nuevo.';
const MENSAJE_PRESUPUESTO_FALLIDO = 'No se pudo guardar el presupuesto. Probá de nuevo.';
const MENSAJE_REMITO_FALLIDO = 'No se pudo emitir el remito. Probá de nuevo.';
const MENSAJE_OCUPADO = 'El sistema está ocupado, reintentá.';
const MENSAJE_SIN_PERMISO = 'No tenés permiso para hacer esto.';
const MENSAJE_SIN_FORMAS = 'No se pudieron cargar las formas de pago. Probá de nuevo en un momento.';

/*
 * S4: por qué no se puede usar una acción de la botonera. Son las mismas condiciones que antes apagaban los botones,
 * dichas como las diría el vendedor: el mostrador las muestra cuando la acción se intenta, sin ejecutar nada.
 */
const MOTIVO_DIALOGO = 'Cerrá el diálogo para seguir con el ticket.';
const MOTIVO_SIN_RENGLONES = 'Agregá un renglón para usar esta acción.';
/** Las acciones que necesitan al menos un renglón en el ticket; el resto anda con el ticket vacío. */
const NECESITAN_RENGLONES: readonly AccionBotonera[] = [
  'cantidad',
  'descuento',
  'descuentoTotal',
  'cobrar',
  'presupuesto',
  'remito',
  'quitar',
  'vaciar',
];
/** Cuánto queda a la vista el motivo de una acción que no se pudo usar. */
const MOTIVO_A_LA_VISTA_MS = 5000;

/** El diálogo abierto, si hay uno. `indice` es el renglón del ticket sobre el que actúa. */
type Dialogo =
  | { tipo: 'cantidad'; indice: number }
  | { tipo: 'quitar'; indice: number }
  | { tipo: 'vaciar' }
  | { tipo: 'descuento'; indice: number }
  /** movil-sectores T8a: la hoja del renglón del celular, que elige entre cantidad, descuento y quitar. */
  | { tipo: 'renglon'; indice: number }
  | { tipo: 'descuentoTotal' }
  | { tipo: 'cobro' }
  | { tipo: 'presupuesto' }
  | { tipo: 'remito' }
  | { tipo: 'cliente' }
  | { tipo: 'factura'; factura: FacturaCobrada }
  /** La hoja del presupuesto recién guardado o del remito recién emitido (informacion-y-links, S1). */
  | { tipo: 'documento'; documento: DocumentoNoFiscal; emisor: DatosFiscales };

/**
 * La factura de la venta recién cobrada (CU-07 paso 5, D7): la respuesta del cobro tal cual, para abrirla y reimprimirla sin
 * pedir nada al servidor. El vuelto, si lo hubo, se ve en el diálogo.
 */
interface FacturaCobrada {
  venta: VentaFacturada;
  vueltoCentavos: number | null;
}

/** La marca del ambiente en el rótulo del cobro: en producción no hay. */
const MARCA_AMBIENTE: Record<string, string> = { SIMULADOR: 'SIMULADO', HOMOLOGACION: 'HOMOLOGACIÓN' };

/**
 * Aviso del contenedor, aparte del mensaje de la búsqueda. `vacio` y `persistente` se van con el próximo
 * renglón (o con Esc); `otro` se queda hasta que lo reemplace otro aviso o venza su plazo.
 */
interface Aviso {
  texto: string;
  grave: boolean;
  tipo: 'vacio' | 'persistente' | 'otro';
  /** Vuelto de la venta en efectivo, en centavos: se muestra grande al lado del número de venta. */
  vueltoCentavos?: number;
  /** Advertencia aparte (sin stock): en color de aviso, no de éxito. */
  advertencia?: string;
  /** La factura de la venta cobrada: el aviso ofrece «Reimprimir factura» mientras está. */
  factura?: FacturaCobrada;
  /** El presupuesto o el remito recién guardado: el aviso lleva el link a su detalle, donde se vuelve a imprimir. */
  documento?: { texto: string; ruta: string };
}

/** Un escaneo (Enter): `seq` dice si sigue siendo lo último que hizo el vendedor. */
interface Escaneo {
  q: string;
  seq: number;
}

/** Mostrador: consulta de precio, stock y ubicación, y el ticket de la venta en curso (CU-07). */
@Component({
  selector: 'app-mostrador',
  imports: [
    AvisosCae,
    AvisosFacturacion,
    BarraAcciones,
    Botonera,
    MontoPipe,
    CajaBusqueda,
    DialogoCantidad,
    DialogoCobro,
    DialogoConfirmacion,
    DialogoDescuento,
    DialogoCliente,
    DialogoPresupuesto,
    DialogoRemito,
    ListaResultados,
    FichaArticulo,
    HojaRenglon,
    TicketLista,
    PrimerosPasos,
    TotalesTicket,
    RouterLink,
    FacturaImprimible,
    DocumentoNoFiscalImprimible,
  ],
  templateUrl: './mostrador.html',
  styleUrl: './mostrador.css',
  providers: [TicketStore],
  host: {
    '(document:keydown)': 'alTeclear($event)',
    '(window:beforeunload)': 'alSalir($event)',
  },
})
export class Mostrador {
  private readonly service = inject(MostradorService);
  private readonly ventas = inject(VentaService);
  private readonly presupuestos = inject(PresupuestosService);
  private readonly remitos = inject(RemitosService);
  private readonly emisorApi = inject(EmisorService);
  private readonly cajas = inject(CajaService);
  private readonly formas = inject(FormasPagoService);
  private readonly clientes = inject(ClientesService);
  private readonly configuracionArca = inject(FacturacionService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  /** Solo el Dueño ve el saldo y el límite del cliente y puede autorizar que una venta a cuenta corriente se pase (CC-007). */
  protected readonly esDueno = computed(() => this.auth.sesion()?.rol === 'DUENO');
  /** CU-07: el tope de descuento del usuario en centésimas, para que el diálogo avise antes; el servidor igual lo controla. */
  protected readonly topeDescuentoBp = computed(() => {
    const tope = this.auth.sesion()?.topeDescuento;
    return tope == null ? null : Math.round(tope * 100);
  });
  /**
   * La cuenta corriente del cliente del ticket, para el cobro abierto (CC-017). Se pide fresca cada vez que se abre y tras
   * cada rechazo que la toca; nunca se guarda en el ticket. null si el ticket no tiene cliente o el cobro está cerrado.
   */
  protected readonly cuentaCobro = signal<CuentaDelCobro | null>(null);
  /** El último rechazo por el límite (CC-013) del cobro abierto: trae las cifras para el Dueño y si puede autorizar. */
  protected readonly avisoLimite = signal<ExcesoCuentaCorriente | null>(null);
  /** La lectura de la cuenta en curso: una nueva cancela la anterior, para que una respuesta atrasada no pise a la vigente. */
  private lecturaCuenta: Subscription | undefined;

  /**
   * Las formas de pago activas (GDS-11), en el orden del Dueño: son las opciones del cobro. Se leen al abrir, al apretar
   * F4 (por si el Dueño cambió un recargo) y cuando el servidor rechaza una forma o un total. Vacía mientras no se
   * pudieron leer: sin formas no se abre el cobro.
   */
  protected readonly formasPago = signal<readonly FormaPago[]>([]);

  /**
   * La caja de la ferretería (CU-10): sin caja abierta del día no se vende. null mientras se lee o si no se pudo leer:
   * en ese caso no se frena nada acá y la venta la valida el backend.
   */
  private readonly cajaVigente = signal<CajaVigente | null>(null);
  /** Por qué no se puede vender ahora («Abrí la caja para vender.»…), o null si se puede. */
  protected readonly avisoCaja = computed(() => {
    const vigente = this.cajaVigente();
    return vigente ? avisoParaVender(vigente) : null;
  });
  /** El botón de la caja del aviso: abrirla o cerrarla según el caso. */
  protected readonly accionCaja = computed(() =>
    this.cajaVigente()?.estado === 'DE_OTRO_DIA' ? 'Cerrar la caja' : 'Abrir la caja',
  );
  /**
   * Hay una caja abierta (de hoy o de otro día, sin cerrar): solo entonces se pregunta si llegó el CAE de sus ventas (C7,
   * S14). Si no se pudo leer la caja, no se pregunta.
   */
  protected readonly hayCajaAbierta = computed(() => {
    const estado = this.cajaVigente()?.estado;
    return estado === 'ABIERTA' || estado === 'DE_OTRO_DIA';
  });
  protected readonly ticket = inject(TicketStore);
  private readonly caja = viewChild.required(CajaBusqueda);

  /**
   * Lo que el diálogo de descuento suma al que se escribe para avisar el tope combinado (arreglos-confusos, S6): en F6 el
   * mayor descuento de renglón, en F7 el general. Un ticket que sale de un presupuesto o de un remito no combina: sus
   * descuentos ya están autorizados y solo el servidor sabe hasta dónde (TopeDeDescuento.Autorizados).
   */
  private readonly combinaDescuentos = computed(() => {
    const t = this.ticket.ticket();
    return t.presupuestoId == null && t.remitoId == null;
  });
  protected readonly mayorDescuentoDeRenglonBp = computed(() =>
    this.combinaDescuentos() ? Math.max(0, ...this.ticket.ticket().renglones.map((r) => r.descuentoBp ?? 0)) : 0,
  );
  protected readonly descuentoGeneralParaCombinarBp = computed(() =>
    this.combinaDescuentos() ? (this.ticket.ticket().descuentoGlobalBp ?? 0) : 0,
  );

  /**
   * Qué comprobante sale al cobrar (C3), leído al abrir el mostrador. null mientras se lee o si no se pudo: el cobro no
   * anticipa nada y la venta la factura (o no) el servidor igual.
   */
  protected readonly facturacion = signal<FacturacionDelMostrador | null>(null);
  /**
   * Bloque 2, S4: el cobro hace elegir con un clic entre facturar con ARCA o no, salvo que el servidor haya dicho que la
   * ferretería no factura. Mientras se lee, o si no se pudo leer, también: Enter nunca factura sin querer.
   */
  protected readonly eligeFactura = computed(() => this.facturacion()?.factura !== false);
  /**
   * El rótulo del cobro: «Factura <letra> · Consumidor Final» o «Factura <letra> · <cliente>», con la letra que el servidor
   * dio para la condición del cliente; un ticket guardado sin esa condición dice solo «Factura». null si no se factura.
   */
  protected readonly rotuloFactura = computed<RotuloFactura | null>(() => {
    const f = this.facturacion();
    if (!f?.factura || !f.letras) return null;
    const ambiente = (f.ambiente && MARCA_AMBIENTE[f.ambiente]) ?? null;
    const { clienteId, clienteNombre, clienteCondicionIva } = this.ticket.ticket();
    if (clienteId === undefined) return { texto: `Factura ${f.letras.CONSUMIDOR_FINAL} · Consumidor Final`, ambiente };
    const letra = clienteCondicionIva ? f.letras[clienteCondicionIva] : undefined;
    return { texto: letra ? `Factura ${letra} · ${clienteNombre}` : 'Factura', ambiente };
  });
  /**
   * S12: el certificado de ARCA por vencer o vencido, para avisarle al Dueño al abrir el mostrador. No frena nada; null si
   * está vigente, si no hay certificado o si no se pudo leer.
   */
  protected readonly vencimientoCertificado = signal<VencimientoCertificado | null>(null);
  /**
   * La franja de avisos del celular (móvil-pulgar, T4): con el panel cerrado los avisos no ocupan lugar y el ticket
   * arranca arriba; la franja los abre a un toque. En el escritorio la franja no se dibuja.
   */
  protected readonly avisosAbiertos = signal(false);
  /** El id del panel: lo comparten el `aria-controls` de la franja y el panel. */
  protected readonly avisosPanelId = AVISOS_PANEL_ID;
  /** Cuántos avisos informó AvisosFacturacion: la franja suma ese número a los que sabe el mostrador. */
  private readonly avisosDeFacturacion = signal(0);
  /** Cuántos avisos hay arriba del ticket: la caja que frena la venta, el certificado del Dueño y lo de facturación. */
  protected readonly cuentaDeAvisos = computed(
    () =>
      (this.avisoCaja() === null ? 0 : 1) +
      (this.vencimientoCertificado() === null ? 0 : 1) +
      this.avisosDeFacturacion(),
  );
  /** «1 aviso», «2 avisos»: lo que dice la franja del celular. */
  protected readonly textoAvisos = computed(() =>
    this.cuentaDeAvisos() === 1 ? '1 aviso' : `${this.cuentaDeAvisos()} avisos`,
  );
  /** La guarda de escaneo del diálogo de la factura: un Enter del lector no imprime. */
  private readonly guardaFactura = new GuardaLector();
  protected readonly esEscaneo = (evento: KeyboardEvent) => this.guardaFactura.alTeclear(evento);

  protected readonly texto = signal('');
  protected readonly resultado = signal<BusquedaMostrador | null>(null);
  /** La consulta que originó `resultado`: si el texto sigue igual, Enter abre la fila en vez de buscar. */
  private readonly consultaMostrada = signal('');
  protected readonly seleccionado = signal(0);
  protected readonly ficha = signal<ArticuloMostrador | null>(null);
  protected readonly error = signal(false);

  /**
   * Lo que se le avisa al vendedor cuando no hay nada para mostrar. `grave` va en rojo; `urgente` se anuncia
   * como alert. «No encontramos» no es urgente: llega mientras tipea y un alert lo interrumpiría.
   */
  protected readonly mensaje = computed(() => {
    if (this.error()) return { texto: MENSAJE_ERROR, grave: true, urgente: true };
    switch (this.resultado()?.tipo) {
      case 'SIN_RESULTADOS':
        return { texto: MENSAJE_SIN_RESULTADOS, grave: true, urgente: false };
      default:
        return null;
    }
  });

  /** Lo último que se vio de cada artículo del ticket (ubicación, código de barras): la ficha lo reusa. */
  private readonly vistos = new Map<number, ArticuloMostrador>();
  /** Tras ↑/↓ (o quitar, cobrar) la ficha sigue al renglón elegido en vez de al último escaneo. */
  private readonly siguiendoTicket = signal(false);

  /** La ficha de la derecha: el resultado resaltado, el renglón elegido o el último artículo mostrado. */
  protected readonly fichaMostrada = computed<ArticuloMostrador | null>(() => {
    const r = this.resultado();
    if (this.listaVisible() && r) return r.items[this.seleccionado()] ?? null;
    if (!this.siguiendoTicket()) return this.ficha();
    const { renglones, elegido } = this.ticket.ticket();
    const renglon = elegido === null ? undefined : renglones[elegido];
    if (!renglon) return null;
    return {
      codigoBarras: null,
      codigoProveedor: null,
      ubicacion: null,
      // Al ticket solo entran activos.
      activo: true,
      ...this.vistos.get(renglon.articuloId),
      id: renglon.articuloId,
      codigoInterno: renglon.codigoInterno,
      descripcion: renglon.descripcion,
      precioFinal: renglon.precioCentavos / 100,
      unidad: renglon.unidad,
      stockActual: renglon.stockActual,
      stockMinimo: renglon.stockMinimo,
    };
  });

  /** La lista visible es la que el campo controla (aria-controls / aria-activedescendant). */
  protected readonly listaVisible = computed(
    () => this.resultado()?.tipo === 'LISTA' && !this.ficha(),
  );
  protected readonly listaId = LISTA_ID;
  protected readonly filaId = filaId;

  /** Texto tipeado: espera 250 ms; un texto nuevo (o `null`) cancela la espera y la llamada en vuelo. */
  private readonly busquedas = new Subject<string | null>();
  /** Escaneos (Enter): se atienden de a uno y en orden, ninguno cancela a otro. */
  private readonly escaneos = new Subject<Escaneo>();
  /** Número del último gesto del vendedor (tipear, Enter, Esc): decide quién puede tocar la pantalla. */
  private seq = 0;
  /** Temporizador que oculta el aviso de venta cobrada; cada aviso nuevo arranca el suyo. */
  private ocultarAviso: ReturnType<typeof setTimeout> | undefined;

  protected readonly dialogo = signal<Dialogo | null>(null);
  /** Para el buscador: con un diálogo abierto no recupera el foco diferido. */
  protected readonly sinDialogo = () => this.dialogo() === null;
  protected readonly aviso = signal<Aviso | null>(null);
  /** Hay un POST de venta o de presupuesto en curso: su diálogo ignora Enter y no se cierra. */
  protected readonly enVuelo = signal(false);
  /** Mensaje dentro del diálogo de cobro o de presupuesto (precio cambiado, error de red, escaneo descartado). */
  protected readonly avisoCobro = signal<string | null>(null);
  /** Lo recibido en el POST en curso (solo efectivo): el backend no guarda el vuelto, se calcula acá. */
  private recibidoEnVuelo: number | null = null;

  /**
   * S4: por qué no se puede usar cada acción de la botonera ahora mismo; la que no figura, se puede. Son las mismas
   * condiciones que antes apagaban los botones (nada con un diálogo abierto; sin renglones, solo buscar y cliente; y
   * cobrar, además, con la caja abierta).
   */
  protected readonly motivos = computed<Partial<Record<AccionBotonera, string>>>(() => {
    const motivos: Partial<Record<AccionBotonera, string>> = {};
    if (this.dialogo() !== null) {
      // Con un diálogo abierto no se hace nada desde la botonera ni con las teclas: la razón es la misma para todas.
      for (const { accion } of atajosDeBotonera()) motivos[accion as AccionBotonera] = MOTIVO_DIALOGO;
      return motivos;
    }
    // Sin renglones no hay nada sobre lo que actuar. El presupuesto y el remito no tocan la caja: siguen sin ella.
    const conRenglones = this.ticket.ticket().renglones.length > 0;
    if (!conRenglones) for (const accion of NECESITAN_RENGLONES) motivos[accion] = MOTIVO_SIN_RENGLONES;
    // Cobrar es lo único que, además, pide la caja abierta: se dice lo mismo que el aviso fijo de la caja.
    const sinCaja = this.avisoCaja();
    if (conRenglones && sinCaja !== null) motivos.cobrar = sinCaja;
    return motivos;
  });

  /** El motivo a la vista de la última acción que no se pudo usar, o null. Se anuncia con role="status" (S4). */
  protected readonly motivoVisible = signal<{ accion: AccionBotonera; texto: string } | null>(null);
  /** Id del aviso del motivo: la botonera enlaza con aria-describedby el botón que lo explica. */
  protected readonly avisoBotoneraId = AVISO_BOTONERA_ID;
  /** Temporizador que oculta el motivo; cada motivo nuevo arranca el suyo, como el resto de los avisos. */
  private ocultarMotivo: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    this.destroyRef.onDestroy(() => clearTimeout(this.ocultarAviso));
    this.destroyRef.onDestroy(() => clearTimeout(this.ocultarMotivo));
    this.leerCaja();
    this.leerFormas();
    this.leerFacturacion();
    this.completarCondicionDelCliente();
    // Solo el Dueño configura la facturación: al Vendedor el servidor le respondería 403.
    if (this.esDueno()) this.leerCertificado();
    this.busquedas
      .pipe(
        switchMap((q) =>
          q === null
            ? EMPTY
            : timer(ESPERA_MS).pipe(
                switchMap(() =>
                  this.service.buscar(q).pipe(
                    map((resultado) => ({ q, resultado })),
                    // El error se resuelve acá adentro para no matar el stream: la próxima búsqueda anda.
                    catchError((err: unknown) => {
                      this.alFallar(err);
                      return EMPTY;
                    }),
                  ),
                ),
              ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ q, resultado }) => this.mostrar(q, resultado, false));

    this.escaneos
      .pipe(
        concatMap((escaneo) =>
          this.service.buscar(escaneo.q).pipe(
            map((resultado) => ({ escaneo, resultado })),
            catchError((err: unknown) => {
              this.alFallar(err);
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ escaneo, resultado }) => {
        // El escaneo nunca se pierde: se agrega aunque el vendedor ya haya seguido con otra cosa.
        if (resultado.tipo === 'EXACTO') this.agregar(resultado.items[0]);
        // La pantalla, en cambio, es de lo último que hizo.
        if (resultado.tipo !== 'EXACTO' || escaneo.seq === this.seq) {
          this.mostrar(escaneo.q, resultado, true);
        }
      });
  }

  /** Lee la caja de la ferretería. Si no se puede leer no se frena nada: la venta la valida el backend. */
  private leerCaja(): void {
    this.cajas
      .actual()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (vigente) => this.cajaVigente.set(vigente), error: () => undefined });
  }

  /** Lee las formas de pago activas. Si no se puede, queda la lista que había (o vacía). */
  private leerFormas(): void {
    this.formas
      .listar(true)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (formas) => this.formasPago.set(formas), error: () => undefined });
  }

  /**
   * T11: el ticket que arma «Pasar a venta» (de un presupuesto o un remito) trae el cliente sin su condición de IVA, y sin
   * ella el cobro no sabe la letra. Se lee el cliente de hoy, que es con el que el servidor factura. Si falla, el rótulo
   * queda en «Factura» y no se frena nada; si el cajero cambió de cliente mientras tanto, no se toca.
   */
  private completarCondicionDelCliente(): void {
    const { clienteId, clienteCondicionIva } = this.ticket.ticket();
    if (clienteId === undefined || clienteCondicionIva) return;
    this.clientes
      .obtener(clienteId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (cliente) => {
          const actual = this.ticket.ticket();
          if (actual.clienteId === cliente.id && !actual.clienteCondicionIva) this.ticket.elegirCliente(cliente);
        },
        error: () => undefined,
      });
  }

  /** Lee qué comprobante sale al cobrar. Si no se puede, el cobro no muestra rótulo (como sin facturación). */
  private leerFacturacion(): void {
    this.ventas
      .facturacion()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (f) => this.facturacion.set(f), error: () => undefined });
  }

  /** Lee el vencimiento del certificado de ARCA. Si no se puede, no se avisa nada. */
  private leerCertificado(): void {
    this.configuracionArca
      .configuracion()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (config) => this.vencimientoCertificado.set(vencimientoParaElMostrador(config)),
        error: () => undefined,
      });
  }

  /** Todo cambio del aviso pasa por acá: un aviso nuevo (o ninguno) cancela el plazo del anterior. */
  private avisar(aviso: Aviso | null, ocultarEnMs?: number): void {
    clearTimeout(this.ocultarAviso);
    this.ocultarAviso = undefined;
    this.aviso.set(aviso);
    if (aviso && ocultarEnMs !== undefined) {
      this.ocultarAviso = setTimeout(() => this.avisar(null), ocultarEnMs);
    }
  }

  /** Agrega 1 unidad al ticket. Un dado de baja sale en la búsqueda pero no entra: se avisa en rojo. */
  private agregar(articulo: ArticuloMostrador): void {
    if (!articulo.activo) {
      this.avisar({ texto: `"${articulo.descripcion}" está dado de baja.`, grave: true, tipo: 'persistente' });
      return;
    }
    if (articulo.precioFinal <= 0) {
      this.avisar({ texto: `"${articulo.descripcion}" no tiene precio.`, grave: true, tipo: 'persistente' });
      return;
    }
    this.vistos.set(articulo.id, articulo);
    this.ticket.agregar(articulo);
    const tipo = this.aviso()?.tipo;
    if (tipo === 'vacio' || tipo === 'persistente') this.avisar(null);
  }

  private mostrar(q: string, resultado: BusquedaMostrador, inmediata: boolean): void {
    this.error.set(false);
    this.resultado.set(resultado);
    this.consultaMostrada.set(q);
    this.seleccionado.set(0);
    this.siguiendoTicket.set(false);
    this.ficha.set(resultado.tipo === 'EXACTO' ? resultado.items[0] : null);
    // Tras escanear, el próximo código tiene que reemplazar al anterior, no sumarse.
    if (inmediata) this.caja().seleccionarTodo();
    this.enfocarSinDialogo();
  }

  /**
   * Clic en un renglón del ticket: queda elegido y el foco vuelve al buscador, así el lector sigue andando. En el celular
   * (movil-sectores T8a) abre además la hoja del renglón, con sus tres acciones.
   */
  protected elegirRenglon(indice: number): void {
    if (this.dialogo()) return;
    this.ticket.elegir(indice);
    if (esCelular() && this.renglonDe(indice)) {
      this.avisar(null);
      this.dialogo.set({ tipo: 'renglon', indice });
      return;
    }
    this.enfocarSinDialogo();
  }

  /** Una acción de la hoja del renglón: abre el mismo diálogo que la tecla (F3, F7 o F10) sobre ese renglón. */
  protected accionDeRenglon(accion: 'cantidad' | 'descuento' | 'quitar', indice: number): void {
    this.avisar(null);
    this.dialogo.set({ tipo: accion, indice });
  }

  /** Con un diálogo abierto el buscador no le roba el foco (R-039). */
  private enfocarSinDialogo(): void {
    if (!this.dialogo()) this.caja().enfocar();
  }

  /**
   * Atajos (mapa en `mostrador.atajos.ts`). Todos anulan la acción del navegador. Esc cierra el diálogo abierto o limpia
   * la pantalla; cualquier otra tecla hace lo mismo que su botón, incluso cuando la acción no se puede usar: en ese caso
   * el mostrador dice por qué en vez de no hacer nada (S4).
   */
  protected alTeclear(evento: KeyboardEvent): void {
    const accion = accionDeTecla(evento.key);
    if (!accion) return;
    evento.preventDefault();
    if (accion === 'cerrar') {
      if (this.dialogo()) this.cerrarDialogo();
      else this.limpiar();
      return;
    }
    this.intentar(accion);
  }

  protected alBotonera(accion: AccionBotonera): void {
    this.intentar(accion);
  }

  /**
   * Hace la acción o, si hoy no se puede, dice por qué sin ejecutar nada (S4). El aviso no mueve el foco: el vendedor
   * sigue tipeando en la caja de búsqueda y el motivo se anuncia solo con role="status".
   */
  private intentar(accion: AccionBotonera): void {
    const motivo = this.motivos()[accion];
    this.callarMotivo();
    if (motivo !== undefined) {
      this.motivoVisible.set({ accion, texto: motivo });
      this.ocultarMotivo = setTimeout(() => this.motivoVisible.set(null), MOTIVO_A_LA_VISTA_MS);
      return;
    }
    this.ejecutar(accion);
  }

  /** Saca el motivo a la vista y su plazo: lo hace la próxima acción que sí se puede. */
  private callarMotivo(): void {
    clearTimeout(this.ocultarMotivo);
    this.ocultarMotivo = undefined;
    this.motivoVisible.set(null);
  }

  private ejecutar(accion: AccionBotonera): void {
    const renglones = this.ticket.ticket().renglones;
    switch (accion) {
      case 'buscar':
        this.caja().enfocar();
        return;
      case 'cobrar': {
        if (this.formasPago().length === 0) {
          this.leerFormas();
          this.avisar({ texto: MENSAJE_SIN_FORMAS, grave: true, tipo: 'otro' });
          return;
        }
        this.avisar(null);
        this.leerFormas();
        this.avisoLimite.set(null);
        this.leerCuenta();
        this.dialogo.set({ tipo: 'cobro' });
        return;
      }
      case 'presupuesto':
        this.avisar(null);
        this.dialogo.set({ tipo: 'presupuesto' });
        return;
      case 'remito':
        this.avisar(null);
        this.dialogo.set({ tipo: 'remito' });
        return;
      case 'cliente':
        this.avisar(null);
        this.dialogo.set({ tipo: 'cliente' });
        return;
      case 'vaciar':
        this.avisar(null);
        this.dialogo.set({ tipo: 'vaciar' });
        return;
      case 'descuentoTotal':
        this.avisar(null);
        this.dialogo.set({ tipo: 'descuentoTotal' });
        return;
      case 'cantidad':
      case 'descuento':
      case 'quitar': {
        // Sin renglón elegido, el último agregado.
        const indice = this.ticket.ticket().elegido ?? renglones.length - 1;
        if (!renglones[indice]) return;
        this.accionDeRenglon(accion, indice);
        return;
      }
    }
  }

  protected cerrarDialogo(): void {
    // No se cierra el cobro mientras viaja la venta: se perdería el resultado.
    if (this.enVuelo()) return;
    this.dialogo.set(null);
    this.avisoCobro.set(null);
    this.soltarCuenta();
    this.caja().enfocar();
  }

  /**
   * Pide la cuenta corriente del cliente del ticket con lo que se va a cobrar (el total sin recargo: la cuenta corriente no
   * lleva recargo). Es un consejo: el servidor decide al vender. Sin cliente no hay nada que pedir.
   */
  private leerCuenta(): void {
    this.lecturaCuenta?.unsubscribe();
    const { clienteId } = this.ticket.ticket();
    if (clienteId === undefined) {
      this.cuentaCobro.set(null);
      return;
    }
    this.cuentaCobro.set({ estado: 'cargando' });
    this.lecturaCuenta = this.clientes
      .cuenta(clienteId, this.ticket.total() / 100)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (cuenta) => this.cuentaCobro.set({ estado: 'listo', cuenta }),
        // Falla cerrada: sin la cuenta no se ofrece cobrar a cuenta corriente.
        error: () => this.cuentaCobro.set({ estado: 'error' }),
      });
  }

  /** El cobro se cerró: no queda nada de la cuenta (la próxima apertura la pide de nuevo). */
  private soltarCuenta(): void {
    this.lecturaCuenta?.unsubscribe();
    this.lecturaCuenta = undefined;
    this.cuentaCobro.set(null);
    this.avisoLimite.set(null);
  }

  protected cambiarCantidad(indice: number, milesimas: number): void {
    const error = this.ticket.cambiarCantidad(indice, milesimas);
    if (error) this.avisar({ texto: error, grave: true, tipo: 'otro' });
    this.cerrarDialogo();
  }

  /** El descuento propio de un renglón (F7), en centésimas de punto porcentual; 0 lo saca. */
  protected aplicarDescuentoRenglon(indice: number, bp: number): void {
    const error = this.ticket.aplicarDescuentoRenglon(indice, bp);
    if (error) this.avisar({ texto: error, grave: true, tipo: 'otro' });
    this.cerrarDialogo();
  }

  /** F9: el cliente de la venta, del presupuesto o del remito. */
  protected elegirCliente(cliente: Cliente): void {
    this.ticket.elegirCliente(cliente);
    this.cerrarDialogo();
  }

  /** Vuelve a Consumidor Final (desde el diálogo o desde el ticket). */
  protected quitarCliente(): void {
    this.ticket.quitarCliente();
    this.cerrarDialogo();
  }

  /** El descuento de todo el ticket (F6), en centésimas de punto porcentual; 0 lo saca. */
  protected aplicarDescuentoGlobal(bp: number): void {
    const error = this.ticket.aplicarDescuentoGlobal(bp);
    if (error) this.avisar({ texto: error, grave: true, tipo: 'otro' });
    this.cerrarDialogo();
  }

  protected quitar(indice: number): void {
    this.ticket.quitar(indice);
    this.siguiendoTicket.set(true);
    this.cerrarDialogo();
  }

  protected vaciarTicket(): void {
    this.ticket.vaciar();
    this.siguiendoTicket.set(true);
    this.cerrarDialogo();
  }

  /** Un escaneo llegó con un diálogo abierto: el diálogo lo descartó y acá se avisa. */
  protected escaneoDescartado(): void {
    const tipo = this.dialogo()?.tipo;
    if (tipo === 'cobro' || tipo === 'presupuesto' || tipo === 'remito' || tipo === 'factura' || tipo === 'documento') {
      this.avisoCobro.set(AVISO_ESCANEO_CON_DIALOGO);
    }
    else this.avisar({ texto: AVISO_ESCANEO_CON_DIALOGO, grave: false, tipo: 'otro' });
  }

  /** Enter en el diálogo de cobro: manda la venta con la clave del ticket (la misma en cada reintento). */
  protected cobrar(confirmacion: ConfirmacionCobro): void {
    if (this.enVuelo()) return;
    const pedido = aRequest(
      this.ticket.ticket(),
      confirmacion.pagos ?? (confirmacion.formaPagoId as number),
      confirmacion.recargoCentavos,
      confirmacion.noFacturable === true,
      confirmacion.autorizaExceso === true,
    );
    this.recibidoEnVuelo = confirmacion.recibidoCentavos;
    this.enVuelo.set(true);
    this.avisoCobro.set(null);
    this.ventas
      .cobrar(pedido)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (cobrada) => this.alCobrar(cobrada, pedido),
        error: (err: unknown) => this.alFallarCobro(err),
      });
  }

  private alCobrar({ venta }: VentaCobrada, pedido: ConfirmarVentaRequest): void {
    this.enVuelo.set(false);
    // Solo sale lo que viajó en el POST: un escaneo que volvió en el medio queda para la próxima venta.
    this.ticket.descontarVendido(pedido);
    this.siguiendoTicket.set(true);
    const sinStock = venta.sinStock
      .map((s) => `"${s.descripcion}" quedó en ${String(s.stockActual).replace('.', ',')}`)
      .join('; ');
    const vuelto = this.recibidoEnVuelo === null ? 0 : this.recibidoEnVuelo - aCentavos(venta.total);
    this.recibidoEnVuelo = null;
    const marca = venta.facturable === false ? ' (no facturable)' : '';
    const aviso: Aviso =
      vuelto > 0
        ? {
            texto: `Venta N.º ${venta.numero} cobrada${marca}`,
            grave: false,
            tipo: 'persistente',
            vueltoCentavos: vuelto,
          }
        : { texto: this.textoCobrada(venta, marca), grave: false, tipo: 'otro' };
    if (sinStock) {
      aviso.advertencia = `Sin stock: ${sinStock}.`;
      aviso.tipo = 'persistente';
    }
    // Con registro fiscal se abre la factura y el aviso ofrece reimprimirla (D7), con la misma respuesta del cobro. Sin el
    // documento (un servidor anterior a C1) no hay hoja que mostrar: todo sigue como sin factura.
    const factura: FacturaCobrada | null = venta.fiscal?.documento
      ? { venta: { ...venta, fiscal: venta.fiscal }, vueltoCentavos: vuelto > 0 ? vuelto : null }
      : null;
    if (factura) {
      aviso.factura = factura;
      aviso.tipo = 'persistente';
    }
    // El vuelto, el sin stock y la factura se necesitan después del plazo habitual: quedan hasta el próximo renglón.
    this.avisar(aviso, aviso.tipo === 'persistente' ? undefined : AVISO_COBRADA_MS);
    if (factura) this.abrirFactura(factura);
    else this.cerrarDialogo();
  }

  /**
   * Abre la factura de la venta cobrada (o la reabre desde el aviso, sin pedir nada al servidor). Esc la cierra y el foco
   * vuelve al buscador, como cualquier diálogo del mostrador.
   */
  protected abrirFactura(factura: FacturaCobrada): void {
    this.avisoCobro.set(null);
    this.soltarCuenta();
    this.dialogo.set({ tipo: 'factura', factura });
  }

  /**
   * «Reimprimir factura» del aviso «Llegó el CAE» (C7): la hoja fiscal de esa venta se abre como la del cobro, sin vuelto.
   * El aviso solo la entrega si no hay otro diálogo abierto (`sinDialogo`).
   */
  protected reimprimirLlegada(venta: VentaFacturada): void {
    this.abrirFactura({ venta, vueltoCentavos: null });
  }

  /** Se cerró un aviso con su botón: el foco vuelve al buscador, para el lector. */
  protected alCerrarAviso(): void {
    this.enfocarSinDialogo();
  }

  /** Abre o cierra los avisos desde la franja del celular (móvil-pulgar, T4). */
  protected alternarAvisos(): void {
    this.avisosAbiertos.update((abiertos) => !abiertos);
  }

  /** La cuenta de AvisosFacturacion cambió: la franja del celular la suma a la suya. */
  protected contarAvisos(cantidad: number): void {
    this.avisosDeFacturacion.set(cantidad);
  }

  /** «Venta N.º 7 cobrada: $ 3.000,00»; a cuenta corriente lo dice, y en un pago mixto cuánto fue a cuenta. */
  private textoCobrada(venta: VentaResponse, marca: string): string {
    const total = formatearMonto(venta.total);
    const pagos = venta.pagos ?? [];
    const aCuenta = pagos
      .filter((p) => p.medio === 'CUENTA_CORRIENTE')
      .reduce((suma, p) => suma + aCentavos(p.monto), 0);
    const todoACuenta = venta.medioPago === 'CUENTA_CORRIENTE' || (aCuenta > 0 && aCuenta === aCentavos(venta.total));
    if (todoACuenta) return `Venta N.º ${venta.numero} cobrada a cuenta corriente${marca}: ${total}`;
    if (aCuenta > 0) {
      return `Venta N.º ${venta.numero} cobrada${marca}: ${total} (${formatearMonto(aCuenta / 100)} a cuenta corriente)`;
    }
    return `Venta N.º ${venta.numero} cobrada${marca}: ${total}`;
  }

  /** Enter en el diálogo de presupuesto (F8): guarda el ticket con la clave del ticket (la misma en cada reintento). */
  protected guardarPresupuesto(datos: DatosPresupuesto): void {
    if (this.enVuelo()) return;
    const pedido = aPresupuestoRequest(this.ticket.ticket(), datos);
    this.enVuelo.set(true);
    this.avisoCobro.set(null);
    this.presupuestos
      .emitir(pedido)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ presupuesto }) => this.alGuardar(presupuesto),
        error: (err: unknown) => this.alFallarEnvio(err, MENSAJE_PRESUPUESTO_FALLIDO, false),
      });
  }

  /**
   * El presupuesto quedó guardado: el ticket se vacía (no se vendió nada), se avisa el número con el link a su detalle y se
   * abre su hoja para imprimir (informacion-y-links, S1).
   */
  private alGuardar(presupuesto: PresupuestoResponse): void {
    this.ticket.vaciar();
    this.siguiendoTicket.set(true);
    const aviso: Aviso = {
      texto: `Presupuesto N.º ${presupuesto.numero} guardado: ${formatearMonto(presupuesto.total)}`,
      grave: false,
      tipo: 'persistente',
      documento: { texto: 'Ver el presupuesto', ruta: `/presupuestos/${presupuesto.presupuestoId}` },
    };
    this.avisar(aviso);
    this.abrirHoja(documentoDePresupuesto(presupuesto), aviso);
  }

  /**
   * La hoja del documento recién guardado, con los datos de la ferretería de hoy (S1): Enter imprime y Esc la cierra. El
   * diálogo del presupuesto o del remito sigue «en vuelo» hasta tenerlos. Si no se pueden leer, el documento ya quedó
   * guardado: se cierra el diálogo y el aviso dice que se imprime desde su link.
   */
  private abrirHoja(documento: DocumentoNoFiscal, aviso: Aviso): void {
    this.emisorApi
      .obtener()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (emisor) => {
          this.enVuelo.set(false);
          this.avisoCobro.set(null);
          this.dialogo.set({ tipo: 'documento', documento, emisor });
        },
        error: () => {
          this.enVuelo.set(false);
          const sinHoja = `No se pudo abrir la hoja para imprimirlo: abrilo con «${aviso.documento?.texto}» e imprimilo desde ahí.`;
          this.avisar({ ...aviso, advertencia: aviso.advertencia ? `${aviso.advertencia} ${sinHoja}` : sinHoja });
          this.cerrarDialogo();
        },
      });
  }

  /** Enter en el diálogo del remito: lo emite con la clave del ticket (la misma en cada reintento). */
  protected emitirRemito(datos: DatosRemito): void {
    if (this.enVuelo()) return;
    const pedido = aRemitoRequest(this.ticket.ticket(), datos);
    this.enVuelo.set(true);
    this.avisoCobro.set(null);
    this.remitos
      .emitir(pedido)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ remito }) => this.alEmitir(remito),
        error: (err: unknown) => this.alFallarEnvio(err, MENSAJE_REMITO_FALLIDO, false),
      });
  }

  /**
   * El remito quedó emitido (y descontó el stock): el ticket se vacía, se avisa el número con el link a su detalle y lo que
   * quedó sin stock, y se abre su hoja para imprimir: acompaña la mercadería (informacion-y-links, S1).
   */
  private alEmitir(remito: RemitoEmitido['remito']): void {
    this.ticket.vaciar();
    this.siguiendoTicket.set(true);
    const sinStock = (remito.sinStock ?? [])
      .map((s) => `"${s.descripcion}" quedó en ${String(s.stockActual).replace('.', ',')}`)
      .join('; ');
    const aviso: Aviso = {
      texto: `Remito N.º ${remito.numero} emitido: ${formatearMonto(remito.total)}`,
      grave: false,
      tipo: 'persistente',
      documento: { texto: 'Ver el remito', ruta: `/remitos/${remito.remitoId}` },
      ...(sinStock ? { advertencia: `Sin stock: ${sinStock}.` } : {}),
    };
    this.avisar(aviso);
    this.abrirHoja(documentoDeRemito(remito), aviso);
  }

  private alFallarCobro(err: unknown): void {
    this.alFallarEnvio(err, MENSAJE_COBRO_FALLIDO, true);
  }

  /**
   * Un POST del ticket (venta o presupuesto) falló. Los rechazos del ticket son los mismos; `esCobro` agrega lo que
   * solo importa al vender: releer las formas de pago y la caja.
   */
  private alFallarEnvio(err: unknown, mensajeGenerico: string, esCobro: boolean): void {
    this.enVuelo.set(false);
    const rechazo = rechazoDe(err);
    if (!rechazo) {
      if (esCobro && this.origenNoSirve(err)) return;
      // Un 403 al cobrar: autorizar el exceso de la cuenta corriente, o facturar un remito por menos de lo que entregó
      // (devuelve stock), es solo del Dueño. Se muestra el mensaje del servidor; el ticket y el cobro siguen.
      if (err instanceof HttpErrorResponse && err.status === 403) {
        this.avisoCobro.set(mensajeDe(err, MENSAJE_SIN_PERMISO));
        return;
      }
      // Un 503: el servidor agotó los reintentos por contención y no quedó nada hecho. El diálogo sigue abierto y el
      // reintento usa la misma clave del ticket.
      if (esOcupado(err)) {
        this.avisoCobro.set(mensajeDe(err, MENSAJE_OCUPADO));
        return;
      }
      const invalida = invalidaDe(err);
      if (invalida) this.alVentaInvalida(invalida);
      else this.avisoCobro.set(mensajeGenerico);
      return;
    }
    switch (rechazo.motivo) {
      case 'PRECIOS_CAMBIADOS':
        // También cambia el total si el Dueño tocó el recargo de la forma: se vuelven a leer.
        if (esCobro) this.leerFormas();
        this.ticket.actualizarPrecios(
          rechazo.articulos.flatMap((a) =>
            a.precioActual === undefined
              ? []
              : [{ articuloId: a.articuloId, precioActual: a.precioActual }],
          ),
        );
        // El total cambió: lo que se le pregunta a la cuenta corriente también.
        if (esCobro) this.leerCuenta();
        this.avisoCobro.set(this.textoPrecios(rechazo));
        return;
      case 'ARTICULO_DADO_DE_BAJA':
        this.ticket.marcarProblemas(
          rechazo.articulos.map((a) => ({ articuloId: a.articuloId, mensaje: PROBLEMA_DADO_DE_BAJA })),
        );
        this.rechazarTicket(rechazo.message);
        return;
      case 'ARTICULO_SIN_PRECIO':
        this.ticket.marcarProblemas(
          rechazo.articulos.map((a) => ({ articuloId: a.articuloId, mensaje: PROBLEMA_SIN_PRECIO })),
        );
        this.rechazarTicket(rechazo.message);
        return;
      case 'ARTICULO_INEXISTENTE':
        this.ticket.marcarProblemas(
          rechazo.articulos.map((a) => ({ articuloId: a.articuloId, mensaje: PROBLEMA_INEXISTENTE })),
        );
        this.rechazarTicket(this.textoInexistentes(rechazo));
        return;
      case 'CLAVE_CON_OTRO_CONTENIDO':
        this.rechazarTicket(rechazo.message);
        return;
      case 'LIMITE_CUENTA_CORRIENTE':
        // La venta se pasa del límite: el cobro sigue abierto. El Dueño ve el exceso y puede autorizarlo; el Vendedor lee
        // «pedile al Dueño». El saldo pudo cambiar: se vuelve a pedir la cuenta.
        this.avisoLimite.set(rechazo.cuentaCorriente ?? null);
        this.avisoCobro.set(rechazo.message);
        this.leerCuenta();
        return;
      case 'CUENTA_CORRIENTE_NO_HABILITADA':
        // Le sacaron la cuenta corriente al cliente mientras se cobraba: se relee y la opción queda explicada.
        this.avisoCobro.set(rechazo.message);
        this.leerCuenta();
        return;
      case 'CONSUMIDOR_FINAL_SIN_DATOS':
        // GDS-32: desde el tope hace falta un cliente con documento. El cobro sigue abierto con el mensaje; se elige el
        // cliente (F9) y se vuelve a cobrar con el mismo ticket.
        this.avisoCobro.set(rechazo.message);
        return;
      case 'CLIENTE_SIN_DATOS_FISCALES':
        // D4: el cliente es Responsable Inscripto, Monotributista o Exento y le falta el CUIT o el domicilio para facturarle.
        // No se guardó nada: el cobro sigue abierto con el mensaje; se completan sus datos en Clientes (o se elige otro
        // cliente con F9) y se vuelve a cobrar con el mismo ticket.
        this.avisoCobro.set(rechazo.message);
        return;
      case 'CAJA_CERRADA':
      case 'CAJA_DE_OTRO_DIA':
        // Otro usuario cerró la caja (o cambió el día) mientras se armaba el ticket: el ticket se conserva.
        this.leerCaja();
        this.rechazarTicket(rechazo.message);
        return;
    }
  }

  /**
   * El servidor no acepta el presupuesto o el remito del que sale el ticket (404 que no existe, 409 rechazado, anulado o ya
   * facturado): se suelta y el ticket sigue como una venta común, con el mensaje del servidor. Esos errores no traen
   * `motivo`, así que son los únicos 404 y 409 sin motivo que puede dar una venta. Devuelve si lo trató.
   */
  private origenNoSirve(err: unknown): boolean {
    const { presupuestoId, remitoId } = this.ticket.ticket();
    const desdePresupuesto = presupuestoId !== undefined;
    const desdeRemito = remitoId !== undefined;
    if (
      (!desdePresupuesto && !desdeRemito) ||
      !(err instanceof HttpErrorResponse) ||
      (err.status !== 404 && err.status !== 409)
    ) {
      return false;
    }
    if (desdeRemito) this.ticket.soltarRemito();
    else this.ticket.soltarPresupuesto();
    const documento = desdeRemito ? 'El remito' : 'El presupuesto';
    this.rechazarTicket(`${mensajeDe(err, `${documento} ya no se puede usar.`)} El ticket sigue como una venta común.`);
    return true;
  }

  /**
   * Un 400: reintentar no sirve porque los datos son inválidos. Se muestran los mensajes del servidor; si
   * alguno es de un renglón (`renglones[i].…`) se marca ese renglón para corregirlo o quitarlo.
   */
  private alVentaInvalida(invalida: VentaInvalida): void {
    const entradas = Object.entries(invalida.errors ?? {});
    // Una forma de pago inválida: la desactivaron o la borraron mientras tanto. Se vuelve a leer la lista.
    if (entradas.some(([campo]) => /(^|\.|\])formaPagoId$/.test(campo))) this.leerFormas();
    const texto = entradas.length > 0 ? [...new Set(entradas.map(([, m]) => m))].join(' ') : invalida.message;
    const renglones = this.ticket.ticket().renglones;
    const problemas = entradas.flatMap(([campo, mensaje]) => {
      const indice = /^renglones\[(\d+)\]\./.exec(campo)?.[1];
      const renglon = indice === undefined ? undefined : renglones[Number(indice)];
      // Un error del descuento del renglón (el tope, por ejemplo) se va solo al cambiar un descuento. QA 05/11, A-5.
      const deDescuento = /^renglones\[\d+\]\.descuentoPorcentaje$/.test(campo);
      return renglon ? [{ articuloId: renglon.articuloId, mensaje, deDescuento }] : [];
    });
    if (problemas.length === 0) {
      this.avisoCobro.set(texto);
      return;
    }
    this.ticket.marcarProblemas(problemas);
    this.rechazarTicket(texto);
  }

  /** El ticket no se vende como está: se cierra el cobro, se conserva el ticket y se explica por qué. */
  private rechazarTicket(texto: string): void {
    this.avisar({ texto, grave: true, tipo: 'otro' });
    this.cerrarDialogo();
  }

  private descripcionDe(articuloId: number): string {
    const r = this.ticket.ticket().renglones.find((x) => x.articuloId === articuloId);
    return r ? r.descripcion : 'Un artículo';
  }

  private textoPrecios(rechazo: VentaRechazada): string {
    const cambios = rechazo.articulos.filter((a) => a.precioActual !== undefined);
    if (cambios.length === 1) {
      const c = cambios[0];
      return `Cambió el precio de "${this.descripcionDe(c.articuloId)}": ahora ${formatearMonto(c.precioActual)}.`;
    }
    if (cambios.length > 1) {
      return `Cambiaron los precios de ${cambios.length} artículos. Revisá el total y confirmá de nuevo.`;
    }
    return rechazo.message;
  }

  /** El 404 solo trae ids: se nombran con la descripción que ya tiene el ticket. */
  private textoInexistentes(rechazo: VentaRechazada): string {
    const nombres = rechazo.articulos.map((a) => `"${this.descripcionDe(a.articuloId)}"`);
    if (nombres.length === 1) return `${nombres[0]} ya no está disponible. Sacalo del ticket para seguir.`;
    if (nombres.length > 1) {
      return `Hay artículos que ya no están disponibles: ${nombres.join(', ')}. Sacalos del ticket para seguir.`;
    }
    return rechazo.message;
  }

  /** Cerrar o recargar la página con un ticket a medias pide confirmación del navegador. */
  protected alSalir(evento: BeforeUnloadEvent): void {
    if (this.ticket.ticket().renglones.length === 0) return;
    evento.preventDefault();
    evento.returnValue = '';
  }

  protected renglonDe(indice: number) {
    return this.ticket.ticket().renglones[indice];
  }

  protected alCambiarTexto(texto: string): void {
    this.texto.set(texto);
    const q = texto.trim();
    if (q === '') this.vaciar();
    this.seq++;
    this.busquedas.next(q === '' ? null : q);
  }

  protected alEnter(): void {
    const q = this.texto().trim();
    if (q === '') return;
    const actual = this.resultado();
    if (actual?.tipo === 'LISTA' && !this.ficha() && q === this.consultaMostrada()) {
      this.elegir(this.seleccionado());
      return;
    }
    // Un escaneo cancela el debounce pendiente: el Enter ya hizo la búsqueda.
    this.seq++;
    this.busquedas.next(null);
    this.escaneos.next({ q, seq: this.seq });
  }

  protected alMover(direccion: 1 | -1): void {
    const actual = this.resultado();
    // Sin lista a la vista, las flechas eligen el renglón del ticket.
    if (actual?.tipo !== 'LISTA' || this.ficha()) {
      this.ticket.moverElegido(direccion);
      if (this.ticket.ticket().renglones.length > 0) this.siguiendoTicket.set(true);
      return;
    }
    const ultimo = actual.items.length - 1;
    this.seleccionado.update((i) => Math.min(Math.max(i + direccion, 0), ultimo));
  }

  /** Agrega 1 unidad de una fila de la lista al ticket y abre su ficha. */
  protected elegir(indice: number): void {
    const articulo = this.resultado()?.items[indice];
    if (!articulo) return;
    this.agregar(articulo);
    this.siguiendoTicket.set(false);
    this.ficha.set(articulo);
    this.caja().seleccionarTodo();
    this.caja().enfocar();
  }

  /** Esc: vuelve a la pantalla en blanco, cancelando lo que esté pendiente o en vuelo. */
  private limpiar(): void {
    this.seq++;
    this.busquedas.next(null);
    this.texto.set('');
    if (this.aviso()?.tipo === 'persistente') this.avisar(null);
    // La pantalla queda en blanco: el motivo de una acción que no se pudo usar ya no viene al caso.
    this.callarMotivo();
    this.vaciar();
    this.caja().enfocar();
  }

  private vaciar(): void {
    this.siguiendoTicket.set(false);
    this.resultado.set(null);
    this.consultaMostrada.set('');
    this.seleccionado.set(0);
    this.ficha.set(null);
    this.error.set(false);
  }

  private alFallar(err: unknown): void {
    this.vaciar();
    // El 401 lo trata el interceptor (manda al login): acá no se avisa nada.
    this.error.set(!(err instanceof HttpErrorResponse && err.status === 401));
    this.enfocarSinDialogo();
  }
}
