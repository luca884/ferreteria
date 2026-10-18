package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.Caja;
import org.springframework.data.domain.Page;

import java.util.Map;

/** Una pagina de cajas cerradas y el nombre de quien abrio y cerro cada una (por id). */
public record HistorialCajas(Page<Caja> pagina, Map<Long, String> nombres) {}
