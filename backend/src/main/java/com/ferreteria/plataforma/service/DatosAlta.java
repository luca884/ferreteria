package com.ferreteria.plataforma.service;

import com.ferreteria.ferreteria.model.CondicionIva;

/** Alta de una ferretería con su primer Dueño. */
public record DatosAlta(String razonSocial, String cuit, CondicionIva condicionIva, Dueno dueno) {

    public record Dueno(String nombre, String email, String password) {}
}
