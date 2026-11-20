import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, Injector, OnInit, afterNextRender, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription, forkJoin } from 'rxjs';
import { erroresDeCampos, esOcupado, mensajeDe } from '../../../core/api/api-error';
import { AuthService } from '../../../core/auth/auth.service';
import { MontoPipe, formatearMonto } from '../../../core/formato/monto';
import { BarraAcciones } from '../../../shared/barra-acciones';
import { esCelular } from '../../../shared/es-celular';
import { fechaHoraLocal } from '../../caja/caja.models';
import { Cliente, documentoLegible } from '../cliente.models';
import { ClientesService } from '../clientes.service';
import { AjusteCobrado, TipoAjuste, textoDeSaldo } from './ajuste.models';
import { PendientesCuenta, Recibo, ReciboCobrado, clavePartida, fechaCompletaLocal } from './cobranza.models';
import {
  CobranzaService,
  GENERAR_CLAVE_COBRO,
  esTransitorio,
  invalidaDe,
  rechazoDe,
} from './cobranza.service';
import { DialogoAjuste, PedidoAjuste } from './dialogo-ajuste';
import { DialogoAnulacion } from './dialogo-anulacion';
import { DialogoCobranza, PedidoCobranza } from './dialogo-cobranza';
import { LineaExtracto } from './extracto.models';
import { MovimientosCuenta } from './movimientos-cuenta';
import { ReciboImprimible } from './recibo-imprimible';

type Estado = 'cargando' | 'listo' | 'error' | 'sinPermiso';

const MENSAJE_SIN_PERMISO = 'Solo el Dueño ve y cobra la cuenta corriente.';
const MENSAJE_CARGA = 'No se pudo cargar la cuenta del cliente.';
const AVISO_DEUDAS = 'Las deudas cambiaron: revisá la imputación y volvé a confirmar.';
const MENSAJE_RED =
  'No se pudo registrar el cobro: no hay conexión o el servidor no respondió. Reintentá: no se cobra dos veces.';
const MENSAJE_RED_AJUSTE =
  'No se pudo registrar el ajuste: no hay conexión o el servidor no respondió. Reintentá: no se carga dos veces.';
const MENSAJE_RECIBO = 'No se pudo traer el recibo. Probá de nuevo.';
const MENSAJE_OCUPADO = 'El sistema está ocupado, reintentá.';
const MENSAJE_RED_ANULACION =
  'No se pudo anular: no hay conexión o el servidor no respondió. Reintentá: si ya se anuló, vas a ver el aviso.';

/** El resultado de una anulación que queda a la vista, con acceso al recibo: se anuló, o ya estaba anulado. */
interface AvisoAnulacion {
  tipo: 'exito' | 'advertencia';
  texto: string;
  reciboId: number;
}

/**
 * Cuenta corriente de un cliente (`clientes/:id/cuenta`): sus pendientes en orden FIFO (ventas y Recargos), el saldo y la
 * acción «Cobrar» (F4), también para el adelanto de un cliente sin deuda, más «Recargo» y «Descuento» (CU-12, ajustes
 * manuales del saldo). Solo el Dueño: el Vendedor ve un aviso y no se hace ningún request.
 * Contenedor: habla con los servicios y maneja la clave de idempotencia del cobro (CC-038) y la del ajuste: la misma clave
 * en cada reintento de red, una nueva cuando cambia el contenido (409, 400) o se abre un diálogo nuevo. Mientras un envío
 * quedó sin confirmar el diálogo no deja cambiar el contenido: o se reintenta lo mismo o se empieza de nuevo (clave nueva).
 */
@Component({
  selector: 'app-cuenta-cliente',
  imports: [
    RouterLink,
    BarraAcciones,
    MontoPipe,
    DialogoAjuste,
    DialogoAnulacion,
    DialogoCobranza,
    MovimientosCuenta,
    ReciboImprimible,
  ],
  templateUrl: './cuenta-cliente.html',
  styleUrl: './cuenta-cliente.css',
  host: { '(document:keydown)': 'alTeclear($event)' },
})
export class CuentaCliente implements OnInit {
  private readonly clientes = inject(ClientesService);
  private readonly cobranza = inject(CobranzaService);
  private readonly route = inject(ActivatedRoute);
  private readonly generarClave = inject(GENERAR_CLAVE_COBRO);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  protected readonly auth = inject(AuthService);

  /**
   * En el celular (movil-sectores T12a) «Cobrar», «Recargo» y «Descuento» van en una fila debajo del saldo y no en la barra,
   * donde con «Volver a Clientes» no entraban. Se lee al crearse: no sigue la rotación. Es sólo dónde están los botones: los
   * manejadores, las condiciones y los diálogos son los mismos, y en la tablet y el escritorio el DOM no cambia.
   */
  protected readonly celular = esCelular();
  protected readonly fecha = fechaHoraLocal;
  protected readonly documentoLegible = documentoLegible;
  protected readonly sinPermiso = MENSAJE_SIN_PERMISO;
  /** Identifica cada partida (venta o Recargo) en la tabla de pendientes: el comprobanteId solo no alcanza. */
  protected readonly clavePartida = clavePartida;

  protected readonly estado = signal<Estado>('cargando');
  protected readonly cliente = signal<Cliente | null>(null);
  protected readonly pendientes = signal<PendientesCuenta | null>(null);
  /** Se incrementa cuando algo cambia el libro (un cobro, una anulación): «Movimientos» vuelve a pedir lo que muestra. */
  protected readonly version = signal(0);
  protected readonly errorCarga = signal<string | null>(null);
  /** Si volver a pedir tiene sentido: con un id inválido en la ruta o un cliente que no existe (404) no lo tiene. */
  protected readonly reintentable = signal(true);

  protected readonly dialogoAbierto = signal(false);
  protected readonly cobrando = signal(false);
  protected readonly avisoDialogo = signal<string | null>(null);
  protected readonly errorDialogo = signal<string | null>(null);
  /**
   * El último envío no se pudo confirmar (red o 5xx): el servidor puede haberlo registrado, así que el contenido queda
   * congelado y se reintenta con la misma clave. Se suelta con una respuesta definitiva o al reiniciar.
   */
  protected readonly envioSinConfirmar = signal(false);
  /** El último cobro registrado (o repetido): el aviso y el recibo que se puede reimprimir. */
  protected readonly cobrado = signal<ReciboCobrado | null>(null);
  /** El recibo que se está viendo: el del cobro recién hecho o el que trajo «Reimprimir recibo». */
  protected readonly reciboAbierto = signal<Recibo | null>(null);
  protected readonly errorRecibo = signal<string | null>(null);
  protected readonly trayendoRecibo = signal(false);

  /** El recibo que se está por anular: con él abierto se muestra el diálogo de anulación. */
  protected readonly anulando = signal<Recibo | null>(null);
  protected readonly anulandoEnVuelo = signal(false);
  protected readonly errorAnulacion = signal<string | null>(null);
  /** Cómo terminó la última anulación: se anuló, o el recibo ya estaba anulado. */
  protected readonly avisoAnulacion = signal<AvisoAnulacion | null>(null);

  /** El ajuste que se está cargando: con él abierto se muestra el diálogo de Recargo o de Descuento. */
  protected readonly ajustando = signal<TipoAjuste | null>(null);
  protected readonly ajustandoEnVuelo = signal(false);
  protected readonly avisoAjuste = signal<string | null>(null);
  protected readonly errorAjuste = signal<string | null>(null);
  /** Los errores de un 400 que corresponden a un campo del diálogo (importe, motivo). */
  protected readonly erroresAjuste = signal<Record<string, string>>({});
  /** Como `envioSinConfirmar`, del ajuste: el contenido queda congelado y se reintenta con la misma clave. */
  protected readonly ajusteSinConfirmar = signal(false);
  /** El último ajuste registrado (o repetido): el aviso con el saldo que dejó. */
  protected readonly ajustado = signal<AjusteCobrado | null>(null);

  private clienteId = 0;
  /** La clave del ajuste en curso: nace al abrir el diálogo y cambia solo cuando cambia el contenido. */
  private claveAjuste = '';
  /** La clave del cobro en curso: nace al abrir el diálogo y cambia solo cuando cambia el contenido. */
  private clave = '';
  private lectura: Subscription | undefined;

  /** «$ 1.200,00» lo que debe; «$ 500,00 a favor» si el saldo es negativo. */
  protected readonly textoSaldo = computed(() => textoDeSaldo(this.pendientes()?.saldo ?? 0));

  protected readonly textoAjustado = computed(() => {
    const resultado = this.ajustado();
    if (!resultado) return null;
    const { ajuste, repetida } = resultado;
    const hecho = repetida
      ? `Este ajuste ya estaba registrado: ${ajuste.referencia}`
      : `${ajuste.referencia} registrado`;
    return `${hecho} por ${formatearMonto(ajuste.importe)}. El saldo ahora es ${textoDeSaldo(ajuste.saldo)}.`;
  });

  protected readonly textoCobrado = computed(() => {
    const cobro = this.cobrado();
    if (!cobro) return null;
    const { recibo, repetida } = cobro;
    const base = `${repetida ? 'Este cobro ya estaba registrado' : 'Cobro registrado'}: Recibo N.º ${recibo.numero} por ${formatearMonto(recibo.total)}.`;
    return recibo.aCuenta > 0 ? `${base} Quedó a cuenta ${formatearMonto(recibo.aCuenta)}.` : base;
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.lectura?.unsubscribe());
  }

  ngOnInit(): void {
    if (!this.auth.esDueno()) {
      this.estado.set('sinPermiso');
      return;
    }
    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (!Number.isInteger(id) || id <= 0) {
      this.reintentable.set(false);
      this.errorCarga.set('El cliente no existe.');
      this.estado.set('error');
      return;
    }
    this.clienteId = id;
    this.cargar();
  }

  /** Lee el cliente y sus pendientes; también la usa «Reintentar». */
  protected cargar(): void {
    this.estado.set('cargando');
    this.errorCarga.set(null);
    this.lectura?.unsubscribe();
    this.lectura = forkJoin({
      cliente: this.clientes.obtener(this.clienteId),
      pendientes: this.cobranza.pendientes(this.clienteId),
    }).subscribe({
      next: ({ cliente, pendientes }) => {
        this.cliente.set(cliente);
        this.pendientes.set(pendientes);
        this.estado.set('listo');
      },
      error: (err: unknown) => this.fallarCarga(err),
    });
  }

  /** Vuelve a pedir los pendientes (después de un cobro o de un 409): nunca se actualiza el saldo a mano. */
  private recargarPendientes(alFallar: (err: unknown) => void): void {
    this.lectura?.unsubscribe();
    this.lectura = this.cobranza.pendientes(this.clienteId).subscribe({
      next: (pendientes) => this.pendientes.set(pendientes),
      error: alFallar,
    });
  }

  private fallarCarga(err: unknown): void {
    // «Cliente no encontrado» (inexistente o de otra ferretería) no se arregla pidiendo de nuevo: solo se vuelve a Clientes.
    this.reintentable.set(!(err instanceof HttpErrorResponse && err.status === 404));
    this.errorCarga.set(mensajeDe(err, MENSAJE_CARGA));
    this.estado.set('error');
  }

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key !== 'F4' || !this.auth.esDueno()) return;
    evento.preventDefault();
    this.abrirCobro();
  }

  /** Abre el cobro: también sin deuda (adelanto). Cada apertura es un cobro nuevo, con su propia clave. */
  protected abrirCobro(): void {
    if (!this.libreParaUnDialogo()) return;
    this.clave = this.generarClave();
    this.cobrado.set(null);
    this.ajustado.set(null);
    this.avisoAnulacion.set(null);
    this.errorRecibo.set(null);
    this.avisoDialogo.set(null);
    this.errorDialogo.set(null);
    this.envioSinConfirmar.set(false);
    this.dialogoAbierto.set(true);
  }

  protected cerrarCobro(): void {
    if (this.cobrando()) return;
    this.dialogoAbierto.set(false);
    this.avisoDialogo.set(null);
    this.errorDialogo.set(null);
  }

  /** «Cancelar y empezar de nuevo»: se abandona el envío sin confirmar, con clave nueva, y el contenido vuelve a editarse. */
  protected reiniciarCobro(): void {
    if (this.cobrando()) return;
    this.clave = this.generarClave();
    this.envioSinConfirmar.set(false);
    this.avisoDialogo.set(null);
    this.errorDialogo.set(null);
  }

  protected cobrar(pedido: PedidoCobranza): void {
    if (this.cobrando()) return;
    this.cobrando.set(true);
    this.avisoDialogo.set(null);
    this.errorDialogo.set(null);
    this.cobranza.cobrar({ clave: this.clave, clienteId: this.clienteId, ...pedido }).subscribe({
      next: (cobro) => {
        this.cobrando.set(false);
        this.envioSinConfirmar.set(false);
        this.dialogoAbierto.set(false);
        this.cobrado.set(cobro);
        this.errorRecibo.set(null);
        this.reciboAbierto.set(cobro.recibo);
        this.recargarPendientes((err) => this.fallarCarga(err));
        this.version.update((v) => v + 1);
      },
      error: (err: unknown) => {
        this.cobrando.set(false);
        this.fallarCobro(err);
      },
    });
  }

  /** Abre el Recargo o el Descuento. Cada apertura es un ajuste nuevo, con su propia clave. */
  protected abrirAjuste(tipo: TipoAjuste): void {
    if (!this.libreParaUnDialogo()) return;
    this.claveAjuste = this.generarClave();
    this.cobrado.set(null);
    this.ajustado.set(null);
    this.avisoAnulacion.set(null);
    this.errorRecibo.set(null);
    this.avisoAjuste.set(null);
    this.errorAjuste.set(null);
    this.erroresAjuste.set({});
    this.ajusteSinConfirmar.set(false);
    this.ajustando.set(tipo);
  }

  protected cerrarAjuste(): void {
    if (this.ajustandoEnVuelo()) return;
    this.ajustando.set(null);
    this.avisoAjuste.set(null);
    this.errorAjuste.set(null);
    this.erroresAjuste.set({});
  }

  /** «Cancelar y empezar de nuevo»: se abandona el envío sin confirmar, con clave nueva, y el contenido vuelve a editarse. */
  protected reiniciarAjuste(): void {
    if (this.ajustandoEnVuelo()) return;
    this.claveAjuste = this.generarClave();
    this.ajusteSinConfirmar.set(false);
    this.avisoAjuste.set(null);
    this.errorAjuste.set(null);
    this.erroresAjuste.set({});
  }

  protected ajustar(pedido: PedidoAjuste): void {
    if (this.ajustandoEnVuelo()) return;
    this.ajustandoEnVuelo.set(true);
    this.avisoAjuste.set(null);
    this.errorAjuste.set(null);
    this.erroresAjuste.set({});
    this.cobranza.ajustar(this.clienteId, { clave: this.claveAjuste, ...pedido }).subscribe({
      next: (resultado) => {
        this.ajustandoEnVuelo.set(false);
        this.ajusteSinConfirmar.set(false);
        this.ajustando.set(null);
        this.ajustado.set(resultado);
        this.refrescarCuenta();
      },
      error: (err: unknown) => {
        this.ajustandoEnVuelo.set(false);
        this.fallarAjuste(err);
      },
    });
  }

  /** Ningún diálogo abierto ni un recibo en camino: recién ahí F4, «Cobrar» y «Anular» pueden abrir otro. */
  private libreParaUnDialogo(): boolean {
    return (
      this.estado() === 'listo' &&
      !this.dialogoAbierto() &&
      !this.ajustando() &&
      !this.reciboAbierto() &&
      !this.anulando() &&
      !this.trayendoRecibo()
    );
  }

  /** Reimprimir: pide el recibo al servidor (GET), que dice lo mismo que el cobro que lo creó. */
  protected reimprimir(): void {
    const cobro = this.cobrado();
    if (cobro) this.verRecibo(cobro.recibo.id);
  }

  /** «Ver recibo» del aviso de una anulación: el recibo ya figura ANULADO. */
  protected verReciboAnulado(): void {
    const aviso = this.avisoAnulacion();
    if (aviso) this.verRecibo(aviso.reciboId);
  }

  /** Trae el recibo (GET) y lo abre para ver e imprimir; también el de una fila ANULACION, que es el mismo recibo. */
  protected verRecibo(reciboId: number): void {
    if (this.trayendoRecibo() || this.reciboAbierto() || this.anulando() || this.dialogoAbierto() || this.ajustando()) return;
    this.trayendoRecibo.set(true);
    this.errorRecibo.set(null);
    this.cobranza.recibo(reciboId).subscribe({
      next: (recibo) => {
        this.trayendoRecibo.set(false);
        this.reciboAbierto.set(recibo);
      },
      error: () => {
        this.trayendoRecibo.set(false);
        this.errorRecibo.set(MENSAJE_RECIBO);
      },
    });
  }

  /**
   * «Anular» en una fila del extracto: trae el recibo para mostrar qué se anula y abre el diálogo. El foco vuelve solo al
   * botón de origen al cerrarse (`appTrampaFoco`); si se anuló, ese botón ya no existe y el foco pasa a «Ver recibo».
   */
  protected anular(linea: LineaExtracto): void {
    const reciboId = linea.reciboId;
    if (reciboId === null || !this.libreParaUnDialogo()) return;
    this.trayendoRecibo.set(true);
    this.errorRecibo.set(null);
    this.cobranza.recibo(reciboId).subscribe({
      next: (recibo) => {
        this.trayendoRecibo.set(false);
        if (recibo.estado === 'ANULADO') {
          // La fila estaba desactualizada: no hay nada que anular, se muestra lo que pasó y se vuelve a pedir lo que se ve.
          this.avisoAnulacion.set(this.avisoYaAnulado(recibo, null));
          this.refrescarCuenta();
          this.enfocarVerRecibo();
          return;
        }
        this.cobrado.set(null);
        this.avisoAnulacion.set(null);
        this.errorAnulacion.set(null);
        this.anulando.set(recibo);
      },
      error: () => {
        this.trayendoRecibo.set(false);
        this.errorRecibo.set(MENSAJE_RECIBO);
      },
    });
  }

  protected cerrarAnulacion(): void {
    if (this.anulandoEnVuelo()) return;
    this.anulando.set(null);
    this.errorAnulacion.set(null);
  }

  protected confirmarAnulacion(motivo: string): void {
    const recibo = this.anulando();
    if (!recibo || this.anulandoEnVuelo()) return;
    this.anulandoEnVuelo.set(true);
    this.errorAnulacion.set(null);
    this.cobranza.anular(recibo.id, motivo).subscribe({
      next: (anulado) => {
        this.anulandoEnVuelo.set(false);
        this.anulando.set(null);
        this.avisoAnulacion.set({
          tipo: 'exito',
          texto: `Recibo N.º ${anulado.numero} anulado. Los comprobantes volvieron a quedar pendientes.`,
          reciboId: anulado.id,
        });
        this.refrescarCuenta();
        this.enfocarVerRecibo();
      },
      error: (err: unknown) => {
        this.anulandoEnVuelo.set(false);
        this.fallarAnulacion(err, recibo);
      },
    });
  }

  /**
   * El botón «Anular» de la fila ya no existe (o está por desaparecer al recargar): el resultado se anuncia en el aviso y
   * el foco va a su acción, «Ver recibo», en lugar de caer al body.
   */
  private enfocarVerRecibo(): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('.ver-recibo')?.focus(), {
      injector: this.injector,
    });
  }

  /** Saldo, pendientes y Movimientos cambiaron (un cobro, una anulación, un ajuste): nunca se corrigen a mano, se vuelven a pedir. */
  private refrescarCuenta(): void {
    this.recargarPendientes((err) => this.fallarCarga(err));
    this.version.update((v) => v + 1);
  }

  private avisoYaAnulado(recibo: Pick<Recibo, 'id' | 'anuladoPor' | 'fechaAnulacion'>, mensaje: string | null): AvisoAnulacion {
    const { anuladoPor, fechaAnulacion } = recibo;
    const texto =
      anuladoPor && fechaAnulacion
        ? `Este recibo ya fue anulado por ${anuladoPor} el ${fechaCompletaLocal(fechaAnulacion)}.`
        : (mensaje ?? 'Este recibo ya fue anulado.');
    return { tipo: 'advertencia', texto, reciboId: recibo.id };
  }

  /**
   * Un 409 «ya anulado» es un hecho consumado: se cierra el diálogo y se avisa quién y cuándo. Los demás rechazos (caja
   * cerrada, cliente desactivado, motivo inválido, sistema ocupado, red) dejan el diálogo abierto con el motivo escrito;
   * reintentar es seguro porque el servidor responde 409 si la anulación ya había quedado hecha.
   */
  private fallarAnulacion(err: unknown, recibo: Recibo): void {
    const rechazo = rechazoDe(err);
    if (rechazo?.motivo === 'RECIBO_YA_ANULADO') {
      this.anulando.set(null);
      this.avisoAnulacion.set(
        this.avisoYaAnulado(
          { id: recibo.id, anuladoPor: rechazo.anuladoPor ?? null, fechaAnulacion: rechazo.fechaAnulacion ?? null },
          rechazo.message,
        ),
      );
      this.refrescarCuenta();
      this.enfocarVerRecibo();
      return;
    }
    if (rechazo) {
      this.errorAnulacion.set(rechazo.message);
    } else if (esOcupado(err)) {
      this.errorAnulacion.set(MENSAJE_OCUPADO);
    } else if (invalidaDe(err)) {
      this.errorAnulacion.set(erroresDeCampos(err).join(' ') || mensajeDe(err, 'Revisá el motivo de la anulación.'));
    } else if (esTransitorio(err)) {
      this.errorAnulacion.set(MENSAJE_RED_ANULACION);
    } else {
      this.errorAnulacion.set(mensajeDe(err, 'No se pudo anular el cobro.'));
    }
  }

  protected cerrarRecibo(): void {
    this.reciboAbierto.set(null);
  }

  /**
   * Qué pasa con la clave del ajuste según el error, como en el cobro: una falla de red o un 5xx no dice si quedó
   * registrado, así que se reintenta con la MISMA clave (el servidor devuelve el ajuste si ya estaba) y el contenido queda
   * congelado. Un 409 o un 400 cambian el contenido que hay que mandar, y una clave usada con otro contenido es un 409:
   * clave nueva. Un 400 reparte sus errores: importe y motivo junto a su campo, el resto en el mensaje del diálogo.
   */
  private fallarAjuste(err: unknown): void {
    this.ajusteSinConfirmar.set(false);
    const rechazo = rechazoDe(err);
    if (rechazo?.motivo === 'IMPUTACION_EXCEDE_DEUDA') {
      this.claveAjuste = this.generarClave();
      this.avisoAjuste.set(AVISO_DEUDAS);
      this.recargarPendientes(() =>
        this.errorAjuste.set('No se pudieron actualizar las deudas. Cerrá y volvé a abrir el ajuste.'),
      );
      return;
    }
    if (rechazo?.motivo === 'CLAVE_CON_OTRO_CONTENIDO') {
      this.claveAjuste = this.generarClave();
      this.errorAjuste.set(rechazo.message);
      return;
    }
    if (rechazo) {
      this.errorAjuste.set(rechazo.message);
      return;
    }
    if (err instanceof HttpErrorResponse && err.status === 409) {
      this.claveAjuste = this.generarClave();
      this.errorAjuste.set(mensajeDe(err, 'El ajuste no se pudo registrar.'));
      return;
    }
    if (esOcupado(err)) {
      this.errorAjuste.set(MENSAJE_OCUPADO);
      return;
    }
    const invalida = invalidaDe(err);
    if (invalida) {
      this.claveAjuste = this.generarClave();
      const { importe, motivo, ...otros } = invalida.errors ?? {};
      const propios = { ...(importe ? { importe } : {}), ...(motivo ? { motivo } : {}) };
      this.erroresAjuste.set(propios);
      const general = Object.values(otros).filter((m) => typeof m === 'string' && m.trim() !== '').join(' ');
      this.errorAjuste.set(general || (Object.keys(propios).length > 0 ? null : mensajeDe(err, 'Revisá los datos del ajuste.')));
      return;
    }
    if (esTransitorio(err)) {
      this.ajusteSinConfirmar.set(true);
      this.errorAjuste.set(MENSAJE_RED_AJUSTE);
      return;
    }
    this.errorAjuste.set(mensajeDe(err, 'No se pudo registrar el ajuste.'));
  }

  /**
   * Qué pasa con la clave según el error: una falla de red o un 5xx no dice si el cobro quedó registrado, así que se
   * reintenta con la MISMA clave (el servidor devuelve el recibo si ya estaba). Un 409 o un 400 cambian el contenido que
   * hay que mandar, y una clave usada con otro contenido es un 422: clave nueva.
   */
  private fallarCobro(err: unknown): void {
    this.envioSinConfirmar.set(false);
    const rechazo = rechazoDe(err);
    if (rechazo?.motivo === 'IMPUTACION_EXCEDE_DEUDA') {
      this.clave = this.generarClave();
      this.avisoDialogo.set(AVISO_DEUDAS);
      this.recargarPendientes(() =>
        this.errorDialogo.set('No se pudieron actualizar las deudas. Cerrá y volvé a abrir el cobro.'),
      );
      return;
    }
    if (rechazo?.motivo === 'CLAVE_CON_OTRO_CONTENIDO') {
      this.clave = this.generarClave();
      this.errorDialogo.set(rechazo.message);
      return;
    }
    if (rechazo) {
      this.errorDialogo.set(rechazo.message);
      return;
    }
    if (invalidaDe(err)) {
      this.clave = this.generarClave();
      this.errorDialogo.set(erroresDeCampos(err).join(' ') || mensajeDe(err, 'Revisá los datos del cobro.'));
      return;
    }
    if (esTransitorio(err)) {
      this.envioSinConfirmar.set(true);
      this.errorDialogo.set(MENSAJE_RED);
      return;
    }
    this.errorDialogo.set(mensajeDe(err, 'No se pudo registrar el cobro.'));
  }
}
