package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proveedores por HTTP contra MySQL real: alta, edicion, baja logica, lectura, aislamiento entre
 * ferreterias y permisos por rol. A = El Tornillo, B = La Tuerca (db/dev-data). Cada test crea sus
 * proveedores con nombres unicos porque la base se comparte y no se revierte.
 */
class ProveedorIntegrationTest extends IntegrationTestBase {

    private String duenoA;
    private String duenoB;
    private String vendedorA;

    @BeforeEach
    void tokens() throws Exception {
        duenoA = login("dueno@tornillo.demo");
        duenoB = login("dueno@tuerca.demo");
        vendedorA = login("vendedor@tornillo.demo");
    }

    private String json(String nombre, String cuit) throws Exception {
        var body = objectMapper.createObjectNode().put("nombre", nombre);
        if (cuit != null) {
            body.put("cuit", cuit);
        }
        return objectMapper.writeValueAsString(body);
    }

    /** Crea un proveedor por la API y devuelve su respuesta. */
    private JsonNode crear(String token, String nombre) throws Exception {
        String body = postJson("/api/proveedores", token, json(nombre, null))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private boolean activo(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select activo from proveedor where id = ?", Boolean.class, id));
    }

    private String nombreEnBase(long id) {
        return jdbc.queryForObject("select nombre from proveedor where id = ?", String.class, id);
    }

    // --- Alta ---

    @Test
    void altaCorrectaDevuelve201ActivoYSinCuit() throws Exception {
        String nombre = unico("Sinteplast");

        postJson("/api/proveedores", duenoA, json(nombre, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre", is(nombre)))
                .andExpect(jsonPath("$.activo", is(true)))
                .andExpect(jsonPath("$.cuit").doesNotExist());

        assertThat(nombres(getJson("/api/proveedores", duenoA))).contains(nombre);
    }

    @Test
    void altaGuardaElCuitYLaFerreteriaDelToken() throws Exception {
        String nombre = unico("Tersuave");

        JsonNode creado = objectMapper.readTree(postJson("/api/proveedores", duenoB, json(nombre, "30-12345678-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cuit", is("30-12345678-1")))
                .andReturn().getResponse().getContentAsString());

        Long ferreteriaId = jdbc.queryForObject(
                "select ferreteria_id from proveedor where id = ?", Long.class, creado.get("id").asLong());
        Long ferreteriaDelDuenoB = jdbc.queryForObject(
                "select ferreteria_id from usuario where email = 'dueno@tuerca.demo'", Long.class);
        assertThat(ferreteriaId).isEqualTo(ferreteriaDelDuenoB);
    }

    @Test
    void nombreDuplicadoEnLaMismaFerreteriaDa409ConElMensajeDelSpec() throws Exception {
        String nombre = unico("Duplicado");
        crear(duenoA, nombre);

        postJson("/api/proveedores", duenoA, json("  " + nombre + " ", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Ya existe un proveedor llamado " + nombre + ".")));
    }

    @Test
    void elMismoNombreEnOtraFerreteriaSePuedeCrear() throws Exception {
        String nombre = unico("Compartido");
        crear(duenoA, nombre);

        postJson("/api/proveedores", duenoB, json(nombre, null))
                .andExpect(status().isCreated());
    }

    @Test
    void nombreEnBlancoDa400YNoCreaNada() throws Exception {
        long antes = jdbc.queryForObject("select count(*) from proveedor", Long.class);

        postJson("/api/proveedores", duenoA, json("   ", null))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from proveedor", Long.class)).isEqualTo(antes);
    }

    @Test
    void cuitConFormatoInvalidoDa400() throws Exception {
        postJson("/api/proveedores", duenoA, json(unico("CuitMalo"), "123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cuit", is("El CUIT tiene que tener 11 números, por ejemplo 30-12345678-1.")));
    }

    @Test
    void cuitConDigitoVerificadorInvalidoDa400YNoCreaNada() throws Exception {
        String nombre = unico("CuitDv");

        postJson("/api/proveedores", duenoA, json(nombre, "30-12345678-9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cuit", is("El CUIT no es válido: revisá los números, el último no coincide.")));

        assertThat(jdbc.queryForObject("select count(*) from proveedor where nombre = ?", Long.class, nombre)).isZero();
    }

    @Test
    void cuitSinGuionesSeGuardaConGuiones() throws Exception {
        postJson("/api/proveedores", duenoA, json(unico("CuitSinGuiones"), "30876543210"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cuit", is("30-87654321-0")));
    }

    @Test
    void nombreDuplicadoDeUnoDadoDeBajaLoDiceEnElMensaje() throws Exception {
        String nombre = unico("DeBaja");
        long id = crear(duenoA, nombre).get("id").asLong();
        delete("/api/proveedores/" + id, duenoA).andExpect(status().isNoContent());

        postJson("/api/proveedores", duenoA, json(nombre.toLowerCase(), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Ya existe un proveedor llamado " + nombre
                        + ", pero está dado de baja. Reactivalo para volver a usarlo.")));
    }

    // --- Dados de baja y reactivacion ---

    @Test
    void conIncluirInactivosElListadoTraeLosDadosDeBajaSoloDeLaFerreteriaDelToken() throws Exception {
        String bajaA = unico("InactivoA");
        String activoA = unico("ActivoA");
        String bajaB = unico("InactivoB");
        long idA = crear(duenoA, bajaA).get("id").asLong();
        crear(duenoA, activoA);
        long idB = crear(duenoB, bajaB).get("id").asLong();
        delete("/api/proveedores/" + idA, duenoA).andExpect(status().isNoContent());
        delete("/api/proveedores/" + idB, duenoB).andExpect(status().isNoContent());

        JsonNode lista = getJson("/api/proveedores?incluirInactivos=true", duenoA);

        assertThat(nombres(lista)).contains(bajaA, activoA).doesNotContain(bajaB);
        lista.forEach(p -> {
            if (p.get("nombre").asText().equals(bajaA)) assertThat(p.get("activo").asBoolean()).isFalse();
        });
    }

    @Test
    void reactivarUnProveedorDadoDeBajaLoVuelveAlListado() throws Exception {
        String nombre = unico("Reactivable");
        long id = crear(duenoA, nombre).get("id").asLong();
        delete("/api/proveedores/" + id, duenoA).andExpect(status().isNoContent());

        postJson("/api/proveedores/" + id + "/reactivacion", duenoA, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo", is(true)))
                .andExpect(jsonPath("$.nombre", is(nombre)));

        assertThat(activo(id)).isTrue();
        assertThat(nombres(getJson("/api/proveedores", duenoA))).contains(nombre);
    }

    @Test
    void reactivarUnProveedorDeOtraFerreteriaDa404YNoLoCambia() throws Exception {
        long idDeB = crear(duenoB, unico("ReactivarAjeno")).get("id").asLong();
        delete("/api/proveedores/" + idDeB, duenoB).andExpect(status().isNoContent());

        postJson("/api/proveedores/" + idDeB + "/reactivacion", duenoA, "")
                .andExpect(status().isNotFound());

        assertThat(activo(idDeB)).isFalse();
    }

    @Test
    void elVendedorNoPuedeReactivar() throws Exception {
        long id = crear(duenoA, unico("ReactivarVendedor")).get("id").asLong();
        delete("/api/proveedores/" + id, duenoA).andExpect(status().isNoContent());

        postJson("/api/proveedores/" + id + "/reactivacion", vendedorA, "")
                .andExpect(status().isForbidden());

        assertThat(activo(id)).isFalse();
    }

    // --- Edicion ---

    @Test
    void edicionCorrectaDevuelve200ConLosDatosNuevos() throws Exception {
        String original = unico("Editable");
        String nuevo = unico("EditableSA");
        long id = crear(duenoA, original).get("id").asLong();

        putJson("/api/proveedores/" + id, duenoA, json(nuevo, "30-12345678-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre", is(nuevo)))
                .andExpect(jsonPath("$.cuit", is("30-12345678-1")));

        assertThat(nombreEnBase(id)).isEqualTo(nuevo);
    }

    @Test
    void guardarSinCambiarElNombreNoChocaConUnoMismo() throws Exception {
        String nombre = unico("MismoNombre");
        long id = crear(duenoA, nombre).get("id").asLong();

        putJson("/api/proveedores/" + id, duenoA, json(nombre, "30-87654321-0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cuit", is("30-87654321-0")));
    }

    @Test
    void unPutSinActivoConservaElEstadoYConActivoExplicitoSeAplica() throws Exception {
        String nombre = unico("ConservaEstado");
        long id = crear(duenoA, nombre).get("id").asLong();
        delete("/api/proveedores/" + id, duenoA).andExpect(status().isNoContent());
        assertThat(activo(id)).isFalse();

        // json(...) no manda "activo": el proveedor dado de baja no se reactiva.
        putJson("/api/proveedores/" + id, duenoA, json(nombre, "30-12345678-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo", is(false)))
                .andExpect(jsonPath("$.cuit", is("30-12345678-1")));
        assertThat(activo(id)).isFalse();

        var conActivo = objectMapper.createObjectNode().put("nombre", nombre).put("activo", true);
        putJson("/api/proveedores/" + id, duenoA, objectMapper.writeValueAsString(conActivo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo", is(true)));
        assertThat(activo(id)).isTrue();

        // Activo y sin "activo": sigue activo. Con false explicito, se da de baja.
        putJson("/api/proveedores/" + id, duenoA, json(nombre, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo", is(true)));
        var sinActivo = objectMapper.createObjectNode().put("nombre", nombre).put("activo", false);
        putJson("/api/proveedores/" + id, duenoA, objectMapper.writeValueAsString(sinActivo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo", is(false)));
    }

    @Test
    void renombrarAUnNombreOcupadoPorOtroProveedorDa409() throws Exception {
        String ocupado = unico("Ocupado");
        crear(duenoA, ocupado);
        String propio = unico("Propio");
        long id = crear(duenoA, propio).get("id").asLong();

        putJson("/api/proveedores/" + id, duenoA, json(ocupado, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Ya existe un proveedor llamado " + ocupado + ".")));

        assertThat(nombreEnBase(id)).isEqualTo(propio);
    }

    // --- Lectura y aislamiento ---

    @Test
    void elListadoMuestraSoloLosProveedoresActivosDeLaFerreteriaDelToken() throws Exception {
        String a1 = unico("ListaA1");
        String a2 = unico("ListaA2");
        String b1 = unico("ListaB1");
        String baja = unico("ListaBaja");
        crear(duenoA, a1);
        crear(duenoA, a2);
        crear(duenoB, b1);
        long idBaja = crear(duenoA, baja).get("id").asLong();
        delete("/api/proveedores/" + idBaja, duenoA).andExpect(status().isNoContent());

        assertThat(nombres(getJson("/api/proveedores", duenoA)))
                .contains(a1, a2)
                .doesNotContain(b1, baja);
        assertThat(nombres(getJson("/api/proveedores", duenoB)))
                .contains(b1)
                .doesNotContain(a1, a2, baja);
    }

    @Test
    void elDetalleDeUnProveedorPropioDa200() throws Exception {
        String nombre = unico("Detalle");
        long id = crear(duenoA, nombre).get("id").asLong();

        getResult("/api/proveedores/" + id, duenoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is((int) id)))
                .andExpect(jsonPath("$.nombre", is(nombre)));
    }

    @Test
    void elDetalleDeUnProveedorDeOtraFerreteriaDa404() throws Exception {
        long idDeB = crear(duenoB, unico("DetalleAjeno")).get("id").asLong();

        getResult("/api/proveedores/" + idDeB, duenoA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Proveedor no encontrado")));
    }

    @Test
    void unProveedorInexistenteDa404() throws Exception {
        getResult("/api/proveedores/999999999", duenoA).andExpect(status().isNotFound());
    }

    @Test
    void editarUnProveedorDeOtraFerreteriaDa404YNoLoCambia() throws Exception {
        String nombre = unico("EditarAjeno");
        long idDeB = crear(duenoB, nombre).get("id").asLong();

        putJson("/api/proveedores/" + idDeB, duenoA, json(unico("Hackeado"), null))
                .andExpect(status().isNotFound());

        assertThat(nombreEnBase(idDeB)).isEqualTo(nombre);
    }

    @Test
    void darDeBajaUnProveedorDeOtraFerreteriaDa404YNoLoCambia() throws Exception {
        long idDeB = crear(duenoB, unico("BajaAjena")).get("id").asLong();

        delete("/api/proveedores/" + idDeB, duenoA).andExpect(status().isNotFound());

        assertThat(activo(idDeB)).isTrue();
    }

    @Test
    void sinTokenDa401() throws Exception {
        mvc.perform(get("/api/proveedores"))
                .andExpect(status().isUnauthorized());
    }

    // --- Baja logica ---

    @Test
    void bajaLogicaDevuelve204ConservaLaFilaYLosArticulos() throws Exception {
        long id = crear(duenoA, unico("ConArticulos")).get("id").asLong();
        Long ferreteriaId = jdbc.queryForObject("select ferreteria_id from proveedor where id = ?", Long.class, id);
        String codigo = unico("PRV");
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, proveedor_id, costo, precio_final) "
                + "values (?, ?, 'Articulo del proveedor', ?, 100.00, 170.00)", ferreteriaId, codigo, id);

        delete("/api/proveedores/" + id, duenoA).andExpect(status().isNoContent());

        assertThat(activo(id)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from proveedor where id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select proveedor_id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigo)).isEqualTo(id);
    }

    // --- Configuracion de la lista (CU-04): solo la fija una importacion aplicada ---

    private static final String MAPEO_JSON = "{\"hoja\":0,\"filaDesde\":2,\"columnaCodigo\":0,"
            + "\"columnaDescripcion\":1,\"columnaCosto\":2,"
            + "\"encabezados\":{\"codigo\":\"Codigo\",\"descripcion\":\"Detalle\",\"costo\":\"Precio\"}}";

    /** Carga a mano lo que despues dejaria una importacion: mapeo, IVA incluido y bonificacion 40 + 10. */
    private void cargarLista(long id) {
        jdbc.update("update proveedor set mapeo_lista = ?, lista_incluye_iva = true, "
                + "bonificacion1 = 40.00, bonificacion2 = 10.00 where id = ?", MAPEO_JSON, id);
    }

    @Test
    void unProveedorNuevoNoTieneListaConfigurada() throws Exception {
        long id = crear(duenoA, unico("SinLista")).get("id").asLong();

        getResult("/api/proveedores/" + id, duenoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapeoLista").doesNotExist())
                .andExpect(jsonPath("$.listaIncluyeIva", is(false)))
                .andExpect(jsonPath("$.bonificacion1").doesNotExist())
                .andExpect(jsonPath("$.bonificacion2").doesNotExist())
                .andExpect(jsonPath("$.bonificacion3").doesNotExist());
    }

    @Test
    void elAltaIgnoraLosCamposDeLaListaDelCuerpo() throws Exception {
        var body = objectMapper.createObjectNode()
                .put("nombre", unico("AltaConLista"))
                .put("listaIncluyeIva", true)
                .put("bonificacion1", 25);

        long id = objectMapper.readTree(postJson("/api/proveedores", duenoA, objectMapper.writeValueAsString(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.listaIncluyeIva", is(false)))
                .andExpect(jsonPath("$.bonificacion1").doesNotExist())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        assertThat(jdbc.queryForObject("select lista_incluye_iva from proveedor where id = ?", Boolean.class, id))
                .isFalse();
        assertThat(jdbc.queryForObject("select bonificacion1 from proveedor where id = ?", BigDecimal.class, id))
                .isNull();
    }

    @Test
    void elPutIgnoraLosCamposDeLaListaYConservaLoGuardado() throws Exception {
        String nombre = unico("PutConLista");
        long id = crear(duenoA, nombre).get("id").asLong();
        cargarLista(id);
        var body = objectMapper.createObjectNode()
                .put("nombre", nombre)
                .put("bonificacion1", 99)
                .put("listaIncluyeIva", false);

        putJson("/api/proveedores/" + id, duenoA, objectMapper.writeValueAsString(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bonificacion1", is(40.0)))
                .andExpect(jsonPath("$.listaIncluyeIva", is(true)))
                .andExpect(jsonPath("$.mapeoLista.columnaCosto", is(2)));

        assertThat(jdbc.queryForObject("select bonificacion1 from proveedor where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("40.00");
        assertThat(objectMapper.readTree(
                jdbc.queryForObject("select mapeo_lista from proveedor where id = ?", String.class, id)))
                .isEqualTo(objectMapper.readTree(MAPEO_JSON));
    }

    @Test
    void elVendedorLeeTodosLosCamposDeLaLista() throws Exception {
        long id = crear(duenoA, unico("VendedorLista")).get("id").asLong();
        cargarLista(id);

        getResult("/api/proveedores/" + id, vendedorA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapeoLista.hoja", is(0)))
                .andExpect(jsonPath("$.mapeoLista.filaDesde", is(2)))
                .andExpect(jsonPath("$.mapeoLista.columnaCodigo", is(0)))
                .andExpect(jsonPath("$.mapeoLista.columnaDescripcion", is(1)))
                .andExpect(jsonPath("$.mapeoLista.columnaCosto", is(2)))
                .andExpect(jsonPath("$.mapeoLista.encabezados.costo", is("Precio")))
                .andExpect(jsonPath("$.listaIncluyeIva", is(true)))
                .andExpect(jsonPath("$.bonificacion1", is(40.0)))
                .andExpect(jsonPath("$.bonificacion2", is(10.0)))
                .andExpect(jsonPath("$.bonificacion3").doesNotExist());
    }

    @Test
    void laListaDeUnProveedorDeOtraFerreteriaDa404() throws Exception {
        long idDeB = crear(duenoB, unico("ListaAjena")).get("id").asLong();
        cargarLista(idDeB);

        getResult("/api/proveedores/" + idDeB, duenoA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mapeoLista").doesNotExist());
    }

    @Test
    void laBajaLogicaConservaElMapeoYLasBonificaciones() throws Exception {
        long id = crear(duenoA, unico("BajaConLista")).get("id").asLong();
        cargarLista(id);

        delete("/api/proveedores/" + id, duenoA).andExpect(status().isNoContent());

        assertThat(activo(id)).isFalse();
        assertThat(jdbc.queryForObject("select mapeo_lista is not null from proveedor where id = ?", Boolean.class, id))
                .isTrue();
        assertThat(jdbc.queryForObject("select bonificacion1 from proveedor where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("40.00");
        assertThat(jdbc.queryForObject("select bonificacion2 from proveedor where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("10.00");
    }

    // --- Permisos por rol ---

    @Test
    void elVendedorNoPuedeCrearProveedores() throws Exception {
        String nombre = unico("VendedorAlta");

        postJson("/api/proveedores", vendedorA, json(nombre, null))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select count(*) from proveedor where nombre = ?", Long.class, nombre))
                .isZero();
    }

    @Test
    void elVendedorNoPuedeEditarNiDarDeBajaYElProveedorNoCambia() throws Exception {
        String nombre = unico("VendedorEdita");
        long id = crear(duenoA, nombre).get("id").asLong();

        putJson("/api/proveedores/" + id, vendedorA, json(unico("Cambiado"), null))
                .andExpect(status().isForbidden());
        delete("/api/proveedores/" + id, vendedorA).andExpect(status().isForbidden());

        assertThat(nombreEnBase(id)).isEqualTo(nombre);
        assertThat(activo(id)).isTrue();
    }

    @Test
    void elVendedorPuedeLeerElListadoYElDetalle() throws Exception {
        String nombre = unico("VendedorLee");
        long id = crear(duenoA, nombre).get("id").asLong();

        assertThat(nombres(getJson("/api/proveedores", vendedorA))).contains(nombre);
        getResult("/api/proveedores/" + id, vendedorA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre", is(nombre)));
    }
}
