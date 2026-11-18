/**
 * La pantalla del celular: la misma consulta de todo el CSS móvil (`max-width: 639.98px`). Sin `matchMedia` (pruebas,
 * impresión) no lo es. Se lee en el momento en que se llama: sirve para decidir cómo se abre algo, no para seguir la
 * rotación.
 */
export const esCelular = (): boolean =>
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(max-width: 639.98px)').matches;
