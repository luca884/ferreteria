package com.ferreteria.catalogo.api;

/**
 * Puerto para leer una factura de proveedor en PDF con texto (CU-13). Lo implementa el modulo de catalogo, que ya sabe
 * leer PDF en un proceso aparte y con topes; las compras solo piden la lectura.
 */
public interface LectorDeFacturas {

    /**
     * Lee la factura. Un PDF escaneado, protegido, danado o demasiado grande avisa con una excepcion de pedido
     * incorrecto; una factura sin renglones reconocidos devuelve la lista de renglones vacia.
     */
    FacturaLeida leer(byte[] pdf);
}
