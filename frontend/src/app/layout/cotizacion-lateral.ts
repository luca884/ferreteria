import { Component, computed, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Cotizacion, LADO_LABELS, LadoCotizacion, PreciosCotizacion, TipoCotizacion } from '../core/cotizacion/cotizacion.models';
import { formatearFechaHora } from '../core/formato/fecha-hora';
import { formatearCotizacion } from '../core/formato/monto';

/**
 * Una línea de la tabla: el tipo con su compra y su venta. El lado del dólar de los precios muestra el dólar realmente
 * aplicado (el vigente), no el último que trajo la fuente.
 */
interface FilaTipo {
  codigo: string;
  nombre: string;
  /** false si todavía no hay ningún valor guardado de este tipo. */
  conDatos: boolean;
  compra: string;
  venta: string;
  /** El lado que usan los precios en esta línea; null si no es el dólar de los precios. */
  ladoDePrecios: LadoCotizacion | null;
  /** «Oficial: cotización del 16/10 08:00», al pasar el mouse; vacío sin fecha. */
  titulo: string;
  /** «16/10 07:00» si ninguna fuente renovó este tipo en más de 1 hora; vacío si está al día. */
  sinRenovarDesde: string;
}

/** Arriba del bloque: «Dólar de los precios · Oficial venta 1.570,00» y, si hay, el dólar nuevo a confirmar. */
interface ResumenPrecios {
  etiqueta: string;
  valor: string;
  /** «14,50»: el dólar nuevo que espera la confirmación del Dueño; vacío si no hay. */
  aConfirmar: string;
}

/** Donde el navegador recuerda si el bloque quedó plegado (arranca desplegado). */
const CLAVE_ABIERTA = 'cotizacion-lateral-abierta';

let siguienteId = 0;

/**
 * Bloque «Cotización» del menú lateral (presentacional): lo que el contenedor (el shell) le pasa, lo muestra. Lo ven
 * todos los roles; el enlace «Configurar» es solo del Dueño. Arriba, el dólar de los precios; abajo, una tabla
 * plegable con la compra y la venta de cada tipo y una sola fecha (la de cada tipo, al pasar el mouse por su línea).
 * El aviso amarillo sale únicamente de `desactualizada` (más de 1 hora sin respuesta de las APIs), nunca de la fecha
 * del mercado: un fin de semana con las APIs respondiendo no da un aviso falso.
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

  protected readonly idCuerpo = `cotizaciones-${++siguienteId}`;
  protected readonly abierta = signal(leerAbierta());

  protected readonly filas = computed<FilaTipo[]>(() => {
    const c = this.cotizacion();
    return c ? c.visibles.map((tipo) => filaDe(tipo, c.precios)) : [];
  });

  protected readonly resumen = computed<ResumenPrecios | null>(() => {
    const c = this.cotizacion();
    if (!c || c.precios.valor === null) return null;
    const tipo = c.visibles.find((t) => t.codigo === c.precios.codigo);
    return {
      etiqueta: `${tipo?.nombre ?? 'Dólar'} ${LADO_LABELS[c.precios.lado]}`,
      valor: formatearCotizacion(c.precios.valor),
      aConfirmar: c.precios.aConfirmar !== null ? formatearCotizacion(c.precios.aConfirmar) : '',
    };
  });

  /** «16/10 09:00»: la fecha más reciente entre los tipos visibles; vacío si ninguno tiene. */
  protected readonly fecha = computed(() => {
    const fechas = (this.cotizacion()?.visibles ?? [])
      .map((tipo) => tipo.actualizadaEn ?? tipo.obtenidaEn)
      .filter((f): f is string => f !== null)
      .sort((a, b) => Date.parse(a) - Date.parse(b));
    return formatearFechaHora(fechas.at(-1));
  });

  protected readonly textoDesplegar = computed(() => {
    if (this.abierta()) return 'Ocultar las cotizaciones';
    const cantidad = this.filas().length;
    return cantidad === 1 ? 'Ver la cotización' : `Ver las ${cantidad} cotizaciones`;
  });

  /** «16/10 08:00»: la última vez que alguna API respondió, que es la cotización que se está usando. */
  protected readonly desdeCuando = computed(() => formatearFechaHora(this.cotizacion()?.ultimaRespuesta));

  protected alternar(): void {
    const abierta = !this.abierta();
    this.abierta.set(abierta);
    try {
      localStorage.setItem(CLAVE_ABIERTA, String(abierta));
    } catch {
      // Sin almacenamiento (modo privado): se pliega igual, solo que no se recuerda.
    }
  }
}

function leerAbierta(): boolean {
  try {
    return localStorage.getItem(CLAVE_ABIERTA) !== 'false';
  } catch {
    return true;
  }
}

function filaDe(tipo: TipoCotizacion, precios: PreciosCotizacion): FilaTipo {
  const ladoDePrecios = tipo.esDePrecios ? precios.lado : null;
  // El lado de los precios muestra el vigente: con un dólar a confirmar, el crudo engañaría (auditoría H09).
  const valor = (lado: LadoCotizacion, crudo: number | null) => {
    const importe = lado === ladoDePrecios && precios.valor !== null ? precios.valor : crudo;
    return importe === null ? '—' : formatearCotizacion(importe);
  };
  const fecha = formatearFechaHora(tipo.actualizadaEn ?? tipo.obtenidaEn);
  return {
    codigo: tipo.codigo,
    nombre: tipo.nombre,
    conDatos: tipo.compra !== null || tipo.venta !== null,
    compra: valor('COMPRA', tipo.compra),
    venta: valor('VENTA', tipo.venta),
    ladoDePrecios,
    titulo: fecha ? `${tipo.nombre}: cotización del ${fecha}` : '',
    sinRenovarDesde: tipo.desactualizada ? formatearFechaHora(tipo.obtenidaEn) : '',
  };
}
