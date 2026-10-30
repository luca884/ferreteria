import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { PageResponse } from '../../core/api/page.models';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe } from '../../core/formato/monto';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Confirmacion } from '../../shared/confirmacion';
import { FacturaImprimible } from '../../shared/factura/factura-imprimible';
import {
  ESTADOS_EN_REVISION,
  ESTADO_FISCAL_LABELS,
  ResumenFiscal,
  VentaFacturada,
  numeroFiscal,
} from '../../shared/factura/factura.models';
import { fechaCompletaLocal } from '../clientes/cuenta/cobranza.models';
import { DialogoAnular } from './dialogo-anular';
import { DialogoSinFactura } from './dialogo-sin-factura';
import { VentaHoja } from './venta-hoja';
import { VentaImprimible } from './venta-imprimible';
import {
  Anulacion,
  FILTROS_FISCALES,
  FiltroFiscal,
  FiltroVentas,
  VentaConsultada,
  VentaResumen,
  clienteDeVenta,
  esNotaDeCredito,
  textoDeAnulacion,
  tituloDeComprobante,
} from './ventas.models';
import { VentasService } from './ventas.service';

const MENSAJE_LISTA = 'No se pudieron cargar las ventas.';
const MENSAJE_DETALLE = 'No se pudo cargar la venta.';
const MENSAJE_NUMERO = 'El número tiene que ser un entero.';
const MENSAJE_ACCION = 'No se pudo hacer. Probá de nuevo.';
/** C2: el texto del 409 FECHA_NUEVA_REQUERIDA, también para confirmar antes cuando el detalle ya lo anticipa. */
const MENSAJE_FECHA_NUEVA = 'La fecha de la venta ya no entra en ARCA. Confirmá que la factura salga con la fecha de hoy.';

/** La marca del ambiente en la columna «Factura»: en producción no hay. */
const AMBIENTE_LABELS: Record<string, string> = { SIMULADOR: 'Simulado', HOMOLOGACION: 'Homologación' };

/** El código de un rechazo de la bandeja (C2, C3), con el `motivo` como los demás rechazos de una venta. */
function motivoDelRechazo(err: unknown): string | null {
  if (!(err instanceof HttpErrorResponse)) return null;
  const motivo: unknown = err.error?.motivo;
  return typeof motivo === 'string' ? motivo : null;
}

/**
 * Consulta y reimpresión de ventas (G60), como la «Consulta de Facturas» de GDS: busca por número, fecha o cliente, muestra
 * la venta elegida y la reimprime. Sin borrar (GDS-15): «Anular» (CU-08) emite la nota de crédito, o la anulación interna
 * con motivo si la venta no tiene la factura autorizada, y las dos se ven como renglones propios. Solo el Dueño: con el
 * listado se podría sumar el efectivo del día y el cierre de caja dejaría de ser ciego.
 *
 * También es la bandeja de las facturas pendientes y rechazadas (CU-16 punto 7, rama 7): el filtro «Facturas», los errores
 * y las observaciones de ARCA en el detalle, y las acciones que el servidor ofrece hoy («Reintentar» y «Dejar sin factura»).
 */
@Component({
  selector: 'app-ventas',
  imports: [
    BarraAcciones,
    Confirmacion,
    DialogoAnular,
    DialogoSinFactura,
    FacturaImprimible,
    MontoPipe,
    VentaHoja,
    VentaImprimible,
  ],
  templateUrl: './ventas.html',
  styleUrl: './ventas.css',
})
export class Ventas implements OnInit {
  private readonly api = inject(VentasService);
  private readonly route = inject(ActivatedRoute);
  protected readonly auth = inject(AuthService);

  protected readonly fecha = fechaCompletaLocal;
  protected readonly cliente = clienteDeVenta;
  protected readonly esNota = esNotaDeCredito;
  protected readonly titulo = tituloDeComprobante;
  protected readonly anulacion = (a: Anulacion) => textoDeAnulacion(a, fechaCompletaLocal);

  protected readonly pagina = signal<PageResponse<VentaResumen> | null>(null);
  protected readonly cargando = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Lo que escribió el usuario en los filtros (todavía sin aplicar). */
  protected readonly numero = signal('');
  protected readonly clienteBuscado = signal('');
  protected readonly desde = signal('');
  protected readonly hasta = signal('');
  protected readonly numeroFactura = signal('');
  /** La bandeja elegida: '' son todas. */
  protected readonly fiscal = signal<FiltroFiscal | ''>('');
  protected readonly errorFiltros = signal<string | null>(null);
  /** Los filtros aplicados a la lista que se ve (sin `page`). */
  private aplicados: Omit<FiltroVentas, 'page'> = {};
  /** Los de la última lista que cargó: si el servidor rechaza un filtro nuevo (400), se vuelve a estos. */
  private vigentes: Omit<FiltroVentas, 'page'> = {};
  protected readonly filtrada = signal(false);

  protected readonly elegida = signal<number | null>(null);
  protected readonly detalle = signal<VentaConsultada | null>(null);
  protected readonly errorDetalle = signal<string | null>(null);
  protected readonly imprimiendo = signal(false);
  /** La venta elegida, si tiene registro fiscal con su documento: «Imprimir» imprime la factura (CU-16). */
  protected readonly facturada = computed<VentaFacturada | null>(() => {
    const venta = this.detalle();
    return venta?.fiscal?.documento ? { ...venta, fiscal: venta.fiscal } : null;
  });
  /**
   * La factura en una línea, arriba del detalle de siempre: «Factura B 00001-00000012 · Autorizada», o «X · <marca>» si
   * no es válida como factura. Sale solo del documento, como la hoja.
   */
  protected readonly lineaFiscal = computed<string | null>(() => {
    const documento = this.facturada()?.fiscal.documento;
    if (!documento) return null;
    if (!documento.validoComoFactura) return documento.marca ? `X · ${documento.marca}` : 'X';
    const { letra, puntoVenta, numero } = documento;
    const fiscal = puntoVenta != null && numero != null ? ` ${numeroFiscal(puntoVenta, numero)}` : '';
    const clase = this.esNota(this.detalle()!) ? 'Nota de crédito' : 'Factura';
    return `${clase} ${letra}${fiscal} · Autorizada`;
  });

  /** El registro fiscal de la venta elegida, con lo de la bandeja (C1). */
  protected readonly fiscalElegido = computed(() => this.detalle()?.fiscal ?? null);
  /** «Estado de la factura: Rechazada»; null si está autorizada (la línea fiscal ya lo dice) o sin registro. */
  protected readonly estadoFactura = computed(() => {
    const estado = this.fiscalElegido()?.estado;
    return estado && estado !== 'AUTORIZADO' ? ESTADO_FISCAL_LABELS[estado] : null;
  });
  protected readonly enRevision = computed(() => {
    const estado = this.fiscalElegido()?.estado;
    return estado !== undefined && ESTADOS_EN_REVISION.includes(estado);
  });
  /** Las acciones las decide el servidor (C1): sin la lista, ninguna. */
  protected readonly puedeReintentar = computed(() => this.fiscalElegido()?.acciones?.includes('REINTENTAR') ?? false);
  protected readonly puedeDejarSinFactura = computed(
    () => this.fiscalElegido()?.acciones?.includes('DEJAR_SIN_FACTURA') ?? false,
  );

  /** Hay un «Reintentar» o un «Dejar sin factura» en curso: los botones de la bandeja esperan. */
  protected readonly enVueloAccion = signal(false);
  /** El reintento espera el CAE como el cobro: mientras tanto se avisa. */
  protected readonly reintentando = signal(false);
  /** El rechazo de «Reintentar», tal cual, al lado de la acción. */
  protected readonly errorAccion = signal<string | null>(null);
  /** El texto de la confirmación de la fecha de hoy, mientras se pregunta (C2). */
  protected readonly confirmandoFecha = signal<string | null>(null);
  protected readonly pidiendoMotivo = signal(false);
  /** El rechazo de «Dejar sin factura», tal cual, dentro de su diálogo. */
  protected readonly errorMotivo = signal<string | null>(null);
  /** «Anular» (CU-08): solo una venta que no está anulada; una nota de crédito no se anula. */
  protected readonly puedeAnular = computed(() => {
    const venta = this.detalle();
    return venta !== null && !this.esNota(venta) && !venta.anulacion;
  });
  /** Sin la factura autorizada la venta se anula sin documento fiscal y pide el motivo (D12); el servidor decide. */
  protected readonly anularConMotivo = computed(() => this.fiscalElegido()?.estado !== 'AUTORIZADO');
  protected readonly confirmandoAnular = signal(false);
  /** El rechazo de «Anular» (400 del motivo, 409, 422), tal cual, dentro de su diálogo. */
  protected readonly errorAnular = signal<string | null>(null);

  /** Lo que hizo la última acción: el detalle se puede cerrar si la venta sale de la bandeja. */
  protected readonly resultadoAccion = signal<string | null>(null);

  ngOnInit(): void {
    if (!this.auth.esDueno()) return;
    // El aviso del mostrador trae la bandeja en la dirección (`?fiscal=RECHAZADAS`): se abre ya filtrada.
    const fiscal = this.route.snapshot.queryParamMap.get('fiscal');
    const bandeja = FILTROS_FISCALES.find((f) => f === fiscal);
    if (bandeja) {
      this.fiscal.set(bandeja);
      this.aplicados = { fiscal: bandeja };
      this.filtrada.set(true);
    }
    this.cargar(0);
  }

  protected cargar(numeroDePagina: number): void {
    this.cargando.set(true);
    this.error.set(null);
    this.api.listar({ page: numeroDePagina, ...this.aplicados }).subscribe({
      next: (pagina) => {
        this.pagina.set(pagina);
        this.cargando.set(false);
        this.vigentes = this.aplicados;
        // Un detalle abierto de una venta que la lista nueva ya no muestra confunde: se cierra.
        const elegida = this.elegida();
        if (elegida !== null && !pagina.items.some((v) => v.ventaId === elegida)) this.cerrarDetalle();
      },
      error: (err: unknown) => {
        this.cargando.set(false);
        // Un filtro que el servidor no acepta (el número de factura, S15): su mensaje va con los filtros y la lista que se
        // ve sigue siendo la de los filtros anteriores.
        if (err instanceof HttpErrorResponse && err.status === 400) {
          const campos = erroresDeCampos(err);
          this.errorFiltros.set(campos.length > 0 ? campos.join(' ') : mensajeDe(err, MENSAJE_LISTA));
          this.aplicados = this.vigentes;
          this.filtrada.set(Object.keys(this.aplicados).length > 0);
          return;
        }
        this.error.set(MENSAJE_LISTA);
      },
    });
  }

  /**
   * «B 00001-00000012» y, aparte, el estado si no está autorizada y el ambiente si no es producción, y si hay que
   * reimprimirla (salió con ticket provisorio y ya tiene CAE). Vacía sin factura.
   */
  protected factura(
    fiscal: ResumenFiscal | null | undefined,
  ): { texto: string; marcas: string; reimprimir: boolean } | null {
    if (!fiscal) return null;
    const numero = fiscal.numero == null ? '' : ` ${numeroFiscal(fiscal.puntoVenta, fiscal.numero)}`;
    const marcas = [
      fiscal.estado === 'AUTORIZADO' ? null : ESTADO_FISCAL_LABELS[fiscal.estado],
      AMBIENTE_LABELS[fiscal.ambiente] ?? null,
    ].filter((m): m is string => m !== null);
    return { texto: `${fiscal.letra}${numero}`, marcas: marcas.join(' · '), reimprimir: fiscal.reimprimir === true };
  }

  /** Aplica los filtros y vuelve a la primera página. Un número que no es un entero positivo no se manda. */
  protected buscar(evento: Event): void {
    evento.preventDefault();
    const numero = this.numero().trim();
    if (numero !== '' && !/^[1-9]\d{0,17}$/.test(numero)) {
      this.errorFiltros.set(MENSAJE_NUMERO);
      return;
    }
    this.errorFiltros.set(null);
    this.resultadoAccion.set(null);
    const cliente = this.clienteBuscado().trim();
    const numeroFiscal = this.numeroFactura().trim();
    const fiscal = this.fiscal();
    this.aplicados = {
      ...(numero ? { numero: Number(numero) } : {}),
      ...(cliente ? { cliente } : {}),
      ...(this.desde() ? { desde: this.desde() } : {}),
      ...(this.hasta() ? { hasta: this.hasta() } : {}),
      ...(numeroFiscal ? { numeroFiscal } : {}),
      ...(fiscal ? { fiscal } : {}),
    };
    this.filtrada.set(Object.keys(this.aplicados).length > 0);
    this.cargar(0);
  }

  protected limpiar(): void {
    this.numero.set('');
    this.clienteBuscado.set('');
    this.desde.set('');
    this.hasta.set('');
    this.numeroFactura.set('');
    this.fiscal.set('');
    this.errorFiltros.set(null);
    this.resultadoAccion.set(null);
    this.aplicados = {};
    this.filtrada.set(false);
    this.cargar(0);
  }

  /** El valor del select «Facturas»: uno que no es de la bandeja vuelve a «Todas». */
  protected elegirFiscal(valor: string): void {
    this.fiscal.set(FILTROS_FISCALES.find((f) => f === valor) ?? '');
  }

  private cerrarDetalle(): void {
    this.elegida.set(null);
    this.detalle.set(null);
    this.errorDetalle.set(null);
    this.imprimiendo.set(false);
    this.soltarAcciones();
  }

  /** Lo de las acciones es de la venta elegida: otra venta (o ninguna) arranca sin diálogos ni mensajes. */
  private soltarAcciones(): void {
    this.errorAccion.set(null);
    this.confirmandoFecha.set(null);
    this.pidiendoMotivo.set(false);
    this.errorMotivo.set(null);
    this.confirmandoAnular.set(false);
    this.errorAnular.set(null);
  }

  protected elegir(fila: VentaResumen): void {
    const id = fila.ventaId;
    this.elegida.set(id);
    this.detalle.set(null);
    this.errorDetalle.set(null);
    this.imprimiendo.set(false);
    this.soltarAcciones();
    this.resultadoAccion.set(null);
    this.leerDetalle(id);
  }

  private leerDetalle(id: number): void {
    this.api.obtener(id).subscribe({
      next: (venta) => {
        // Si mientras tanto se eligió otra, esta respuesta ya no importa.
        if (this.elegida() === id) this.detalle.set(venta);
      },
      error: () => {
        if (this.elegida() === id) this.errorDetalle.set(MENSAJE_DETALLE);
      },
    });
  }

  /**
   * «Reintentar» (C2). Si el detalle ya dice que la fecha no entra en ARCA, primero se confirma la fecha de hoy: la fecha
   * nunca cambia sola.
   */
  protected reintentar(): void {
    const venta = this.detalle();
    if (!venta || this.enVueloAccion()) return;
    this.errorAccion.set(null);
    if (venta.fiscal?.fechaNuevaRequerida) {
      this.confirmandoFecha.set(MENSAJE_FECHA_NUEVA);
      return;
    }
    this.mandarReintento(venta, false);
  }

  protected confirmarFechaDeHoy(): void {
    const venta = this.detalle();
    this.confirmandoFecha.set(null);
    if (venta) this.mandarReintento(venta, true);
  }

  private mandarReintento(venta: VentaConsultada, fechaDeHoy: boolean): void {
    const id = venta.ventaId;
    this.enVueloAccion.set(true);
    this.reintentando.set(true);
    this.api.reintentar(id, fechaDeHoy).subscribe({
      next: () => {
        this.terminarAccion();
        this.alTerminarAccion(id, `Se reintentó la factura de la venta N.º ${venta.numero}.`);
      },
      error: (err: unknown) => {
        this.terminarAccion();
        if (this.elegida() !== id) return;
        // La fecha dejó de entrar en ARCA mientras tanto: se pide la confirmación con el texto del servidor.
        if (!fechaDeHoy && motivoDelRechazo(err) === 'FECHA_NUEVA_REQUERIDA') {
          this.confirmandoFecha.set(mensajeDe(err, MENSAJE_FECHA_NUEVA));
          return;
        }
        this.errorAccion.set(mensajeDe(err, MENSAJE_ACCION));
      },
    });
  }

  protected pedirMotivo(): void {
    if (this.enVueloAccion()) return;
    this.errorAccion.set(null);
    this.errorMotivo.set(null);
    this.pidiendoMotivo.set(true);
  }

  protected cerrarMotivo(): void {
    this.pidiendoMotivo.set(false);
    this.errorMotivo.set(null);
  }

  /** «Dejar sin factura» (C3): el 400 del motivo y el 409 quedan en el diálogo, con el motivo escrito. */
  protected dejarSinFactura(motivo: string): void {
    const venta = this.detalle();
    if (!venta || this.enVueloAccion()) return;
    const id = venta.ventaId;
    this.enVueloAccion.set(true);
    this.errorMotivo.set(null);
    this.api.dejarSinFactura(id, motivo).subscribe({
      next: () => {
        this.terminarAccion();
        this.pidiendoMotivo.set(false);
        this.alTerminarAccion(id, `La venta N.º ${venta.numero} quedó sin factura.`);
      },
      error: (err: unknown) => {
        this.terminarAccion();
        if (this.elegida() !== id) return;
        const campos = erroresDeCampos(err);
        this.errorMotivo.set(campos.length > 0 ? campos.join(' ') : mensajeDe(err, MENSAJE_ACCION));
      },
    });
  }

  protected pedirAnular(): void {
    if (this.enVueloAccion()) return;
    this.errorAccion.set(null);
    this.errorAnular.set(null);
    this.confirmandoAnular.set(true);
  }

  protected cerrarAnular(): void {
    this.confirmandoAnular.set(false);
    this.errorAnular.set(null);
  }

  /**
   * «Anular» (CU-08): manda el motivo solo si se pidió. Los rechazos quedan en el diálogo; al terminar se vuelve a leer la
   * lista, como las acciones de la bandeja, y se abre el detalle de la nota nueva, desde donde se reimprime.
   */
  protected anular(motivo: string | null): void {
    const venta = this.detalle();
    if (!venta || this.enVueloAccion()) return;
    const id = venta.ventaId;
    this.enVueloAccion.set(true);
    this.errorAnular.set(null);
    this.api.anular(id, motivo).subscribe({
      next: (nota) => {
        this.terminarAccion();
        this.confirmandoAnular.set(false);
        const como = nota.fiscal ? 'la nota de crédito' : 'la anulación';
        this.elegida.set(nota.ventaId);
        this.detalle.set(null);
        this.imprimiendo.set(false);
        this.alTerminarAccion(nota.ventaId, `Se anuló la venta N.º ${venta.numero} con ${como} N.º ${nota.numero}.`);
      },
      error: (err: unknown) => {
        this.terminarAccion();
        if (this.elegida() !== id) return;
        const campos = erroresDeCampos(err);
        this.errorAnular.set(campos.length > 0 ? campos.join(' ') : mensajeDe(err, MENSAJE_ACCION));
      },
    });
  }

  private terminarAccion(): void {
    this.enVueloAccion.set(false);
    this.reintentando.set(false);
  }

  /** Después de cada acción se vuelven a leer la lista (misma página y filtros) y el detalle, si sigue elegido. */
  private alTerminarAccion(id: number, resultado: string): void {
    this.resultadoAccion.set(resultado);
    this.cargar(this.pagina()?.page ?? 0);
    if (this.elegida() === id) this.leerDetalle(id);
  }
}
