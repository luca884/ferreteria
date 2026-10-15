import { Injectable } from '@angular/core';

/**
 * Aviso de éxito que una pantalla deja para la siguiente (por ejemplo, el formulario de artículo al guardar y
 * volver al listado). Se entrega una sola vez.
 */
@Injectable({ providedIn: 'root' })
export class Avisos {
  private pendiente: string | null = null;

  dejar(texto: string): void {
    this.pendiente = texto;
  }

  tomar(): string | null {
    const texto = this.pendiente;
    this.pendiente = null;
    return texto;
  }
}
