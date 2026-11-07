import { Component, computed, input } from '@angular/core';

export type MonedaDelCampo = 'ARS' | 'USD';

/**
 * Envoltorio de un campo de plata (arreglos-compras, S6): el signo de la moneda («$» o «US$») va fijo y en gris dentro
 * del recuadro, a la izquierda, y el `appImporte` de adentro muestra siempre dos decimales. Sin moneda (un valor que a
 * veces es porcentaje) no hay signo y el campo sigue como un importe común. El signo es solo visual: el rótulo del
 * campo ya dice qué se escribe.
 *
 * Uso: `<app-campo-moneda [moneda]="'USD'"><input appImporte … /></app-campo-moneda>`.
 */
@Component({
  selector: 'app-campo-moneda',
  host: {
    '[class.con-signo]': 'signo() !== null',
    '[class.en-dolares]': "moneda() === 'USD'",
  },
  template: `
    @if (signo(); as texto) {
      <span class="signo" aria-hidden="true">{{ texto }}</span>
    }
    <ng-content />
  `,
  styles: `
    :host {
      position: relative;
      display: inline-flex;
      align-items: center;
      max-width: 100%;
    }
    .signo {
      position: absolute;
      left: 10px;
      color: var(--texto-tenue);
      pointer-events: none;
    }
  `,
})
export class CampoMoneda {
  readonly moneda = input<MonedaDelCampo | null>('ARS');
  readonly signo = computed(() => {
    const moneda = this.moneda();
    return moneda === null ? null : moneda === 'USD' ? 'US$' : '$';
  });
}
