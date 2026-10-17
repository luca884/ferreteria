import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Cotizacion, LADO_LABELS, LadoCotizacion, PreciosCotizacion, TipoCotizacion } from '../core/cotizacion/cotizacion.models';
import { formatearFechaHora } from '../core/formato/fecha-hora';
import { formatearCotizacion } from '../core/formato/monto';

/**
 * Una línea del bloque: «Oficial venta 1.000,00», con marca si es el lado del dólar de los precios. Esa línea muestra
 * el dólar realmente aplicado a los precios (el vigente), no el último que trajo la fuente.
 */
interface Linea {
  etiqueta: string;
  valor: string;
  dePrecios: boolean;
  /** «14,50»: el dólar nuevo que espera la confirmación del Dueño (solo en la línea de los precios); vacío si no hay. */
  aConfirmar: string;
}

interface TipoVisible {
  codigo: string;
  nombre: string;
  /** Vacío si todavía no hay ningún valor guardado de este tipo. */
  lineas: Linea[];
  /** Fecha y hora del valor («16/10 08:00»). */
  fecha: string;
  /** «16/10 07:00» si ninguna fuente renovó este tipo en más de 1 hora; vacío si está al día. */
  sinRenovarDesde: string;
}

const LADOS: readonly LadoCotizacion[] = ['COMPRA', 'VENTA'];

/**
 * Bloque «Cotización» del menú lateral (presentacional): lo que el contenedor (el shell) le pasa, lo muestra. Lo ven
 * todos los roles; el enlace «Configurar» es solo del Dueño. Siempre muestra la fecha y la hora de cada valor. El
 * aviso amarillo sale únicamente de `desactualizada` (más de 1 hora sin respuesta de las APIs), nunca de la fecha del
 * mercado: un fin de semana con las APIs respondiendo no da un aviso falso.
 */
@Component({
  selector: 'app-cotizacion-lateral',
  imports: [RouterLink],
  templateUrl: './cotizacion-lateral.html',
  styleUrl: './cotizacion-lateral.css',
})
export class CotizacionLateral {
  /** Lo último que leyó el shell; null hasta la primera respuesta. */
  readonly cotizacion = input.required<Cotizacion | null>();
  readonly esDueno = input(false);

  protected readonly tipos = computed<TipoVisible[]>(() => {
    const c = this.cotizacion();
    return c ? c.visibles.map((tipo) => tipoVisible(tipo, c.precios)) : [];
  });

  /** «16/10 08:00»: la última vez que alguna API respondió, que es la cotización que se está usando. */
  protected readonly desdeCuando = computed(() => formatearFechaHora(this.cotizacion()?.ultimaRespuesta));
}

function tipoVisible(tipo: TipoCotizacion, precios: PreciosCotizacion): TipoVisible {
  const importes: Record<LadoCotizacion, number | null> = { COMPRA: tipo.compra, VENTA: tipo.venta };
  const lineas = LADOS.filter((lado) => importes[lado] !== null).map((lado) => {
    const dePrecios = tipo.esDePrecios && lado === precios.lado;
    // La línea de los precios muestra el vigente: con un dólar a confirmar, el crudo engañaría (auditoría H09).
    const valor = dePrecios && precios.valor !== null ? precios.valor : importes[lado];
    return {
      etiqueta: `${tipo.nombre} ${LADO_LABELS[lado]}`,
      valor: formatearCotizacion(valor),
      dePrecios,
      aConfirmar: dePrecios && precios.aConfirmar !== null ? formatearCotizacion(precios.aConfirmar) : '',
    };
  });
  return {
    codigo: tipo.codigo,
    nombre: tipo.nombre,
    lineas,
    fecha: formatearFechaHora(tipo.actualizadaEn ?? tipo.obtenidaEn),
    sinRenovarDesde: tipo.desactualizada ? formatearFechaHora(tipo.obtenidaEn) : '',
  };
}
