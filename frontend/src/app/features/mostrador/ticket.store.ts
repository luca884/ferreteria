import { Injectable, InjectionToken, computed, effect, inject, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { UNIDADES } from '../articulos/articulo.models';
import { ArticuloMostrador } from './mostrador.models';
import { ConfirmarVentaRequest } from './venta.models';
import {
  RenglonTicket,
  Ticket,
  actualizarPrecios,
  agregar,
  aplicarDescuentoGlobal,
  aplicarDescuentoRenglon,
  cambiarCantidad,
  descontarVendido,
  marcarProblemas,
  moverElegido,
  quitar,
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

  /** Tras cobrar o vaciar: ticket vacío y sin clave (el próximo primer renglón genera una nueva). */
  vaciar(): void {
    this._ticket.set(ticketVacio(''));
  }
}

function claveDeStorage(auth: AuthService): string | null {
  const s = auth.sesion();
  if (!s || s.ferreteriaId == null || s.userId == null) return null;
  return `ticket:${s.ferreteriaId}:${s.userId}`;
}

function descuentoGuardadoValido(bp: unknown): boolean {
  return Number.isInteger(bp) && (bp as number) >= 0 && (bp as number) <= 9999;
}

function esRenglon(r: unknown): r is RenglonTicket {
  if (typeof r !== 'object' || r === null) return false;
  const x = r as Record<string, unknown>;
  return (
    typeof x['articuloId'] === 'number' &&
    typeof x['codigoInterno'] === 'string' &&
    typeof x['descripcion'] === 'string' &&
    typeof x['unidad'] === 'string' &&
    (UNIDADES as readonly string[]).includes(x['unidad']) &&
    Number.isSafeInteger(x['precioCentavos']) &&
    Number.isSafeInteger(x['cantidadMilesimas']) &&
    (x['cantidadMilesimas'] as number) > 0 &&
    typeof x['stockActual'] === 'number' &&
    typeof x['stockMinimo'] === 'number' &&
    (x['descuentoBp'] === undefined || descuentoGuardadoValido(x['descuentoBp'])) &&
    (x['problema'] === undefined || typeof x['problema'] === 'string')
  );
}

function leerTicket(key: string): Ticket | null {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return null;
    const t: unknown = JSON.parse(raw);
    if (typeof t !== 'object' || t === null) return null;
    const x = t as Record<string, unknown>;
    if (x['v'] !== 1 || typeof x['clave'] !== 'string' || x['clave'] === '') return null;
    if (!Array.isArray(x['renglones']) || !x['renglones'].every(esRenglon)) return null;
    const n = x['renglones'].length;
    const e = x['elegido'];
    if (e !== null && !(Number.isInteger(e) && (e as number) >= 0 && (e as number) < n)) return null;
    const global = x['descuentoGlobalBp'];
    if (global !== undefined && !descuentoGuardadoValido(global)) return null;
    return {
      v: 1,
      clave: x['clave'],
      renglones: x['renglones'] as RenglonTicket[],
      elegido: e as number | null,
      ...(global ? { descuentoGlobalBp: global as number } : {}),
    };
  } catch {
    return null;
  }
}

// localStorage puede fallar (modo privado, storage bloqueado): el ticket sigue en memoria sin persistir.
function escribir(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* sin persistencia */
  }
}

function borrar(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    /* sin persistencia */
  }
}
