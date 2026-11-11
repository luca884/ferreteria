/**
 * Los avisos de éxito y de error que responde a una acción del usuario se traen a la vista cuando aparecen
 * (responsive, S8): «Se guardó el cliente.» puede quedar fuera de la pantalla cuando la acción se dispara desde el pie
 * de un formulario largo —el caso de Clientes, que además tiene la lista debajo— y hay que ir a buscarlo scrolleando.
 *
 * Un solo punto de conexión en el shell alcanza para las 22 plantillas que tienen avisos. La alternativa —una directiva
 * por plantilla— obligaba a tocar 21 componentes y quedaba a medio camino con el primer aviso nuevo. El observer mira
 * el contenido, no la pantalla: no hay nada que registrar por plantilla.
 *
 * Qué cuenta como aviso, y por qué:
 *
 * - `.alerta` es la familia de avisos de la app (38 entre `p.alerta` y `div.alerta`, en 22 plantillas): el verde de
 *   «Se guardó…», el amarillo de «Se guardó, pero…», el rojo de error y el `.aviso-al-llegar` del shell. Entra entera.
 * - `.aviso[role]`, `.aviso-caja[role]` y `.mensaje[role]` son los avisos de acción del mostrador, que usan sus propias
 *   clases: el de la caja cerrada con su enlace (`mostrador.html`), la nota del renglón recién agregado o rechazado y
 *   el mensaje urgente de la búsqueda. Llevan `role` porque se anuncian solos, y esa es justamente la marca que los
 *   separa de las pistas.
 * - Quedan afuera, a propósito, las pistas estáticas, que no responden a una acción y no tienen por qué mover la
 *   pantalla: `p.aviso.apagada` y `p.aviso.desactualizada` de la cotización del menú (`layout/cotizacion-lateral.html`),
 *   `p.aviso.nuevos-no-creados` del resumen de la importación (`importaciones.html`), `span.aviso-fila` de los renglones
 *   de la compra (`compras/nueva-compra.html`), los dos `p.aviso-caja` de texto fijo del pop-up de anulación
 *   (`clientes/cuenta/dialogo-anulacion.html`) y todo lo que no es aviso: `.ayuda`, `.error` de campo y `.pista`.
 *
 * Un aviso que ya estaba antes de conectar tampoco mueve nada: solo se miran los nodos que se insertan, y
 * `block: 'nearest'` no scrollea si el que se inserta ya está a la vista.
 */
export const SELECTOR_DE_AVISO = '.alerta, .aviso[role], .aviso-caja[role], .mensaje[role]';

/**
 * Mira lo que se inserta dentro de un contenedor y trae a la vista el aviso que encuentra.
 *
 * Se usa desde el shell sobre el `main.contenido`, que es el que scrollea (la ventana no): el aviso de una pantalla
 * aparece en medio de esa pantalla, muchas veces debajo del formulario que el usuario acaba de completar.
 */
export class AvisosALaVista {
  private observador: MutationObserver | null = null;

  /** Empieza a mirar lo que se inserta dentro de `raiz`; conectar de nuevo reemplaza al observer anterior. */
  conectar(raiz: HTMLElement): void {
    this.desconectar();
    this.observador = new MutationObserver((mutaciones) => {
      for (const mutacion of mutaciones) this.revisar(mutacion.addedNodes);
    });
    this.observador.observe(raiz, { childList: true, subtree: true });
  }

  desconectar(): void {
    this.observador?.disconnect();
    this.observador = null;
  }

  /**
   * Los nodos que acaban de insertarse. Es público porque el `MutationObserver` entrega las mutaciones en una
   * microtask: los tests de las reglas lo llaman directo y reservan la espera para un solo test del observer.
   */
  revisar(nodos: Iterable<Node>): void {
    for (const nodo of nodos) {
      if (!(nodo instanceof Element)) continue;
      // Un aviso puede llegar solo (Angular inserta el `p.alerta` cuando cambia el estado) o dentro de un bloque que se
      // inserta entero (una pantalla nueva): en el segundo caso se trae el primero, que es el de más arriba.
      const aviso = nodo.matches(SELECTOR_DE_AVISO) ? nodo : nodo.querySelector(SELECTOR_DE_AVISO);
      if (!aviso) continue;
      // `nearest` scrollea solo si el aviso no se ve y lo mínimo para que se vea: si ya está a la vista, no mueve nada.
      // Sin `behavior: 'smooth'` a propósito: pelea con el scroll del usuario y no respeta `prefers-reduced-motion`.
      // Sin `setTimeout` ni debounce: cuando llega la mutación el aviso ya está pintado. jsdom no implementa
      // `scrollIntoView`, de ahí el `?.`.
      aviso.scrollIntoView?.({ block: 'nearest' });
    }
  }
}
