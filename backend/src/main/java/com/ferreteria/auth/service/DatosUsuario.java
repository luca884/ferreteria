package com.ferreteria.auth.service;

import com.ferreteria.auth.model.Rol;

/** Alta de un usuario de la ferretería. */
public record DatosUsuario(String nombre, String email, Rol rol, String password) {}
