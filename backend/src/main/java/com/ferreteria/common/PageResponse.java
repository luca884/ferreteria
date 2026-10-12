package com.ferreteria.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Pagina de resultados con una forma JSON estable (no se serializa el PageImpl de Spring). */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
