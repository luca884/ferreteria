import { Pipe, PipeTransform } from '@angular/core';

const FORMATO = new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS' });

/** Formatea un importe como `$ 1.234,56`. `null` o `undefined` dan texto vacío. */
export function formatearMonto(valor: number | null | undefined): string {
  return valor === null || valor === undefined ? '' : FORMATO.format(valor);
}

const FORMATO_COTIZACION = new Intl.NumberFormat('es-AR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** Una cotización como `1.000,00`, sin signo $: es un valor de mercado y no un importe de la caja. */
export function formatearCotizacion(valor: number | null | undefined): string {
  return valor === null || valor === undefined ? '' : FORMATO_COTIZACION.format(valor);
}

@Pipe({ name: 'monto', pure: true })
export class MontoPipe implements PipeTransform {
  transform(valor: number | null | undefined): string {
    return formatearMonto(valor);
  }
}
