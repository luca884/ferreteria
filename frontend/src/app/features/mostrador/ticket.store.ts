import { Injectable, InjectionToken, computed, effect, inject, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { ArticuloMostrador } from './mostrador.models';
import { borrar, claveDeStorage, escribir, leerTicket } from './ticket.storage';
import { ConfirmarVentaRequest } from './venta.models';
import {
  Ticket,
  actualizarPrecios,
  agregar,
  aplicarDescuentoGlobal,
  aplicarDescuentoRenglon,
  cambiarCantidad,
  descontarVendido,
  elegirCliente,
  marcarProblemas,
  moverElegido,
  quitar,
  quitarCliente,
  soltarPresupuesto,
  soltarRemito,
  ticketVacio,
  totalDelTicket,
} from './ticket';

/** Genera la clave de idempotencia de una venta. En los tests se reemplaza por una determinista. */
export const GENERAR_CLAVE = new InjectionToken<() => string>('GENERAR_CLAVE', {
  providedIn: 'root',
  factory: () => () => crypto.randomUUID(),
});

/**
 * Estado del ticket en curso con persistencia en localStorage (`ticket:{ferreteriaId}:{usuarioId}`), para
 * que sobreviva a un F5. Se provee en `Mostrador`. La clave de idempotencia nace con el primer renglón, se
 * conserva en los reintentos y se renueva solo al cobrar o vaciar.
 */
@Injectable()
export class TicketStore {
  private readonly generarClave = inject(GENERAR_CLAVE);
  private readonly storageKey = claveDeStorage(inject(AuthService));

  private readonly _ticket = signal<Ticket>(
    (this.storageKey ? leerTicket(this.storageKey) : null) ?? ticketVacio(''),
  );

  readonly ticket = this._ticket.asReadonly();
  readonly total = computed(() => totalDelTicket(this._ticket()));
  readonly elegido = computed(() => this._ticket().elegido);

  constructor() {
    effect(() => {
      const t = this._ticket();
      if (!this.storageKey) return;
      if (t.renglones.length === 0) borrar(this.storageKey);
      else escribir(this.storageKey, JSON.stringify(t));
    });
  }

  agregar(a: ArticuloMostrador): void {
    this._ticket.update((t) => agregar(t.clave ? t : { ...t, clave: this.generarClave() }, a));
  }

  /** Devuelve el mensaje de error, o null si cambió la cantidad. */
  cambiarCantidad(indice: number, milesimas: number): string | null {
    const r = cambiarCantidad(this._ticket(), indice, milesimas);
    if (r.error !== undefined || !r.ticket) return r.error ?? 'No se pudo cambiar la cantidad.';
    this._ticket.set(r.ticket);
    return null;
  }

  /** Descuento propio del renglón en centésimas de punto (0 lo saca). Devuelve el error, o null si lo aplicó. */
  aplicarDescuentoRenglon(indice: number, bp: number): string | null {
    const r = aplicarDescuentoRenglon(this._ticket(), indice, bp);
    if (r.error !== undefined || !r.ticket) return r.error ?? 'No se pudo aplicar el descuento.';
    this._ticket.set(r.ticket);
    return null;
  }

  /** Descuento global del ticket en centésimas de punto (0 lo saca). Devuelve el error, o null si lo aplicó. */
  aplicarDescuentoGlobal(bp: number): string | null {
    const r = aplicarDescuentoGlobal(this._ticket(), bp);
    if (r.error !== undefined || !r.ticket) return r.error ?? 'No se pudo aplicar el descuento.';
    this._ticket.set(r.ticket);
    return null;
  }

  quitar(indice: number): void {
    this._ticket.update((t) => quitar(t, indice));
  }

  moverElegido(delta: -1 | 1): void {
    this._ticket.update((t) => moverElegido(t, delta));
  }

  actualizarPrecios(cambios: readonly { articuloId: number; precioActual: number }[]): void {
    this._ticket.update((t) => actualizarPrecios(t, cambios));
  }

  marcarProblemas(problemas: readonly { articuloId: number; mensaje: string }[]): void {
    this._ticket.update((t) => marcarProblemas(t, problemas));
  }

  /** Tras cobrar: saca lo enviado; lo agregado durante el POST queda con una clave nueva. */
  descontarVendido(pedido: ConfirmarVentaRequest): void {
    this._ticket.update((t) => descontarVendido(t, pedido, this.generarClave));
  }

  /** El ticket sigue como una venta común: el presupuesto de origen ya no se manda al cobrar ni cambia. */
  soltarPresupuesto(): void {
    this._ticket.update(soltarPresupuesto);
  }

  /** El ticket sigue como una venta común: el remito de origen ya no se manda al cobrar ni cambia. */
  soltarRemito(): void {
    this._ticket.update(soltarRemito);
  }

  /** F9: el cliente de la venta, del presupuesto o del remito. Reemplaza al anterior; sirve con el ticket vacío. */
  elegirCliente(cliente: { id: number; razonSocial: string }): void {
    this._ticket.update((t) => elegirCliente(t, cliente));
  }

  /** Vuelve a Consumidor Final. */
  quitarCliente(): void {
    this._ticket.update(quitarCliente);
  }

  /** Tras cobrar o vaciar: ticket vacío y sin clave (el próximo primer renglón genera una nueva). */
  vaciar(): void {
    this._ticket.set(ticketVacio(''));
  }
}
