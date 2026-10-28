package com.ferreteria.facturacion.model;

/**
 * El ambiente de ARCA de una ferretería (CU-01, «Ambiente de ARCA»). El simulador lo asigna solo el super admin, y solo a
 * una ferretería marcada «de prueba»; homologación y producción salen de la CA del certificado (ramas 5 y 6), y de
 * producción no se vuelve.
 */
public enum Ambiente {
    SIMULADOR,
    HOMOLOGACION,
    PRODUCCION
}
