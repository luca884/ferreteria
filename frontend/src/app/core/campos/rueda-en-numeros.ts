import { DestroyRef, DOCUMENT, EnvironmentProviders, inject, provideAppInitializer } from '@angular/core';

/**
 * En un campo numérico con el foco, la rueda del mouse cambia el valor sin que nadie lo note (un precio de 4.000
 * quedó en 3999,94). Se cancela la rueda solo en ese caso; mientras el puntero está sobre ese campo la página
 * tampoco se desplaza. Sobre cualquier otro elemento la rueda funciona como siempre.
 */
export function cancelarRuedaEnNumeros(evento: WheelEvent): void {
  const campo = evento.target;
  if (campo instanceof HTMLInputElement && campo.type === 'number' && campo === campo.ownerDocument.activeElement) {
    evento.preventDefault();
  }
}

/** Escucha la rueda en toda la app (`passive: false` para poder cancelarla) y deja de escuchar al destruirse. */
export function provideRuedaSinCambiarNumeros(): EnvironmentProviders {
  return provideAppInitializer(() => {
    const documento = inject(DOCUMENT);
    const opciones: AddEventListenerOptions = { capture: true, passive: false };
    documento.addEventListener('wheel', cancelarRuedaEnNumeros, opciones);
    inject(DestroyRef).onDestroy(() => documento.removeEventListener('wheel', cancelarRuedaEnNumeros, opciones));
  });
}
