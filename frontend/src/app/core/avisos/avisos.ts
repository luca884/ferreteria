import { Injectable } from '@angular/core';

/**
 * Aviso de éxito que una pantalla deja para la siguiente (por ejemplo, el formulario de artículo al guardar y
 * volver al listado). Se entrega una sola vez.
 *
 * La pantalla de destino lo toma al crearse (inicializador de campo o constructor): el shell toma lo que quede al
 * terminar la navegación y lo muestra arriba del contenido. Tomarlo en `ngOnInit` llega tarde y lo muestra el shell.
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
