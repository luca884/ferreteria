import { DetectorLector } from './detector-lector';

/** Teclas que no escriben nada: un lector las manda para las mayúsculas y no cortan la ráfaga. */
const MODIFICADORES = ['Shift', 'CapsLock', 'AltGraph'];

/**
 * Para los diálogos: detecta si un Enter cierra una ráfaga del lector de código de barras. Así un escaneo
 * con el diálogo abierto no confirma nada. Reusa el `DetectorLector` de la caja de búsqueda.
 */
export class GuardaLector {
  private readonly detector = new DetectorLector();

  /** Registra la tecla; devuelve true si es el Enter final de un escaneo. */
  alTeclear(evento: KeyboardEvent): boolean {
    if (evento.isComposing) return false;
    if (evento.key === 'Enter') return this.detector.alEnter(Date.now()) !== null;
    if (MODIFICADORES.includes(evento.key)) return false;
    const esCaracter =
      evento.key.length === 1 && !evento.ctrlKey && !evento.metaKey && !evento.altKey;
    if (esCaracter) this.detector.tecla(evento.key, Date.now());
    else this.detector.reiniciar();
    return false;
  }
}

export const AVISO_ESCANEO_CON_DIALOGO = 'Cerrá el diálogo antes de escanear.';
