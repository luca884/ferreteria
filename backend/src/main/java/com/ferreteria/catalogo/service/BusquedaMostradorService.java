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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Buscar un articulo en el mostrador (CU-06): primero el codigo exacto de un activo y despues las palabras. Los dados
 * de baja salen en la lista, marcados y al final: el aviso de baja lo da el mostrador al querer agregarlos al ticket.
 */
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
            return new BusquedaMostradorResponse(TipoResultadoMostrador.EXACTO, items(exactos), false);
        }
        // El codigo de barras no es unico: varios exactos van a la lista.
        if (!exactos.isEmpty()) {
            return lista(exactos);
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

    private BusquedaMostradorResponse lista(List<Articulo> filas) {
        return new BusquedaMostradorResponse(TipoResultadoMostrador.LISTA, items(filas), filas.size() > LIMITE);
    }

    /** Hasta LIMITE filas, cada una con su «costo al» (GDS-05), que sale de una sola consulta. */
    private List<ArticuloMostradorResponse> items(List<Articulo> filas) {
        List<Articulo> mostradas = filas.stream().limit(LIMITE).toList();
        Map<Long, LocalDate> costoAl = articuloRepository.costoAlScoped(mostradas.stream().map(Articulo::getId).toList());
        return mostradas.stream().map(a -> ArticuloMostradorResponse.from(a, costoAl.get(a.getId()))).toList();
    }
}
