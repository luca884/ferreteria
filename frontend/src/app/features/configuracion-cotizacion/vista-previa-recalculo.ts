import { Component, computed, input, output } from '@angular/core';
import { VistaPreviaRecalculo } from '../../core/cotizacion/cotizacion.models';
import { formatearCotizacion, formatearMonto } from '../../core/formato/monto';

/**
 * Vista previa de «Recalcular ahora» (presentacional): cómo quedaría cada artículo en dólares con el dólar nuevo, el
 * aviso si supera el de la ferretería, y los botones para aplicar o cancelar. No habla con el servidor.
 */
@Component({
  selector: 'app-vista-previa-recalculo',
  templateUrl: './vista-previa-recalculo.html',
  styleUrl: './vista-previa-recalculo.css',
})
export class VistaPreviaRecalculoComponent {
  readonly vista = input.required<VistaPreviaRecalculo>();
  readonly aplicando = input(false);
  readonly aplicar = output<void>();
  readonly cancelar = output<void>();

  protected readonly monto = formatearMonto;

  protected readonly resumen = computed(() => {
    const v = this.vista();
    if (v.articulos.length === 0) return 'No hay artículos cargados en dólares.';
    const total = v.articulos.length === 1 ? 'del único artículo' : `de los ${v.articulos.length} artículos`;
    const cambian = v.articulosQueCambian === 1 ? '1' : `${v.articulosQueCambian}`;
    return `Con el dólar a ${formatearCotizacion(v.dolar)} cambia el precio de ${cambian} ${total} en dólares.`;
  });

  protected diferencia(valor: number): string {
    if (valor === 0) return '—';
    return valor > 0 ? `+${formatearMonto(valor)}` : formatearMonto(valor);
  }
}
