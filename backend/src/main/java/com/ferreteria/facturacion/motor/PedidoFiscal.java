package com.ferreteria.facturacion.motor;

import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.ComprobanteFiscalAlicuota;
import com.ferreteria.facturacion.wsfe.AlicIva;
import com.ferreteria.facturacion.wsfe.FeCabReq;
import com.ferreteria.facturacion.wsfe.FeCaeDetRequest;
import com.ferreteria.facturacion.wsfe.FeCaeReq;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.ResultGet;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * El pedido de {@code FECAESolicitar} de un registro, armado solo con lo congelado al registrarlo más el número y la CbteFch
 * del intento: el mismo registro con el mismo número y la misma fecha da siempre el mismo pedido (el reenvío por N−1 y la
 * verificación lo vuelven a armar igual). Y la comparación campo por campo con lo que ARCA tiene autorizado.
 */
final class PedidoFiscal {

    /** Productos: la ferretería vende mercadería. */
    static final int CONCEPTO_PRODUCTOS = 1;
    static final String PESOS = "PES";

    private PedidoFiscal() {
    }

    static FeCaeReq armar(ComprobanteFiscal registro, List<ComprobanteFiscalAlicuota> alicuotas, long numero,
            LocalDate cbteFch) {
        List<AlicIva> iva = alicuotas.stream()
                .sorted(Comparator.comparingInt(ComprobanteFiscalAlicuota::getIvaId))
                .map(a -> new AlicIva(a.getIvaId(), a.getBaseImp(), a.getImporte()))
                .toList();
        // Sin tributos: ImpTrib no se guarda y va siempre en 0 (el CHECK de importes de V32 lo deja afuera).
        FeCaeDetRequest detalle = new FeCaeDetRequest(CONCEPTO_PRODUCTOS, registro.getReceptorDocTipo(),
                registro.getReceptorDocNro(), numero, numero, cbteFch, registro.getImpTotal(), registro.getImpTotConc(),
                registro.getImpNeto(), registro.getImpOpEx(), BigDecimal.ZERO, registro.getImpIva(), PESOS, BigDecimal.ONE,
                registro.getReceptorCondicionIvaId(), iva, List.of(), null);
        return new FeCaeReq(FeCabReq.de(registro.getPuntoVenta(), registro.getCbteTipo()), detalle);
    }

    /**
     * Si lo que ARCA tiene autorizado es exactamente lo enviado: aprobado, con CAE y vencimiento, la misma serie y TODOS los
     * campos del detalle iguales (importes con su escala, alícuotas en orden, receptor, número y CbteFch).
     */
    static boolean coincide(FeCaeReq enviado, ResultGet autorizado) {
        return autorizado != null
                && autorizado.resultado() == Resultado.A
                && autorizado.codAutorizacion() != null
                && autorizado.fchVto() != null
                && autorizado.ptoVta() == enviado.feCabReq().ptoVta()
                && autorizado.cbteTipo() == enviado.feCabReq().cbteTipo()
                && enviado.feDetReq().equals(autorizado.detalle());
    }
}
