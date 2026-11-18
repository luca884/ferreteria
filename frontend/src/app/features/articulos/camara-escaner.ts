import { Injectable } from '@angular/core';

/** Por qué no se pudo usar la cámara: el permiso negado, que no hay cámara, o cualquier otra cosa. */
export type CausaFalla = 'denegada' | 'sin-camara' | 'otra';

/** Una cámara abierta: el video para mostrar, el lector de códigos y la linterna. `detener()` la apaga del todo. */
export interface SesionCamara {
  readonly stream: MediaStream;
  /** La pista de video acepta la linterna (`torch`): sólo algunos celulares. */
  readonly tieneLinterna: boolean;
  /** El primer código que ve el lector en el video, o null si no hay ninguno (o el video todavía no tiene imagen). */
  leer(video: HTMLVideoElement): Promise<string | null>;
  linterna(encendida: boolean): Promise<void>;
  /** Apaga todas las pistas: el celular suelta la cámara y se va el aviso de «cámara en uso». */
  detener(): void;
}

/** Los formatos de un artículo de ferretería; se piden sólo los que el navegador soporta. */
const FORMATOS = ['ean_13', 'ean_8', 'upc_a', 'upc_e', 'code_128', 'code_39'];

/** Lo que usamos de la API nativa `BarcodeDetector` (no está en los tipos de TypeScript). */
interface Detector {
  detect(fuente: HTMLVideoElement): Promise<{ rawValue: string }[]>;
}
interface DetectorNativo {
  new (opciones?: { formats?: string[] }): Detector;
  getSupportedFormats?: () => Promise<string[]>;
}
const detectorNativo = (): DetectorNativo | undefined =>
  (globalThis as unknown as { BarcodeDetector?: DetectorNativo }).BarcodeDetector;

/** HAVE_CURRENT_DATA: el video ya tiene un cuadro para leer. */
const CON_IMAGEN = 2;

/** Distingue la falla de `getUserMedia` por el nombre del error (los que define el estándar). */
export function causaDeFalla(error: unknown): CausaFalla {
  const nombre = typeof error === 'object' && error !== null ? (error as { name?: unknown }).name : undefined;
  if (nombre === 'NotAllowedError' || nombre === 'SecurityError') return 'denegada';
  if (nombre === 'NotFoundError' || nombre === 'OverconstrainedError') return 'sin-camara';
  return 'otra';
}

/**
 * movil-sectores T7: la cámara y el lector nativo de códigos de barras detrás de un servicio, para que Vitest pueda
 * simularlos. Regla de Luca (L18): un permiso negado NO se recuerda. Acá no se guarda ni se lee nada del permiso
 * (ni `localStorage` ni `navigator.permissions`): cada `abrir()` vuelve a llamar a `getUserMedia` y el navegador decide.
 */
@Injectable({ providedIn: 'root' })
export class CamaraEscaner {
  /** Hay lector nativo y cámara en este navegador. Sin alguno de los dos no se ofrece el escáner (S1: se escribe a mano). */
  disponible(): boolean {
    return detectorNativo() !== undefined && typeof navigator.mediaDevices?.getUserMedia === 'function';
  }

  /** Pide la cámara trasera (cada vez) y arma el lector. Rechaza con el error del navegador si no se pudo. */
  async abrir(): Promise<SesionCamara> {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } },
    });
    const detener = () => stream.getTracks().forEach((pista) => pista.stop());
    try {
      const Nativo = detectorNativo()!;
      const soportados = await Nativo.getSupportedFormats?.();
      const formats = soportados ? FORMATOS.filter((formato) => soportados.includes(formato)) : FORMATOS;
      const detector = new Nativo({ formats });
      const pista = stream.getVideoTracks()[0];
      const capacidades = (pista?.getCapabilities?.() ?? {}) as { torch?: boolean };
      return {
        stream,
        tieneLinterna: capacidades.torch === true,
        leer: async (video) => {
          if (video.readyState < CON_IMAGEN) return null;
          const codigos = await detector.detect(video);
          return codigos[0]?.rawValue ?? null;
        },
        linterna: (encendida) =>
          pista.applyConstraints({ advanced: [{ torch: encendida } as MediaTrackConstraintSet] }),
        detener,
      };
    } catch (error) {
      detener();
      throw error;
    }
  }
}
