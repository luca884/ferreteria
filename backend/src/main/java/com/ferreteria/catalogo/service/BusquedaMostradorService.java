package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.ArticuloMostradorResponse;
import com.ferreteria.catalogo.dto.BusquedaMostradorResponse;
import com.ferreteria.catalogo.dto.TipoResultadoMostrador;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ConsultaMostrador;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Buscar un articulo en el mostrador (CU-06): primero el codigo exacto, despues el dado de baja y al final las palabras. */
@Service
public class BusquedaMostradorService {

    /** Filas que se devuelven en una lista; se piden LIMITE + 1 para saber si habia mas sin contar. */
    static final int LIMITE = 20;

    private final ArticuloRepository articuloRepository;

    public BusquedaMostradorService(ArticuloRepository articuloRepository) {
        this.articuloRepository = articuloRepository;
    }

    @Transactional(readOnly = true)
    public BusquedaMostradorResponse buscar(String q) {
        // Antes que nada: sin ferreteria (super admin) falla aunque q este en blanco.
        TenantContext.requireFerreteriaId();
        ConsultaMostrador consulta = ConsultaMostrador.de(q);
        if (consulta.vacia()) {
            return sinItems(TipoResultadoMostrador.SIN_RESULTADOS);
        }
        List<Articulo> exactos = articuloRepository.exactosActivosScoped(consulta.codigo(), LIMITE + 1);
        if (exactos.size() == 1) {
            return new BusquedaMostradorResponse(
                    TipoResultadoMostrador.EXACTO, List.of(ArticuloMostradorResponse.from(exactos.get(0))), false);
        }
        // El codigo de barras no es unico: varios exactos van a la lista.
        if (!exactos.isEmpty()) {
            return lista(exactos);
        }
        // Un codigo exacto de un articulo dado de baja gana sobre las coincidencias parciales.
        if (articuloRepository.hayDadoDeBajaConCodigoScoped(consulta.codigo())) {
            return sinItems(TipoResultadoMostrador.DADO_DE_BAJA);
        }
        List<Articulo> parciales = articuloRepository.coincidenciasMostradorScoped(consulta, LIMITE + 1);
        if (parciales.isEmpty()) {
            return sinItems(TipoResultadoMostrador.SIN_RESULTADOS);
        }
        // Aunque sea una sola fila es LISTA: la ficha directa es solo para el codigo exacto.
        return lista(parciales);
    }

    private static BusquedaMostradorResponse sinItems(TipoResultadoMostrador tipo) {
        return new BusquedaMostradorResponse(tipo, List.of(), false);
    }

    private static BusquedaMostradorResponse lista(List<Articulo> filas) {
        List<ArticuloMostradorResponse> items = filas.stream()
                .limit(LIMITE).map(ArticuloMostradorResponse::from).toList();
        return new BusquedaMostradorResponse(TipoResultadoMostrador.LISTA, items, filas.size() > LIMITE);
    }
}
