package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.MapeoLista;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * El JSON de proveedor.mapeo_lista contra MySQL real: los mapeos viejos se leen y se vuelven a guardar sin inventar
 * campos (R-110), y el rollback del corte 2 necesita limpiar las claves nuevas antes de volver al codigo anterior.
 * CUIT: bloque 30-7812xxxx.
 */
class MapeoListaPersistenciaIntegrationTest extends IntegrationTestBase {

    /** El JSON de dev-data V1002__importacion_demo.sql: lo que guardo el codigo anterior al corte 2. */
    private static final String JSON_V1002 = "{\"hoja\": 0, \"filaDesde\": 2, \"columnaCodigo\": 0, "
        + "\"columnaDescripcion\": 1, \"columnaCosto\": 2, \"encabezados\": {\"codigo\": \"Codigo\", "
        + "\"descripcion\": \"Descripcion\", \"costo\": \"Precio\"}}";

    /** El MapeoLista de antes del corte 2, copiado: sin ignoreUnknown, como lo lee el codigo viejo. */
    record MapeoListaV5(int hoja, int filaDesde, int columnaCodigo, Integer columnaDescripcion, int columnaCosto,
                        EncabezadosV5 encabezados) {}

    record EncabezadosV5(String codigo, String descripcion, String costo) {}

    @Autowired
    ProveedorRepository proveedorRepository;

    @AfterEach
    void limpiarElTenant() {
        TenantContext.clear();
    }

    private Long ferreteriaId(String cuit, String razonSocial) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
            + "values (?, ?, 'RESPONSABLE_INSCRIPTO') on duplicate key update razon_social = razon_social",
            razonSocial, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long proveedorConMapeo(Long ferreteriaId, String nombre, String mapeoJson) {
        jdbc.update("insert into proveedor (ferreteria_id, nombre, mapeo_lista) values (?, ?, ?)",
            ferreteriaId, nombre, mapeoJson);
        return jdbc.queryForObject("select id from proveedor where ferreteria_id = ? and nombre = ?", Long.class,
            ferreteriaId, nombre);
    }

    private JsonNode mapeoCrudo(Long proveedorId) throws IOException {
        String json = jdbc.queryForObject("select mapeo_lista from proveedor where id = ?", String.class, proveedorId);
        return objectMapper.readTree(json);
    }

    // ---- compatibilidad ----

    @Test
    @DisplayName("un proveedor con el JSON viejo de V1002 se lee por el repositorio con los campos nuevos en null")
    void leeElMapeoViejo() {
        Long f = ferreteriaId("30-78120001-1", "Mapeo persistencia lectura");
        Long proveedorId = proveedorConMapeo(f, "Proveedor mapeo viejo", JSON_V1002);
        TenantContext.setCurrentFerreteriaId(f);

        MapeoLista mapeo = proveedorRepository.findScoped(proveedorId).orElseThrow().getMapeoLista();

        assertThat(mapeo.hoja()).isZero();
        assertThat(mapeo.filaDesde()).isEqualTo(2);
        assertThat(mapeo.columnaCodigo()).isZero();
        assertThat(mapeo.columnaDescripcion()).isEqualTo(1);
        assertThat(mapeo.columnaCosto()).isEqualTo(2);
        assertThat(mapeo.encabezados()).isEqualTo(new MapeoLista.Encabezados("Codigo", "Descripcion", "Precio"));
        assertThat(mapeo.hojas()).isNull();
        assertThat(mapeo.hojasElegidas()).containsExactly(0);
        assertThat(mapeo.columnaMoneda()).isNull();
        assertThat(mapeo.monedaFija()).isNull();
        assertThat(mapeo.columnaSinIva()).isNull();
        assertThat(mapeo.columnaConIva()).isNull();
        assertThat(mapeo.columnaIva()).isNull();
        assertThat(mapeo.columnaAjuste1()).isNull();
        assertThat(mapeo.columnaCodigoBarras()).isNull();
        assertThat(mapeo.columnaUnidad()).isNull();
        assertThat(mapeo.columnaRubro()).isNull();
    }

    @Test
    @DisplayName("volver a guardar el mapeo viejo conserva los campos viejos y no inventa los nuevos")
    void guardarElMapeoViejoNoInventaCampos() throws Exception {
        Long f = ferreteriaId("30-78120002-2", "Mapeo persistencia reescritura");
        Long proveedorId = proveedorConMapeo(f, "Proveedor reescrito", JSON_V1002);
        TenantContext.setCurrentFerreteriaId(f);
        Proveedor proveedor = proveedorRepository.findScoped(proveedorId).orElseThrow();

        // listaIncluyeIva cambia para que haya un UPDATE de verdad (Hibernate reescribe la fila entera).
        proveedor.configurarLista(proveedor.getMapeoLista(), true, Bonificaciones.NINGUNA);
        proveedorRepository.save(proveedor);

        JsonNode guardado = mapeoCrudo(proveedorId);
        assertThat(guardado).isEqualTo(objectMapper.readTree(JSON_V1002));
        assertThat(guardado.has("hojas")).isFalse();
        assertThat(guardado.has("columnaMoneda")).isFalse();
        assertThat(guardado.get("encabezados").has("moneda")).isFalse();
    }

    @Test
    @DisplayName("un mapeo nuevo con los campos del corte 2 se guarda y se relee entero")
    void guardaYReleeElMapeoNuevo() throws Exception {
        Long f = ferreteriaId("30-78120003-3", "Mapeo persistencia nuevo");
        Long proveedorId = proveedorConMapeo(f, "Proveedor mapeo nuevo", JSON_V1002);
        TenantContext.setCurrentFerreteriaId(f);
        Proveedor proveedor = proveedorRepository.findScoped(proveedorId).orElseThrow();
        MapeoLista nuevo = new MapeoLista(0, 2, 0, 1, 5,
            new MapeoLista.Encabezados("Código", "Artículo", "Lista (unidad)", "Mon.", "Sin IVA", "Con IVA", null,
                null, null, null, null, null, null),
            List.of(0, 1, 4), 4, null, 7, 8, null, null, null, null, null, null, null);

        proveedor.configurarLista(nuevo, true, Bonificaciones.NINGUNA);
        proveedorRepository.save(proveedor);

        MapeoLista leido = proveedorRepository.findScoped(proveedorId).orElseThrow().getMapeoLista();
        assertThat(leido).isEqualTo(nuevo);
        JsonNode crudo = mapeoCrudo(proveedorId);
        assertThat(crudo.get("hojas")).hasSize(3);
        assertThat(crudo.get("columnaMoneda").asInt()).isEqualTo(4);
        assertThat(crudo.has("monedaFija")).isFalse();
        assertThat(crudo.get("encabezados").get("conIva").asText()).isEqualTo("Con IVA");
    }

    @Test
    @DisplayName("un mapeo con moneda fija USD se guarda como texto y se relee")
    void monedaFijaPersistida() {
        Long f = ferreteriaId("30-78120004-4", "Mapeo persistencia moneda fija");
        Long proveedorId = proveedorConMapeo(f, "Proveedor moneda fija", JSON_V1002);
        TenantContext.setCurrentFerreteriaId(f);
        Proveedor proveedor = proveedorRepository.findScoped(proveedorId).orElseThrow();
        MapeoLista conMonedaFija = new MapeoLista(0, 2, 0, 1, 2, null, null, null, Moneda.USD, null, null, null, null,
            null, null, null, null, null);

        proveedor.configurarLista(conMonedaFija, false, Bonificaciones.NINGUNA);
        proveedorRepository.save(proveedor);

        assertThat(proveedorRepository.findScoped(proveedorId).orElseThrow().getMapeoLista().monedaFija())
            .isEqualTo(Moneda.USD);
    }

    // ---- rollback ----

    private static String scriptDeRollback() throws IOException {
        return StreamUtils.copyToString(new ClassPathResource("catalogo/rollback-v6-mapeo.sql").getInputStream(),
            java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("el codigo viejo (sin ignoreUnknown) falla al leer un mapeo nuevo; despues del rollback lo lee y los 6 campos siguen")
    void elRollbackLimpiaLasClavesNuevas() throws Exception {
        Long f = ferreteriaId("30-78120005-5", "Mapeo persistencia rollback");
        Long proveedorId = proveedorConMapeo(f, "Proveedor para rollback", JSON_V1002);
        TenantContext.setCurrentFerreteriaId(f);
        Proveedor proveedor = proveedorRepository.findScoped(proveedorId).orElseThrow();
        proveedor.configurarLista(new MapeoLista(0, 2, 0, 1, 5,
            new MapeoLista.Encabezados("Código", "Artículo", "Lista (unidad)", "Mon.", "Sin IVA", "Con IVA", null,
                null, null, null, null, null, null),
            List.of(0, 1), 4, null, 7, 8, null, null, null, null, 9, null, null), true, Bonificaciones.NINGUNA);
        proveedorRepository.save(proveedor);
        String conClavesNuevas = jdbc.queryForObject("select mapeo_lista from proveedor where id = ?", String.class,
            proveedorId);
        // El ObjectMapper por defecto es el de Hibernate: FAIL_ON_UNKNOWN_PROPERTIES = true.
        ObjectMapper deHibernate = new ObjectMapper();

        // Antes de limpiar, el codigo anterior NO puede leerlo (clave de primer nivel y de encabezados).
        assertThatThrownBy(() -> deHibernate.readValue(conClavesNuevas, MapeoListaV5.class))
            .isInstanceOf(UnrecognizedPropertyException.class);

        jdbc.update(scriptDeRollback());

        String limpio = jdbc.queryForObject("select mapeo_lista from proveedor where id = ?", String.class, proveedorId);
        MapeoListaV5 leidoPorElViejo = deHibernate.readValue(limpio, MapeoListaV5.class);
        assertThat(leidoPorElViejo.hoja()).isZero();
        assertThat(leidoPorElViejo.filaDesde()).isEqualTo(2);
        assertThat(leidoPorElViejo.columnaCodigo()).isZero();
        assertThat(leidoPorElViejo.columnaDescripcion()).isEqualTo(1);
        assertThat(leidoPorElViejo.columnaCosto()).isEqualTo(5);
        assertThat(leidoPorElViejo.encabezados())
            .isEqualTo(new EncabezadosV5("Código", "Artículo", "Lista (unidad)"));
    }

    @Test
    @DisplayName("el rollback no toca los proveedores con mapeo NULL ni los de mapeo viejo")
    void elRollbackNoTocaLoQueNoTieneClavesNuevas() throws Exception {
        Long f = ferreteriaId("30-78120006-6", "Mapeo persistencia rollback ajenos");
        Long conMapeoViejo = proveedorConMapeo(f, "Proveedor viejo", JSON_V1002);
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, 'Proveedor sin mapeo')", f);
        Long sinMapeo = jdbc.queryForObject(
            "select id from proveedor where ferreteria_id = ? and nombre = 'Proveedor sin mapeo'", Long.class, f);

        jdbc.update(scriptDeRollback());

        assertThat(mapeoCrudo(conMapeoViejo)).isEqualTo(objectMapper.readTree(JSON_V1002));
        assertThat(jdbc.queryForObject("select mapeo_lista from proveedor where id = ?", String.class, sinMapeo))
            .isNull();
    }
}
