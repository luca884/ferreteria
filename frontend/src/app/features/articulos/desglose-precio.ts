import { Component, computed, effect, input, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { CadenaDePrecio, cadenaDePrecio, costoDeLista } from '../../core/formato/cadena-de-precio';
import { formatearFechaHora } from '../../core/formato/fecha-hora';
import { formatearCotizacion, formatearTexto } from '../../core/formato/monto';
import { Decimal, exacto } from '../../core/formato/precio-final';
import { DesglosePrecio, Moneda } from './articulo.models';

/** Los tres ajustes que escribe el Dueño: su propio grupo, fuera del formulario del artículo (decisión 43). */
export type GrupoAjustes = FormGroup<{
  ajuste1: FormControl<number | null>;
  ajuste2: FormControl<number | null>;
  ajuste3: FormControl<number | null>;
}>;

export const NOMBRES_AJUSTE = ['ajuste1', 'ajuste2', 'ajuste3'] as const;
export const MENSAJE_RANGO = 'Cada descuento o recargo tiene que estar entre -99,99 y 1.000.';
export const MENSAJE_DECIMALES = 'Los descuentos y recargos admiten hasta 2 decimales.';

/** Lo mismo que valida el backend (R-215): de -99,99 a 1.000 y hasta 2 decimales. null si el valor sirve. */
export function errorDeAjuste(valor: number | null | undefined): string | null {
  if (valor === null || valor === undefined) return null;
  if (valor < -99.99 || valor > 1000) return MENSAJE_RANGO;
  const exactoDelValor = exacto(valor);
  return exactoDelValor && exactoDelValor.escala > 2 ? MENSAJE_DECIMALES : null;
}

/** Los ajustes del grupo como lista de tres. */
export function valoresDe(grupo: GrupoAjustes): (number | null)[] {
  const v = grupo.getRawValue();
  return [v.ajuste1, v.ajuste2, v.ajuste3];
}

/** Los tres ajustes guardados del desglose. */
export function ajustesGuardados(d: DesglosePrecio): (number | null)[] {
  return [d.ajuste1, d.ajuste2, d.ajuste3].map((a) => a ?? null);
}

/**
 * Los ajustes con los que se calcula en vivo: lo escrito, salvo un valor inválido, que no cuenta (queda el guardado
 * en esa posición). `cambiaron` es si difieren de los guardados.
 */
export function ajustesEfectivos(
  d: DesglosePrecio,
  escritos: readonly (number | null)[],
): { ajustes: (number | null)[]; cambiaron: boolean } {
  const guardados = ajustesGuardados(d);
  const ajustes = guardados.map((guardado, i) => {
    const valor = escritos[i] ?? null;
    return errorDeAjuste(valor) === null ? valor : guardado;
  });
  return { ajustes, cambiaron: ajustes.some((a, i) => a !== guardados[i]) };
}

/**
 * La cadena del desglose con los ajustes escritos (R-219). Sin cambios en los ajustes parte del costo GUARDADO, que
 * puede no ser el de la lista; con ajustes distintos, del costo que saldría de la lista, que es lo que se guardaría.
 */
export function cadenaDelDesglose(
  d: DesglosePrecio,
  escritos: readonly (number | null)[],
): CadenaDePrecio {
  const { ajustes, cambiaron } = ajustesEfectivos(d, escritos);
  const bonificaciones = [d.bonificacion1, d.bonificacion2, d.bonificacion3];
  let costoGuardado: Decimal = d.costoGuardado;
  if (d.tieneLista && cambiaron) {
    costoGuardado =
      costoDeLista(d.precioLista, d.alicuotaIva, d.listaIncluyeIva, bonificaciones, ajustes) ??
      d.costoGuardado;
  }
  return cadenaDePrecio({
    // Sin cambios en los ajustes el último paso es el precio GUARDADO (R-209); con cambios, el que saldría.
    precioGuardado: cambiaron ? undefined : d.precioFinal,
    tieneLista: d.tieneLista,
    lista: d.precioLista,
    incluyeIva: d.listaIncluyeIva,
    alicuota: d.alicuotaIva,
    bonificaciones,
    ajustes,
    costoGuardado,
    moneda: d.moneda,
    cotizacion: d.cotizacion,
    margen: d.margen,
    multiplo: d.multiplo,
  });
}

const FORMATO_PORCENTAJE = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 });

const porcentaje = (valor: number) => FORMATO_PORCENTAJE.format(valor);

/** Hasta el costo el importe está en la moneda del artículo; de la cotización en adelante, en pesos. */
const EN_MONEDA_DEL_ARTICULO = ['LISTA', 'SIN_IVA', 'BONIFICACION', 'AJUSTE', 'COSTO'];

/** Una lista legible: «10 %», «10 % y 5 %», «10 %, 5 % y 2,5 %». */
function unirConY(items: string[]): string {
  return items.length <= 1
    ? (items[0] ?? '')
    : `${items.slice(0, -1).join(', ')} y ${items.at(-1)}`;
}

interface PasoMostrado {
  tipo: string;
  nombre: string;
  /** null en el redondeo: el múltiplo ya va en el nombre. */
  importe: string | null;
}

@Component({
  selector: 'app-desglose-precio',
  imports: [ReactiveFormsModule],
  templateUrl: './desglose-precio.html',
  styleUrl: './desglose-precio.css',
})
export class DesglosePrecioVista {
  readonly desglose = input.required<DesglosePrecio>();
  /** Los ajustes escritos; el Dueño los edita acá y el contenedor los guarda con su propia acción. */
  readonly ajustes = input.required<GrupoAjustes>();
  readonly esDueno = input(false);
  /** Desde cuándo rige la cotización de los precios (ISO), de `GET /cotizacion`. */
  readonly cotizacionDesde = input<string | null>(null);

  protected readonly nombres = NOMBRES_AJUSTE;
  /** Cada cambio de los controles invalida lo calculado. */
  private readonly cambios = signal(0);

  /** Hay campos para editar: solo el Dueño y solo con lista (decisión 44 y decisión 46). */
  protected readonly editable = computed(() => this.esDueno() && this.desglose().tieneLista);

  protected readonly valores = computed(() => {
    this.cambios();
    return valoresDe(this.ajustes());
  });
  protected readonly errores = computed(() => this.valores().map(errorDeAjuste));
  private readonly efectivos = computed(() => ajustesEfectivos(this.desglose(), this.valores()));
  protected readonly sinGuardar = computed(
    () => this.desglose().tieneLista && this.efectivos().cambiaron,
  );
  protected readonly cadena = computed(() => cadenaDelDesglose(this.desglose(), this.valores()));

  protected readonly pasos = computed<PasoMostrado[]>(() => {
    const d = this.desglose();
    return this.cadena().pasos.map((paso) => {
      const moneda: Moneda = EN_MONEDA_DEL_ARTICULO.includes(paso.tipo) ? d.moneda : 'ARS';
      const pct = paso.porcentaje === null ? null : Number(paso.porcentaje);
      const importe = paso.tipo === 'REDONDEO' ? null : formatearTexto(paso.importe, moneda, 4);
      return { tipo: paso.tipo, nombre: this.nombreDe(paso.tipo, pct, paso.importe), importe };
    });
  });

  /** «Costo de la lista» de la comparación: el del servidor, o el que salga de los ajustes escritos. */
  protected readonly costoDeLaLista = computed(() => {
    const d = this.desglose();
    const valor = this.sinGuardar() ? this.cadena().costoDeLista : d.costoDeLista;
    return valor === null ? '' : formatearTexto(String(valor), d.moneda);
  });
  protected readonly costoGuardado = computed(() =>
    formatearTexto(String(this.desglose().costoGuardado), this.desglose().moneda),
  );
  /** Algún ajuste escrito no es válido: no entra en los importes. */
  protected readonly hayAjusteInvalido = computed(() => this.errores().some((e) => e !== null));
  protected readonly noCoincide = computed(
    () => this.desglose().tieneLista && this.desglose().coincide === false,
  );

  protected readonly bonificaciones = computed(() => {
    const d = this.desglose();
    const puestas = [d.bonificacion1, d.bonificacion2, d.bonificacion3].filter(
      (b): b is number => b !== null && b !== undefined,
    );
    return puestas.length === 0 ? 'Ninguna' : unirConY(puestas.map((b) => `${porcentaje(b)} %`));
  });
  protected readonly ajustesGuardadosTexto = computed(() => {
    const puestos = ajustesGuardados(this.desglose()).filter((a): a is number => a !== null);
    return puestos.length === 0 ? 'Ninguno' : unirConY(puestos.map((a) => `${porcentaje(a)} %`));
  });

  protected readonly dolar = computed(() => formatearCotizacion(this.desglose().cotizacion));
  protected readonly dolarDesde = computed(() => formatearFechaHora(this.cotizacionDesde()));

  constructor() {
    effect((alLimpiar) => {
      const suscripcion = this.ajustes().valueChanges.subscribe(() =>
        this.cambios.update((n) => n + 1),
      );
      alLimpiar(() => suscripcion.unsubscribe());
    });
    // Sin lista los campos se ven, pero apagados; el estado lo decide este componente, no quien lo usa.
    effect(() => {
      const grupo = this.ajustes();
      const editable = this.editable();
      untracked(() =>
        editable ? grupo.enable({ emitEvent: false }) : grupo.disable({ emitEvent: false }),
      );
    });
  }

  /** Ids para aria-describedby de un ajuste: la pista, su error y, sin lista, el motivo. */
  protected describir(indice: number): string {
    const ids = ['ajustes-pista'];
    if (this.errores()[indice]) ids.push(`${NOMBRES_AJUSTE[indice]}-error`);
    if (!this.desglose().tieneLista) ids.push('motivo-sin-lista');
    return ids.join(' ');
  }

  private nombreDe(tipo: string, pct: number | null, importe: string): string {
    const d = this.desglose();
    switch (tipo) {
      case 'LISTA':
        return 'Precio de lista';
      case 'SIN_IVA':
        return `Sin el IVA de la lista (${porcentaje(pct ?? 0)} %)`;
      case 'BONIFICACION':
        return (pct ?? 0) >= 0
          ? `Bonificación del proveedor, descuenta ${porcentaje(pct ?? 0)} %`
          : `Bonificación del proveedor, recarga ${porcentaje(-(pct ?? 0))} %`;
      case 'AJUSTE':
        if ((pct ?? 0) < 0) return `Descuento propio de ${porcentaje(-(pct ?? 0))} %`;
        return (pct ?? 0) > 0
          ? `Recargo propio de ${porcentaje(pct ?? 0)} %`
          : 'Ajuste propio de 0 %';
      case 'COSTO':
        return 'Costo';
      case 'COTIZACION':
        return `Pasado a pesos con el dólar a ${formatearCotizacion(d.cotizacion)}`;
      case 'MARGEN':
        return `Margen de ${porcentaje(pct ?? 0)} %`;
      case 'IVA':
        return `IVA de ${porcentaje(pct ?? 0)} %`;
      case 'REDONDEO':
        return `Redondeo hacia arriba al múltiplo de ${porcentaje(Number(importe))}`;
      default:
        return 'Precio final';
    }
  }
}
