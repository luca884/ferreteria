package com.ferreteria.clientes.model;

import com.ferreteria.common.Cuit;

/**
 * Valida y normaliza el documento de un cliente (CL-002). Se guarda solo con dígitos: lo que se tipea puede traer puntos,
 * guiones o espacios. El DNI tiene 7 u 8 dígitos. El CUIT y el CUIL tienen 11: un prefijo de persona o de empresa y un
 * dígito verificador módulo 11 con los pesos de ARCA. Cuando la cuenta da 10 no existe dígito válido (ARCA reasigna el
 * prefijo), así que ninguno se acepta.
 */
public final class DocumentoCliente {

    private DocumentoCliente() {}

    /** Los dígitos del documento, o {@link DocumentoInvalidoException} con el motivo para el usuario. */
    public static String normalizar(TipoDocumento tipo, String texto) {
        String digitos = texto == null ? "" : texto.replaceAll("[\\s.\\-]", "");
        return switch (tipo) {
            case DNI -> dni(digitos);
            case CUIT, CUIL -> cuit(tipo.name(), digitos);
        };
    }

    private static String dni(String digitos) {
        if (!digitos.matches("\\d{7,8}") || digitos.matches("0+")) {
            throw new DocumentoInvalidoException("El DNI tiene que tener 7 u 8 dígitos.");
        }
        return digitos;
    }

    /**
     * El largo y el dígito verificador son los de {@link Cuit} (un solo código y un solo texto para proveedores, ferreterías
     * y clientes); acá quedan el nombre CUIL y el prefijo, que se revisa antes del verificador.
     */
    private static String cuit(String nombre, String escrito) {
        try {
            String digitos = Cuit.soloDigitos(escrito, nombre);
            if (!Cuit.tienePrefijoValido(digitos)) {
                throw new DocumentoInvalidoException("El " + nombre + " no es válido: revisá los dos primeros dígitos.");
            }
            Cuit.verificar(digitos, nombre);
            return digitos;
        } catch (IllegalArgumentException e) {
            throw new DocumentoInvalidoException(e.getMessage());
        }
    }
}
