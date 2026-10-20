import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription, forkJoin } from 'rxjs';
import { erroresDeCampos, mensajeDe } from '../../../core/api/api-error';
import { AuthService } from '../../../core/auth/auth.service';
import { MontoPipe, formatearMonto } from '../../../core/formato/monto';
import { BarraAcciones } from '../../../shared/barra-acciones';
import { fechaHoraLocal } from '../../caja/caja.models';
import { Cliente, documentoLegible } from '../cliente.models';
import { ClientesService } from '../clientes.service';
import { PendientesCuenta, Recibo, ReciboCobrado } from './cobranza.models';
import { CobranzaService, GENERAR_CLAVE_COBRO, esTransitorio, invalidaDe, rechazoDe } from './cobranza.service';
import { DialogoCobranza, PedidoCobranza } from './dialogo-cobranza';
import { ReciboImprimible } from './recibo-imprimible';

type Estado = 'cargando' | 'listo' | 'error' | 'sinPermiso';

const MENSAJE_SIN_PERMISO = 'Solo el Dueño ve y cobra la cuenta corriente.';
const MENSAJE_CARGA = 'No se pudo cargar la cuenta del cliente.';
const AVISO_DEUDAS = 'Las deudas cambiaron: revisá la imputación y volvé a confirmar.';
const MENSAJE_RED =
  'No se pudo registrar el cobro: no hay conexión o el servidor no respondió. Reintentá: no se cobra dos veces.';

/**
 * Cuenta corriente de un cliente (`clientes/:id/cuenta`): sus pendientes en orden FIFO, el saldo y la acción «Cobrar» (F4),
 * también para el adelanto de un cliente sin deuda. Solo el Dueño: el Vendedor ve un aviso y no se hace ningún request.
 * Contenedor: habla con los servicios y maneja la clave de idempotencia del cobro (CC-038): la misma clave en cada reintento
 * de red, una nueva cuando cambia el contenido (409, 400) o se abre un cobro nuevo. Mientras un envío quedó sin confirmar el
 * diálogo no deja cambiar el contenido: o se reintenta lo mismo o se empieza de nuevo (clave nueva).
 */
@Component({
  selector: 'app-cuenta-cliente',
  imports: [RouterLink, BarraAcciones, MontoPipe, DialogoCobranza, ReciboImprimible],
  templateUrl: './cuenta-cliente.html',
  styleUrl: './cuenta-cliente.css',
  host: { '(document:keydown)': 'alTeclear($event)' },
})
export class CuentaCliente implements OnInit {
  private readonly clientes = inject(ClientesService);
  private readonly cobranza = inject(CobranzaService);
  private readonly route = inject(ActivatedRoute);
  private readonly generarClave = inject(GENERAR_CLAVE_COBRO);
  protected readonly auth = inject(AuthService);

  protected readonly fecha = fechaHoraLocal;
  protected readonly documentoLegible = documentoLegible;
  protected readonly sinPermiso = MENSAJE_SIN_PERMISO;

  protected readonly estado = signal<Estado>('cargando');
  protected readonly cliente = signal<Cliente | null>(null);
  protected readonly pendientes = signal<PendientesCuenta | null>(null);
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

  private clienteId = 0;
  /** La clave del cobro en curso: nace al abrir el diálogo y cambia solo cuando cambia el contenido. */
  private clave = '';
  private lectura: Subscription | undefined;

  /** «$ 1.200,00» lo que debe; «$ 500,00 a favor» si el saldo es negativo. */
  protected readonly textoSaldo = computed(() => {
    const saldo = this.pendientes()?.saldo ?? 0;
    return saldo < 0 ? `${formatearMonto(-saldo)} a favor` : formatearMonto(saldo);
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
    if (this.estado() !== 'listo' || this.dialogoAbierto() || this.reciboAbierto()) return;
    this.clave = this.generarClave();
    this.cobrado.set(null);
    this.errorRecibo.set(null);
    this.avisoDialogo.set(null);
    this.errorDialogo.set(null);
    this.envioSinConfirmar.set(false);
    this.dialogoAbierto.set(true);
  }

  protected cerrarCobro(): void {
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
      },
      error: (err: unknown) => {
        this.cobrando.set(false);
        this.fallarCobro(err);
      },
    });
  }

  /** Reimprimir: pide el recibo al servidor (GET), que dice lo mismo que el cobro que lo creó. */
  protected reimprimir(): void {
    const cobro = this.cobrado();
    if (!cobro || this.trayendoRecibo()) return;
    this.trayendoRecibo.set(true);
    this.errorRecibo.set(null);
    this.cobranza.recibo(cobro.recibo.id).subscribe({
      next: (recibo) => {
        this.trayendoRecibo.set(false);
        this.reciboAbierto.set(recibo);
      },
      error: () => {
        this.trayendoRecibo.set(false);
        this.errorRecibo.set('No se pudo traer el recibo. Probá de nuevo.');
      },
    });
  }

  protected cerrarRecibo(): void {
    this.reciboAbierto.set(null);
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
