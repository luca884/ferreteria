import { AuthService } from '../../core/auth/auth.service';
import { UNIDADES } from '../articulos/articulo.models';
import { CONDICIONES_IVA, CondicionIva } from '../clientes/cliente.models';
import { RenglonTicket, Ticket } from './ticket';

/**
 * El ticket en curso en localStorage (`ticket:{ferreteriaId}:{usuarioId}`). Lo usa el `TicketStore` del mostrador y la
 * pantalla de presupuestos y la de remitos, que dejan ahí el ticket de «Pasar a venta» antes de abrir el mostrador.
 */

export function claveDeStorage(auth: AuthService): string | null {
  const s = auth.sesion();
  if (!s || s.ferreteriaId == null || s.userId == null) return null;
  return `ticket:${s.ferreteriaId}:${s.userId}`;
}

function descuentoGuardadoValido(bp: unknown): boolean {
  return Number.isInteger(bp) && (bp as number) >= 0 && (bp as number) <= 9999;
}

function esPositivo(n: unknown): boolean {
  return Number.isSafeInteger(n) && (n as number) > 0;
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

export function leerTicket(key: string): Ticket | null {
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
    // El presupuesto de origen va completo (id y número) o no va: uno a medias es un ticket roto.
    const presupuestoId = x['presupuestoId'];
    const presupuestoNumero = x['presupuestoNumero'];
    if ((presupuestoId === undefined) !== (presupuestoNumero === undefined)) return null;
    if (presupuestoId !== undefined && !(esPositivo(presupuestoId) && esPositivo(presupuestoNumero))) return null;
    // Igual con el remito de origen, y un ticket sale de un presupuesto o de un remito, no de los dos.
    const remitoId = x['remitoId'];
    const remitoNumero = x['remitoNumero'];
    if ((remitoId === undefined) !== (remitoNumero === undefined)) return null;
    if (remitoId !== undefined && !(esPositivo(remitoId) && esPositivo(remitoNumero))) return null;
    if (remitoId !== undefined && presupuestoId !== undefined) return null;
    // El cliente elegido (CL-005) también va completo (id y nombre) o no va.
    const clienteId = x['clienteId'];
    const clienteNombre = x['clienteNombre'];
    if ((clienteId === undefined) !== (clienteNombre === undefined)) return null;
    if (clienteId !== undefined && !(esPositivo(clienteId) && typeof clienteNombre === 'string' && clienteNombre !== '')) {
      return null;
    }
    // La condición de IVA solo anticipa la letra en el cobro: una rara (o sin cliente) se descarta, el ticket sigue.
    const condicion = x['clienteCondicionIva'];
    const condicionValida =
      clienteId !== undefined && (CONDICIONES_IVA as readonly unknown[]).includes(condicion) ? (condicion as CondicionIva) : null;
    return {
      v: 1,
      clave: x['clave'],
      renglones: x['renglones'] as RenglonTicket[],
      elegido: e as number | null,
      ...(global ? { descuentoGlobalBp: global as number } : {}),
      ...(presupuestoId !== undefined
        ? { presupuestoId: presupuestoId as number, presupuestoNumero: presupuestoNumero as number }
        : {}),
      ...(remitoId !== undefined
        ? { remitoId: remitoId as number, remitoNumero: remitoNumero as number }
        : {}),
      ...(clienteId !== undefined ? { clienteId: clienteId as number, clienteNombre: clienteNombre as string } : {}),
      ...(condicionValida ? { clienteCondicionIva: condicionValida } : {}),
    };
  } catch {
    return null;
  }
}

// localStorage puede fallar (modo privado, storage bloqueado): el ticket sigue en memoria sin persistir.
export function escribir(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* sin persistencia */
  }
}

export function borrar(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    /* sin persistencia */
  }
}

/** El ticket guardado de este usuario (o null si no hay, está roto o no se puede leer). */
export function ticketGuardado(auth: AuthService): Ticket | null {
  const clave = claveDeStorage(auth);
  return clave ? leerTicket(clave) : null;
}

/** Deja el ticket guardado para que el mostrador lo recupere al abrirse. No hace nada sin sesión completa. */
export function guardarTicket(auth: AuthService, ticket: Ticket): void {
  const clave = claveDeStorage(auth);
  if (clave) escribir(clave, JSON.stringify(ticket));
}

