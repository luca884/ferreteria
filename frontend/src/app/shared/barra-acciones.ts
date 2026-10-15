import { Component } from '@angular/core';

/**
 * Barra superior de una pantalla: título y acciones (teclas F, botones) que pone la pantalla adentro.
 * Cada pantalla la usa primera en su plantilla:
 *
 *   <app-barra-acciones>
 *     <h1>Artículos</h1>
 *     <button type="button" class="accion-barra primario">Nuevo</button>
 *   </app-barra-acciones>
 *
 * Los estilos están en styles.css («Barra superior de cada pantalla») porque alcanzan al contenido proyectado.
 */
@Component({
  selector: 'app-barra-acciones',
  template: `<ng-content />`,
})
export class BarraAcciones {}
