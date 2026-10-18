/**
 * Mapa único de atajos del mostrador. El manejador de teclas de `Mostrador` y la botonera leen de acá:
 * si Chrome no deja anular una tecla F, se cambia solo este archivo. F9 se suma en el corte del cliente.
 */
export type AccionMostrador =
  | 'buscar'
  | 'cantidad'
  | 'descuento'
  | 'descuentoTotal'
  | 'cobrar'
  | 'presupuesto'
  | 'quitar'
  | 'vaciar'
  | 'cerrar';

export interface Atajo {
  /** `null`: la acción existe pero ninguna tecla la dispara (Vaciar, para no vaciar por error: R-030). */
  tecla: string | null;
  accion: AccionMostrador;
  etiqueta: string;
  enBotonera: boolean;
}

export const ATAJOS: readonly Atajo[] = [
  { tecla: 'F2', accion: 'buscar', etiqueta: 'Buscar', enBotonera: true },
  { tecla: 'F3', accion: 'cantidad', etiqueta: 'Cantidad', enBotonera: true },
  { tecla: 'F7', accion: 'descuento', etiqueta: 'Descuento', enBotonera: true },
  { tecla: 'F6', accion: 'descuentoTotal', etiqueta: 'Dto. total', enBotonera: true },
  { tecla: 'F4', accion: 'cobrar', etiqueta: 'Cobrar', enBotonera: true },
  { tecla: 'F8', accion: 'presupuesto', etiqueta: 'Presupuesto', enBotonera: true },
  { tecla: 'F10', accion: 'quitar', etiqueta: 'Quitar', enBotonera: true },
  { tecla: null, accion: 'vaciar', etiqueta: 'Vaciar', enBotonera: true },
  { tecla: 'Escape', accion: 'cerrar', etiqueta: 'Esc', enBotonera: false },
];

/** `KeyboardEvent.key` a acción, o null si la tecla no tiene atajo. */
export function accionDeTecla(key: string): AccionMostrador | null {
  return ATAJOS.find((a) => a.tecla !== null && a.tecla === key)?.accion ?? null;
}

export function atajosDeBotonera(): readonly Atajo[] {
  return ATAJOS.filter((a) => a.enBotonera);
}
