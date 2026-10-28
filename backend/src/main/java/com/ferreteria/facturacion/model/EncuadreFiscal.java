package com.ferreteria.facturacion.model;

import java.util.Objects;

/**
 * Cómo se encuadra un comprobante según la condición frente al IVA de quien lo emite y de quien lo recibe (S1, D2):
 * la letra, la condición del receptor que se informa a WSFEv1 y si lleva la leyenda de la Ley 27.618. Función pura;
 * la tabla de verdad completa está en {@code facturacion/letra-tabla-de-verdad.json} de los tests.
 *
 * <ul>
 *   <li>Emisor Responsable Inscripto: A al Responsable Inscripto y al Monotributista (al Monotributista, con la leyenda
 *       de la Ley 27.618); B al Consumidor Final, al Exento y a la venta sin cliente.</li>
 *   <li>Emisor Monotributista o Exento: C siempre, sin IVA discriminado.</li>
 * </ul>
 *
 * @param letra la letra del comprobante
 * @param condicionReceptor la condición que se informa como {@code CondicionIVAReceptorId}
 * @param leyendaLey27618 true si el comprobante lleva la leyenda de la Ley 27.618 (factura A a un Monotributista)
 */
public record EncuadreFiscal(Letra letra, CondicionIvaReceptor condicionReceptor, boolean leyendaLey27618) {

    public EncuadreFiscal {
        Objects.requireNonNull(letra, "Falta la letra del comprobante.");
        Objects.requireNonNull(condicionReceptor, "Falta la condición frente al IVA del receptor.");
    }

    /** El encuadre del comprobante que {@code emisor} le hace a un cliente con condición {@code receptor}. */
    public static EncuadreFiscal de(CondicionIvaEmisor emisor, CondicionIvaReceptor receptor) {
        Objects.requireNonNull(emisor, "Falta la condición frente al IVA del emisor.");
        Objects.requireNonNull(receptor, "Falta la condición frente al IVA del receptor.");
        if (emisor != CondicionIvaEmisor.RESPONSABLE_INSCRIPTO) {
            return new EncuadreFiscal(Letra.C, receptor, false);
        }
        return switch (receptor) {
            case RESPONSABLE_INSCRIPTO -> new EncuadreFiscal(Letra.A, receptor, false);
            case MONOTRIBUTISTA -> new EncuadreFiscal(Letra.A, receptor, true);
            case CONSUMIDOR_FINAL, EXENTO -> new EncuadreFiscal(Letra.B, receptor, false);
        };
    }

    /** La venta sin cliente se encuadra como la de un Consumidor Final (y va con {@code DocTipo} 99). */
    public static EncuadreFiscal sinCliente(CondicionIvaEmisor emisor) {
        return de(emisor, CondicionIvaReceptor.CONSUMIDOR_FINAL);
    }

    /** El {@code CbteTipo} de WSFEv1 del comprobante de esta letra y esa clase. */
    public int cbteTipo(ClaseComprobante clase) {
        return letra.cbteTipo(clase);
    }
}
