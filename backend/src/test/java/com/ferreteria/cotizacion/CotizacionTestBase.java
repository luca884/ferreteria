package com.ferreteria.cotizacion;

import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.model.Cotizacion;
import com.ferreteria.cotizacion.repository.ConfiguracionCotizacionRepository;
import com.ferreteria.cotizacion.repository.CotizacionRepository;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Base de los tests de integracion de cotizacion. La base de datos se comparte entre clases y no se revierte, la tabla
 * cotizacion es global y configuracion_cotizacion persiste: cada test siembra lo suyo y lo borra, con ferreterias y
 * casas propias (los CUIT 30-745000xx y 30-741000xx son de este lote).
 *
 * <p>Las fechas se siembran con los repositorios (no con JDBC crudo) para que el huso horario sea el mismo que el de
 * la aplicacion.
 */
public abstract class CotizacionTestBase extends IntegrationTestBase {

    /** Hash de demo1234, el mismo de db/dev-data. */
    protected static final String HASH_DEMO = "$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i";

    @Autowired
    protected CotizacionRepository cotizaciones;

    @Autowired
    protected ConfiguracionCotizacionRepository configuraciones;

    @AfterEach
    protected void limpiarTenantDelTest() {
        TenantContext.clear();
    }

    /** Crea la ferreteria (o reutiliza la del mismo CUIT) y devuelve su id. */
    protected Long ferreteria(String cuit, String razonSocial) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO') on duplicate key update razon_social = razon_social",
                razonSocial, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    /** Crea un usuario (o reutiliza el del mismo email) de esa ferreteria con ese rol y devuelve su email. */
    protected String usuario(Long ferreteriaId, String email, String rol) {
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, ?, ?, ?) "
                + "on duplicate key update nombre = nombre", ferreteriaId, email, HASH_DEMO, "Usuario " + email, rol);
        return email;
    }

    /**
     * El aviso del dolar con el que se escribieron los tests de recalculo (antes era un 20 % fijo). Mueven el dolar de
     * 1.000 a 1.100 o 1.200 para probar versiones, historial y el dia del recalculo, no el aviso: con el 5 % por defecto
     * esos saltos quedarian a confirmar. Los tests del aviso fijan el suyo con setUmbralDesvioPorcentaje.
     */
    protected static final BigDecimal UMBRAL_DE_LOS_TESTS = new BigDecimal("20");

    /** Reemplaza la configuracion de la ferreteria por una por defecto (con el aviso al 20 %) y los cambios pedidos. */
    protected ConfiguracionCotizacion configuracion(Long ferreteriaId, Consumer<ConfiguracionCotizacion> cambios) {
        borrarConfiguracion(ferreteriaId);
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        try {
            ConfiguracionCotizacion config = ConfiguracionCotizacion.porDefecto(ferreteriaId);
            config.setUmbralDesvioPorcentaje(UMBRAL_DE_LOS_TESTS);
            cambios.accept(config);
            return configuraciones.saveAndFlush(config);
        } finally {
            TenantContext.clear();
        }
    }

    protected void borrarConfiguracion(Long... ferreteriaIds) {
        for (Long id : ferreteriaIds) {
            jdbc.update("delete from configuracion_cotizacion where ferreteria_id = ?", id);
        }
    }

    /** Guarda (o reemplaza) el ultimo valor de un tipo. */
    protected void cotizacion(String moneda, String casa, String compra, String venta, Instant obtenidaEn) {
        jdbc.update("delete from cotizacion where moneda = ? and casa = ?", moneda, casa);
        cotizaciones.saveAndFlush(new Cotizacion(moneda, casa, "Prueba " + casa,
                compra == null ? null : new BigDecimal(compra), venta == null ? null : new BigDecimal(venta),
                "DOLARAPI", obtenidaEn, obtenidaEn));
    }

    protected void borrarCotizaciones(String prefijoDeCasa) {
        jdbc.update("delete from cotizacion where casa like ?", prefijoDeCasa + "%");
    }

    protected ConfiguracionCotizacion configuracionDe(Long ferreteriaId) {
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        try {
            return configuraciones.findPropiaScoped().orElseThrow();
        } finally {
            TenantContext.clear();
        }
    }

    /** Id del usuario con ese email. */
    protected Long usuarioId(String email) {
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }

    /** Inserta un articulo sin rubro (IVA 21, margen de la ferreteria) con el precio final ya calculado que se indica. */
    protected Long articulo(Long ferreteriaId, String codigo, String moneda, String costo, String precioFinal,
                            boolean activo) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, moneda, precio_final, activo) "
                + "values (?, ?, ?, ?, ?, ?, ?)", ferreteriaId, codigo, "Articulo " + codigo, new BigDecimal(costo),
                moneda, new BigDecimal(precioFinal), activo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigo);
    }

    protected Long articuloUsd(Long ferreteriaId, String codigo, String costo, String precioFinal) {
        return articulo(ferreteriaId, codigo, "USD", costo, precioFinal, true);
    }

    protected BigDecimal precioDe(Long articuloId) {
        return jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class, articuloId);
    }

    protected List<Map<String, Object>> historialDe(Long articuloId) {
        return jdbc.queryForList("select origen, moneda, usuario_id, costo_anterior, costo_nuevo, precio_anterior, "
                + "precio_nuevo from historial_precio where articulo_id = ? order by id", articuloId);
    }

    protected long historialDeLaFerreteria(Long ferreteriaId) {
        return jdbc.queryForObject("select count(*) from historial_precio where ferreteria_id = ?", Long.class,
                ferreteriaId);
    }

    /** Borra lo que los tests siembran de esas ferreterias (historial, articulos, importaciones, proveedores, rubros, configuracion y usuarios). */
    protected void borrarDatosDe(Long... ferreteriaIds) {
        for (Long id : ferreteriaIds) {
            jdbc.update("delete from historial_precio where ferreteria_id = ?", id);
            jdbc.update("delete from articulo where ferreteria_id = ?", id);
            jdbc.update("delete from importacion_lista where ferreteria_id = ?", id);
            jdbc.update("delete from proveedor where ferreteria_id = ?", id);
            jdbc.update("delete from rubro where ferreteria_id = ?", id);
            jdbc.update("delete from configuracion_cotizacion where ferreteria_id = ?", id);
            // CU-10: la caja apunta a los usuarios; las ventas que quedaran se sueltan de la caja antes de borrarla.
            jdbc.update("update comprobante set caja_id = null where ferreteria_id = ?", id);
            jdbc.update("delete from movimiento_caja where ferreteria_id = ?", id);
            jdbc.update("delete from caja where ferreteria_id = ?", id);
            jdbc.update("delete from usuario where ferreteria_id = ?", id);
        }
    }
}
