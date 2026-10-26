package com.ferreteria.catalogo.service.lectura;

/**
 * Una palabra de una pagina del PDF, en puntos: sus bordes izquierdo y derecho, su linea base (crece hacia abajo) y el
 * tamano de la letra. La pagina es base 1.
 */
record PalabraPdf(int pagina, double izquierda, double derecha, double base, double tamano, String texto) {
}
