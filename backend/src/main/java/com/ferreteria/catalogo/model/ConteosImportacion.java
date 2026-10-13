package com.ferreteria.catalogo.model;

/** Los seis contadores de una importacion: suben, bajan, nuevos, sin cambio, no estan y con error. */
public record ConteosImportacion(int suben, int bajan, int nuevos, int sinCambio, int noEstan, int conError) {}
