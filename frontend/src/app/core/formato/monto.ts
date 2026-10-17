import { Pipe, PipeTransform } from '@angular/core';

const FORMATO = new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS' });

/** Formatea un importe como `$ 1.234,56`. `null` o `undefined` dan texto vacío. */
export function formatearMonto(valor: number | null | undefined): string {
  return valor === null || valor === undefined ? '' : FORMATO.format(valor);
}

const FORMATO_DOLARES = new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'USD' });

/** Un costo en su moneda: `$ 1.234,50` o `US$ 10,00`. Sin moneda, pesos. */
export function formatearCosto(valor: number, moneda: 'ARS' | 'USD' | null | undefined): string {
  return (moneda === 'USD' ? FORMATO_DOLARES : FORMATO).format(valor);
}

/**
 * Un importe que viene como texto decimal exacto (`"880.65"`), formateado sin pasar por Number: `Intl` lee la cadena
 * como decimal. Entre 2 y `maxDecimales` decimales; `$` o `US$` según la moneda (pesos si falta).
 */
export function formatearTexto(
  valor: string,
  moneda: 'ARS' | 'USD' | null | undefined,
  maxDecimales = 2,
): string {
  const formato = new Intl.NumberFormat('es-AR', {
    style: 'currency',
    currency: moneda === 'USD' ? 'USD' : 'ARS',
    minimumFractionDigits: 2,
    maximumFractionDigits: maxDecimales,
  });
  // Intl.NumberFormat v3 (Node 19+, navegadores actuales) lee la cadena como decimal exacto; los tipos de lib aún no lo declaran.
  return formato.format(valor as unknown as number);
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
