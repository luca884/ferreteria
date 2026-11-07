import { Injectable } from '@angular/core';

/** Éxito (verde) o advertencia (amarillo, como «No tenés acceso a Ventas.» del guard de rol). */
export type TipoAviso = 'exito' | 'advertencia';

export interface Aviso {
  texto: string;
  tipo: TipoAviso;
}

/**
 * Aviso que una pantalla deja para la siguiente (por ejemplo, el formulario de artículo al guardar y volver al
 * listado, o el guard de rol al mandar a alguien a su inicio). Se entrega una sola vez.
 *
 * La pantalla de destino lo toma al crearse (inicializador de campo o constructor): el shell toma lo que quede al
 * terminar la navegación y lo muestra arriba del contenido. Tomarlo en `ngOnInit` llega tarde y lo muestra el shell.
 */
@Injectable({ providedIn: 'root' })
export class Avisos {
  private pendiente: Aviso | null = null;

  dejar(texto: string, tipo: TipoAviso = 'exito'): void {
    this.pendiente = { texto, tipo };
  }

  tomar(): Aviso | null {
    const aviso = this.pendiente;
    this.pendiente = null;
    return aviso;
  }
}
