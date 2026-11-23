import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { IconoMenu } from './menu';

/** Ícono de una opción del menú lateral (16×16, trazo). Decorativo: el rótulo va al lado. */
@Component({
  selector: 'app-icono-menu',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg viewBox="0 0 16 16" aria-hidden="true">
      @switch (nombre()) {
        @case ('mostrador') {
          <path d="M2.5 7h11v6.5h-11zM4.5 7V3.5h7V7M6 10.2h4" />
        }
        @case ('presupuestos') {
          <path d="M3.5 1.5h7l2.5 2.5v10.5h-9.5zM10.5 1.5V4H13M5.5 7h5M5.5 9.5h5M5.5 12h3" />
        }
        @case ('remitos') {
          <path d="M1 4h8v7H1zM9 6.5h3l2.5 2.5v2H9M4 6.5h2.5M4 8.5h2.5" />
        }
        @case ('clientes') {
          <circle cx="6" cy="5" r="2.5" />
          <path d="M1.5 13.5c0-2.5 2-4 4.5-4s4.5 1.5 4.5 4M11 3.2a2.5 2.5 0 0 1 0 4.6M12.5 9.7c1.2.6 2 1.8 2 3.8" />
        }
        @case ('caja') {
          <path d="M2 5.5h12v8H2zM4 5.5V3h8v2.5M2 9h12M7 9v1.5h2V9" />
        }
        @case ('ventas') {
          <path d="M3.5 1.5h9v13l-1.5-1-1.5 1-1.5-1-1.5 1-1.5-1-1.5 1zM6 5h4M6 8h4" />
        }
        @case ('reportes') {
          <path d="M2 14.5h12M4 12V8M8 12V3.5M12 12V6" />
        }
        @case ('articulos') {
          <path d="M2 5l6-3 6 3v6l-6 3-6-3zM2 5l6 3 6-3M8 8v6" />
        }
        @case ('rubros') {
          <path d="M2 4h5l1.5 2H14v7.5H2z" />
        }
        @case ('stock-bajo-minimo') {
          <path d="M2 5l6-3 6 3v6l-6 3-6-3zM8 6v3.5M8 11.5v.5" />
        }
        @case ('etiquetas') {
          <path d="M2 3.5v9M4.5 3.5v9M6 3.5v9M8.5 3.5v9M11 3.5v9M12.5 3.5v9M14 3.5v9" />
        }
        @case ('controles') {
          <path d="M3 1.5h7l3 3v10H3zM5.5 8.5l1.8 1.8 3.2-3.6" />
        }
        @case ('proveedores') {
          <path d="M1 4h8v7H1zM9 6.5h3l2.5 2.5v2H9" />
          <circle cx="4" cy="12.5" r="1.3" />
          <circle cx="11.5" cy="12.5" r="1.3" />
        }
        @case ('compras') {
          <path d="M1.5 2.5h2l1.6 7.5h7.4l1.5-5.5H4.3" />
          <circle cx="6" cy="12.8" r="1.2" />
          <circle cx="11.5" cy="12.8" r="1.2" />
        }
        @case ('importar') {
          <path d="M4 1.5h5l3 3v10H4zM8 7v5M6 9l2-2 2 2" />
        }
        @case ('actualizar-precios') {
          <path d="M3 13L13 3" />
          <circle cx="4.5" cy="4.5" r="1.5" />
          <circle cx="11.5" cy="11.5" r="1.5" />
        }
        @case ('margen') {
          <path d="M2 4h12M2 8h12M2 12h12" />
          <circle cx="5" cy="4" r="1.5" />
          <circle cx="11" cy="8" r="1.5" />
          <circle cx="7" cy="12" r="1.5" />
        }
        @case ('cotizacion') {
          <circle cx="8" cy="8" r="6" />
          <path
            d="M8 4.5v7M10 6.2c-.4-.6-1.1-.9-2-.9-1.1 0-1.9.5-1.9 1.3 0 1.9 4 .9 4 2.8 0 .8-.8 1.3-2.1 1.3-.9 0-1.7-.3-2.1-1"
          />
        }
        @case ('formas-pago') {
          <path d="M1.5 4h13v8.5h-13zM1.5 7h13M4 10h3" />
        }
        @case ('consumidor-final') {
          <path d="M2 3.5h12v9H2zM5.5 7.5a1.5 1.5 0 1 0 0-.01M4 11c.4-1.2 2.6-1.2 3 0M9.5 7h3M9.5 9h2" />
        }
        @case ('datos-fiscales') {
          <path d="M3.5 1.5h9v13l-1.5-1-1.5 1-1.5-1-1.5 1-1.5-1-1.5 1zM6 5h4M6 7.5h4M6 10h2.5" />
        }
        @case ('facturacion') {
          <path d="M8.5 14.5H3V1.5h7l3 3v3.5M10 1.5v3h3M5.5 6h4M5.5 8.5h2.5" />
          <circle cx="12" cy="11" r="2" />
          <path d="M11 12.8l-.5 2.2 1.5-.8 1.5.8-.5-2.2" />
        }
        @case ('menu') {
          <path d="M2.5 4h11M2.5 8h11M2.5 12h11" />
        }
        @case ('usuarios') {
          <circle cx="8" cy="5" r="2.5" />
          <path d="M3 13.5c0-2.6 2.2-4.2 5-4.2s5 1.6 5 4.2" />
        }
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      flex: 0 0 auto;
    }
    svg {
      width: 18px;
      height: 18px;
      fill: none;
      stroke: currentColor;
      stroke-width: 1.5;
    }
  `,
})
export class IconoDelMenu {
  readonly nombre = input.required<IconoMenu>();
}
