package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.ConteosImportacion;
import com.ferreteria.catalogo.model.EstadoImportacion;
import com.ferreteria.catalogo.model.ImportacionLista;
import com.ferreteria.catalogo.repository.ImportacionListaRepository;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.MissingTenantException;
import com.ferreteria.tenant.TenantContext;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ImportacionListaRepository contra MySQL real: se guarda con save y se lee solo con los metodos
 * *Scoped, asi que una ferreteria nunca ve las importaciones de otra.
 */
class ImportacionListaRepositoryTest extends IntegrationTestBase {

    private static final ConteosImportacion CONTEOS = new ConteosImportacion(4, 2, 1, 3, 5, 6);

    @Autowired
    ImportacionListaRepository repository;

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private Map<String, Object> usuario(String email) {
        return jdbc.queryForMap("select id, ferreteria_id from usuario where email = ?", email);
    }

    private Long proveedor(Long ferreteriaId) {
        String nombre = unico("Prov importacion");
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, ?)", ferreteriaId, nombre);
        return jdbc.queryForObject(
                "select id from proveedor where ferreteria_id = ? and nombre = ?", Long.class, ferreteriaId, nombre);
    }

    private ImportacionLista guardarComo(String email, String archivo) {
        Map<String, Object> usuario = usuario(email);
        Long ferreteriaId = ((Number) usuario.get("ferreteria_id")).longValue();
        Long usuarioId = ((Number) usuario.get("id")).longValue();
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        return repository.save(
                ImportacionLista.aplicada(ferreteriaId, proveedor(ferreteriaId), usuarioId, archivo, CONTEOS));
    }

    @Test
    void guardaUnaImportacionAplicadaConSusContadoresYElEstado() {
        ImportacionLista guardada = guardarComo("dueno@tornillo.demo", "lista-enero.xlsx");

        ImportacionLista leida = repository.findScoped(guardada.getId()).orElseThrow();

        assertThat(leida.getArchivo()).isEqualTo("lista-enero.xlsx");
        assertThat(leida.getEstado()).isEqualTo(EstadoImportacion.APLICADA);
        assertThat(leida.getSuben()).isEqualTo(4);
        assertThat(leida.getBajan()).isEqualTo(2);
        assertThat(leida.getNuevos()).isEqualTo(1);
        assertThat(leida.getSinCambio()).isEqualTo(3);
        assertThat(leida.getNoEstan()).isEqualTo(5);
        assertThat(leida.getConError()).isEqualTo(6);
        assertThat(leida.getFecha()).isNotNull();
        assertThat(jdbc.queryForObject(
                "select estado from importacion_lista where id = ?", String.class, guardada.getId()))
                .isEqualTo("APLICADA");
    }

    @Test
    void guardaUnNombreDeArchivoDe255Caracteres() {
        String nombre = "a".repeat(251) + ".csv";

        ImportacionLista guardada = guardarComo("dueno@tornillo.demo", nombre);

        assertThat(nombre).hasSize(255);
        assertThat(repository.findScoped(guardada.getId()).orElseThrow().getArchivo()).isEqualTo(nombre);
    }

    @Test
    void otraFerreteriaNoLaVePorIdNiEnElListado() {
        ImportacionLista deA = guardarComo("dueno@tornillo.demo", "solo-de-a.xlsx");
        Long ferreteriaB = ((Number) usuario("dueno@tuerca.demo").get("ferreteria_id")).longValue();

        TenantContext.setCurrentFerreteriaId(ferreteriaB);

        assertThat(repository.findScoped(deA.getId())).isEmpty();
        assertThat(repository.findAllScoped()).extracting(ImportacionLista::getId).doesNotContain(deA.getId());
        assertThat(repository.findAllScoped()).allMatch(i -> i.getFerreteriaId().equals(ferreteriaB));
    }

    @Test
    void cadaFerreteriaVeSoloLasSuyas() {
        ImportacionLista deA = guardarComo("dueno@tornillo.demo", "de-a.xlsx");
        ImportacionLista deB = guardarComo("dueno@tuerca.demo", "de-b.xlsx");
        Long ferreteriaA = deA.getFerreteriaId();

        TenantContext.setCurrentFerreteriaId(ferreteriaA);
        assertThat(repository.findScoped(deA.getId())).isPresent();
        assertThat(repository.findScoped(deB.getId())).isEmpty();
        assertThat(repository.findAllScoped()).extracting(ImportacionLista::getId)
                .contains(deA.getId()).doesNotContain(deB.getId());

        TenantContext.setCurrentFerreteriaId(deB.getFerreteriaId());
        assertThat(repository.findScoped(deB.getId())).isPresent();
        assertThat(repository.findScoped(deA.getId())).isEmpty();
    }

    @Test
    void sinTenantLosMetodosScopedFallan() {
        TenantContext.clear();

        assertThatThrownBy(() -> repository.findScoped(1L)).isInstanceOf(MissingTenantException.class);
        assertThatThrownBy(() -> repository.findAllScoped()).isInstanceOf(MissingTenantException.class);
    }
}
