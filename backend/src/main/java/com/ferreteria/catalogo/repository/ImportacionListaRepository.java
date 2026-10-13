package com.ferreteria.catalogo.repository;

import com.ferreteria.catalogo.model.ImportacionLista;
import com.ferreteria.tenant.TenantScopedRepository;

/** Solo se usa save: las importaciones se registran al aplicar y todavia no se listan. */
public interface ImportacionListaRepository extends TenantScopedRepository<ImportacionLista, Long> {}
