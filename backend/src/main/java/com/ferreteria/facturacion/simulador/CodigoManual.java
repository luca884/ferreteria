package com.ferreteria.facturacion.simulador;

import java.util.Arrays;

/**
 * Los códigos de WSFEv1 que devuelve el simulador, con su texto. Manual: {@value #VERSION_MANUAL}.
 *
 * <p>Texto del manual 4.8 (descripción); confirmar el Msg literal contra homologación (rama 6). El manual da la
 * «Descripción de la validación» y no el {@code Msg} que devuelve el servicio (sus pocos ejemplos de {@code Msg} son
 * resúmenes distintos): cada texto es la descripción de la fila, tal cual, con {@code //} entre párrafos. Lo que el manual no
 * trae se marca como inferido.
 *
 * <p>Prefijo E: validación excluyente (rechaza); O: no excluyente (aprueba con CAE). Dónde va cada uno (p. 25 y 76,
 * ejemplos 3 y 4 en p. 83-86): E10007, control de {@code FeCabReq}, y E602, de {@code FECompConsultar}, en {@code Errors};
 * las demás E, validaciones de {@code FeDetReq}, en las {@code Observaciones} del detalle con R; las O, en las
 * {@code Observaciones} con A.
 */
enum CodigoManual {

    // ---- Validaciones excluyentes (rechazan). 10007 es de la cabecera (Errors); las demás, del comprobante (Obs con R) ----

    E10007(10007, "Campo CbteTipo sea: - 01, 02, 03, 04, 05 ,34, 39, 60, 63, 201, 202, 203 para los clase A // - 06, 07, 08, "
            + "09, 10, 35, 40,64, 61, 206, 207, 208 para los clase B. // - 11, 12, 13, 15, 211, 212, 213 para los clase C. // "
            + "- 51, 52, 53, 54 para los clase “A con leyenda operación sujeta a retención”. // - 49 para los Bienes Usados. // "
            + "Consultar método FEParamGetTiposCbte."),
    E10013(10013, "Para comprobantes clase “A” y “A con leyenda operación sujeta a retención” el campo DocTipo tenga valor 80 "
            + "(CUIT)"),
    E10015(10015, "Para comprobantes tipo B en pedidos múltiples (CbteDesde distinto a CbteHasta) y el resultado de la "
            + "operación ImpTotal / (CbteHasta – CbteDesde + 1 ) < monto en pesos resultante según RG4444. el campo DocTipo "
            + "deberá ser igual a 99, el campo DocNro deberá ser cero (0). // Para comprobantes tipo B en pedidos individuales "
            + "(CbteDesde igual a CbteHasta) y el resultado de la operación ImpTotal / (CbteHasta – CbteDesde + 1 ) < monto en "
            + "pesos resultante según RG4444 si el campo DocTipo es igual a 99, el campo DocNro deberá ser cero. // Para "
            + "comprobantes tipo B individuales (CbteDesde igual a CbteHasta), si el campo DocTipo es 80, 86 u 87, deberá "
            + "verificarse que el número consignado se encuentre en los padrones de ARCA. Si DocTipo es 80 y DocNro es "
            + "23000000000 (No Categorizado) esta validación no se tendrá en cuenta. // Si el campo DocTipo es distinto de 80, "
            + "86 u 87, deberá verificarse que se ingrese uno de los valores devueltos por el método FEParamGetTiposDoc y que "
            + "se informe el campo DocNro. // Para pedidos individuales (CbteDesde igual a CbteHasta) tipo B con montos "
            + "superiores a monto en pesos resultante según RG4444 el campo DocTipo deberá ser igual a algunos de los valores "
            + "devueltos por el método FEParamGetTiposDoc excepto 99 y deberá informar el campo DocNro. // Para comprobantes "
            + "tipo 49 – Bienes Usados, DocTipo deberá ser igual a algunos de los valores devueltos por el método "
            + "FEParamGetTiposDoc excepto el 99 y deberá informar el campo DocNro. // Para comprobantes tipo 49 – Bienes "
            + "Usados, si DocTipo es 80, 86 u 87, deberá verificarse que el número consignado se encuentra en los padrones de "
            + "arca. // Para comprobantes MiPyMEs (FCE) el documento del receptor debe ser 80 CUIT."),
    E10016(10016, "El número de comprobante informado <CbteDesde> debe ser mayor en 1 al último informado para igual punto de "
            + "venta y tipo de comprobante. Consultar método FECompUltimoAutorizado // El campo <CbteFch> podrá ser: - Nulo o "
            + "comprendido en el rango N-5 y N+5 siendo N la fecha de envío del pedido de autorización, para Concepto= 01 "
            + "Productos. La misma no podrá exceder el mes de presentación. - Para Concepto 02, 03 el campo CbteFch puede ser "
            + "nulo o comprendido en el rango N-10 y N+10 siendo N la fecha de envío del pedido de autorización. - Deberá ser "
            + "mayor o igual al del ultimo comprobante emitido para ese tipo y punto de venta - Para comprobantes MiPyMEs (FCE) "
            + "estar comprendido en el rango N-5 y N+1 siendo N la fecha de envío del pedido de autorización. De tratarse de "
            + "notas de débito o crédito, la fecha del comprobante puede ser hasta N-5."),
    E10023(10023, "La suma de los campos <importe> en <IVA> debe ser igual al valor ingresado en ImpIVA. Margen de error: "
            + "Error relativo porcentual deberá ser <= 0.01% o el error absoluto <=0.01 * cantidad de alícuotas de IVA "
            + "ingresadas * No aplica para comprobantes tipo C."),
    E10040(10040, "De enviarse el tag <CbtesAsoc>, entonces el campo “código de tipo de comprobante” <CbteTipo> a autorizar "
            + "tiene que ser 01, 02, 03, 06, 07, 08, 12, 13, 51, 52, 53, 201, 206 o 211 // Para 02 y 03 pueden asociarse los "
            + "tipos de comprobante 01, 02, 03, 04, 05, 34, 39, 60, 63, 88 y 991 // Para 07 y 08 pueden asociarse 06, 07, 08, "
            + "09, 10, 35, 40, 61, 64, 88 y 991 // Para 12 o 13 pueden asociarse 11, 12, 13 y 15. // Para 52 o 53 pueden "
            + "asociarse 51, 52, 53, 54, 88 y 991 // Para 01,06 y 51 pueden asociarse 88 y 991 // Para comprobantes MiPyMEs "
            + "(FCE) 201, 206 o 211 puede asociarse los comprobantes (91, 990, 991, 993, 994, 995). // Para comprobantes "
            + "MiPyMEs (FCE) A 202 y 203, puede asociar 201,202 o 203. // Para comprobantes MiPyMEs (FCE) B 207, 208 puede "
            + "asociar 206, 207, 208. // Para comprobantes MiPyMEs (FCE) C 212, 213 puede asociarse 211, 212, 213."),
    E10048(10048, "El campo “Importe Total” <ImpTotal>, debe ser igual a la suma de ImpTotConc + ImpNeto + ImpOpEx + ImpTrib + "
            + "ImpIVA // Para comprobantes tipo C, el campo “Importe Total” <ImpTotal>, debe ser igual a la suma de ImpNeto + "
            + "ImpTrib. // Para comprobantes tipo 49 – Bienes Usados, si se encuentra inscripto en MONOTRIBUTO el campo "
            + "“Importe Total” <ImpTotal>, debe ser igual a la suma de ImpTotConc + ImpTrib. // Margen de error: Error relativo "
            + "porcentual deberá ser <= 0.01% o el error absoluto <=0.01"),
    E10051(10051, "Los importes informados en AlicIVA se deben corresponder según el tipo de iva seleccionado. Para "
            + "comprobantes tipo 2, 3, 7, 8, 52 y 53 no se tiene en cuenta esta validación. Margen de error: Error relativo "
            + "porcentual deberá ser <= 0.01% o el error absoluto <=0.01 No aplica para comprobantes tipo C"),
    E10061(10061, "La suma de los campos <BaseImp> en <AlicIva> debe ser igual al valor ingresado en ImpNeto. Esta validación "
            + "no deberá ser tenida en cuenta, cuando el <CbteTipo> sea 02, 03 ,07, 08, para comprobantes tipo C (11, 12, 13, "
            + "15) y para Comprobantes tipo “A con leyenda operación sujeta a retención” (52, 53) // Margen de error: Error "
            + "relativo porcentual deberá ser <= 0.01% o el error absoluto <=0.01 * cantidad de alícuotas de IVA ingresadas *"),
    E10071(10071, "Si el tipo de comprobante es C, el array de IVA no debe informarse."),
    E10152(10152, "Si informa fecha de comprobante <CbteFch> para el Concepto del tipo “01 – Productos” con fecha superior a "
            + "la fecha de envío de autorización, el mes de la fecha del comprobante <CbteFch> debe coincidir con el mes de la "
            + "fecha de envío de autorización. // Si informa fecha de comprobante <CbteFch> para comprobantes del tipo MiPyMEs "
            + "(FCE) con fecha superior a la fecha de envío de autorización, el mes de la fecha del comprobante <CbteFch> debe "
            + "coincidir con el mes de la fecha de envío de autorización."),
    E10197(10197, "Si el comprobante es Debito o Credito, se deberá informar de forma obligatoria los campos Fecha "
            + "Comprobantes Asociados Desde/Hasta, o al menos un comprobante asociado."),
    E10242(10242, "El campo de identificación de la Condición de IVA del receptor no es un valor permitido. Para mayor detalle "
            + "consular el método FEParamGetCondicionIvaReceptor."),
    E10243(10243, "El campo de identificación de Condición de IVA del receptor no es valido para la clase de comprobante "
            + "informado. Para mas detalle consultar el Método: FEParamGetCondicionIvaReceptor"),
    E10246(10246, "Campo Condición Frente al IVA del receptor es obligatorio conforme a lo reglamentado por la Resolución "
            + "General N° 5616. Para mas información consular método FEParamGetCondicionIvaReceptor."),
    /**
     * Inferido: el manual no dice qué devuelve FECompConsultar si el comprobante no existe; es el 602 de la p. 21, que es un
     * error de infraestructura y va en {@code Errors}, como los de FECompConsultar (10200 a 10202 y 10104, p. 194-195).
     */
    E602(602, "No existen datos en nuestros registros."),

    // ---- Validaciones no excluyentes (aprueban con CAE): Obs con A ----

    O10041(10041, "Si el punto de venta del comprobante asociado (campo <PtoVta> de <CbtesAsoc>) es electrónico, el número de "
            + "comprobante debe obrar en las bases del organismo para el punto de venta y tipo de comprobante informado."),
    O10063(10063, "Para comprobantes Clase “A” y “A con leyenda operación sujeta a retención” el receptor del comprobante "
            + "informado en <DocTipo> y <DocNro> debe corresponder a un contribuyente activo en el Impuesto al Valor Agregado "
            + "o Responsable Monotributo."),
    O10188(10188, "Si el tipo de comprobante que está autorizando es 1 - Factura A o 4 - Recibo A o 6 - Factura B o 9 - Recibo "
            + "B o 11 - Factura C o 15 - Recibo C, por la categorización de las cuits emisora y receptora, se deberia realizar "
            + "una factura de crédito electrónica."),
    O10217(10217, "Para comprobantes Clase “A” y “A con leyenda operación sujeta a retención”, donde el receptor del "
            + "comprobante informado en <DocTipo> y <DocNro> se encuentra activo en el Impuesto Responsable Monotributo, “El "
            + "crédito fiscal discriminado en el presente comprobante solo podrá ser computado a efectos del Procedimiento "
            + "permanente de transición al Régimen General.”"),
    O10237(10237, "El importe de la nota de crédito supera el monto del comprobante asociado que estás ajustando. Verificá los "
            + "montos ingresados y de tratarse de un error, tenés que efectuar el ajuste o anulación de la operación según "
            + "corresponda.");

    /** La versión del manual que copia el simulador. */
    static final String VERSION_MANUAL = "Facturación RG 4291 – Proyecto FE v4.8, revisión del 1 de diciembre de 2026";

    private final int codigo;
    private final String texto;

    CodigoManual(int codigo, String texto) {
        this.codigo = codigo;
        this.texto = texto;
    }

    int codigo() {
        return codigo;
    }

    String texto() {
        return texto;
    }

    static CodigoManual de(int codigo) {
        return Arrays.stream(values()).filter(c -> c.codigo == codigo).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("El simulador no conoce el código " + codigo + "."));
    }
}
