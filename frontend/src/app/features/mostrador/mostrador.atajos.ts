/**
 * Mapa único de atajos del mostrador. El manejador de teclas de `Mostrador` y la botonera leen de acá:
 * si Chrome no deja anular una tecla F, se cambia solo este archivo. F7, F8 y F9 se suman en los cortes 2 a 4.
 */
export type AccionMostrador = 'buscar' | 'cantidad' | 'cobrar' | 'quitar' | 'cerrar';

export interface Atajo {
  tecla: string;
  accion: AccionMostrador;
  etiqueta: string;
  enBotonera: boolean;
}

export const ATAJOS: readonly Atajo[] = [
  { tecla: 'F2', accion: 'buscar', etiqueta: 'Buscar', enBotonera: true },
  { tecla: 'F3', accion: 'cantidad', etiqueta: 'Cantidad', enBotonera: true },
  { tecla: 'F4', accion: 'cobrar', etiqueta: 'Cobrar', enBotonera: true },
  { tecla: 'F10', accion: 'quitar', etiqueta: 'Quitar', enBotonera: true },
  { tecla: 'Escape', accion: 'cerrar', etiqueta: 'Esc', enBotonera: false },
];

/** `KeyboardEvent.key` a acción, o null si la tecla no tiene atajo. */
export function accionDeTecla(key: string): AccionMostrador | null {
  return ATAJOS.find((a) => a.tecla === key)?.accion ?? null;
}

export function atajosDeBotonera(): readonly Atajo[] {
  return ATAJOS.filter((a) => a.enBotonera);
}
