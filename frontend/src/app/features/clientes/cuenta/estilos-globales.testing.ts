/**
 * Las reglas globales de `styles.css` que pisan las referencias de las tablas de cuenta: `th` las pasa a mayúsculas
 * (es el estilo de un encabezado de columna, no el de «Venta N.º 7»). Los specs de componentes no traen `styles.css`,
 * así que se replica la regla antes de crear el componente: el orden es el de la app (globales primero, después los
 * del componente) y el spec vigila que los estilos del componente se la ganen.
 */
export function cargarEstilosGlobales(): void {
  if (document.head.querySelector('style[data-estilos-globales]')) return;
  const hoja = document.createElement('style');
  hoja.dataset['estilosGlobales'] = '';
  hoja.textContent = 'th { text-transform: uppercase; letter-spacing: 0.04em; }';
  document.head.prepend(hoja);
}
