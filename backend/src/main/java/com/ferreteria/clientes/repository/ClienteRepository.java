package com.ferreteria.clientes.repository;

import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface ClienteRepository extends TenantScopedRepository<Cliente, Long> {

    /**
     * Por razón social (contiene) o por documento (empieza con los dígitos). La collation de la tabla (_ai_ci) hace que
     * no distinga mayúsculas ni tildes. {@code patron} ya viene con los comodines y escapado (barra invertida, el escape
     * de LIKE en MySQL); {@code digitos} vacío no busca por documento.
     */
    @Query("select c from Cliente c where c.ferreteriaId = :fid and (:soloActivos = false or c.activo = true) "
            + "and (c.razonSocial like :patron or (:digitos <> '' and c.numeroDocumento like concat(:digitos, '%'))) "
            + "order by c.razonSocial, c.id")
    Page<Cliente> buscar(@Param("fid") Long ferreteriaId, @Param("soloActivos") boolean soloActivos,
            @Param("patron") String patron, @Param("digitos") String digitos, Pageable pageable);

    @Query("select c from Cliente c where c.ferreteriaId = :fid and (:soloActivos = false or c.activo = true) "
            + "order by c.razonSocial, c.id")
    Page<Cliente> todos(@Param("fid") Long ferreteriaId, @Param("soloActivos") boolean soloActivos, Pageable pageable);

    boolean existsByFerreteriaIdAndNumeroDocumento(Long ferreteriaId, String numeroDocumento);

    boolean existsByFerreteriaIdAndNumeroDocumentoAndIdNot(Long ferreteriaId, String numeroDocumento, Long id);

    /** Lo que el bloqueo de la cuenta lee: escalares (con la razón social, que ventas copia al documento), nunca la entidad (ver {@link #bloquearCuenta}). */
    interface CuentaBloqueada {
        String getRazonSocial();

        BigDecimal getSaldo();

        BigDecimal getLimiteCuentaCorriente();

        boolean getActivo();

        /** DNI, CUIT o CUIL, o null sin documento, de la misma lectura actual (la venta lo pide desde el tope, GDS-32). */
        String getTipoDocumento();

        /** Solo dígitos, o null sin documento. Con la condición y el domicilio, lo que la venta facturada copia (D4). */
        String getNumeroDocumento();

        /** El nombre de {@code CondicionIvaCliente}: nunca null. */
        String getCondicionIva();

        String getDomicilio();
    }

    /**
     * Toma la fila del cliente con {@code SELECT … FOR UPDATE} y lee SALDO, LÍMITE y ACTIVO como escalares, con los datos
     * fiscales (condición de IVA, documento y domicilio) en la misma lectura. Es nativa a propósito: un JPQL devolvería la
     * instancia ya gestionada del persistence context (con el saldo de antes), y una lectura plana en REPEATABLE READ vería
     * la foto del inicio de la transacción; el {@code FOR UPDATE} es una lectura actual y trae lo último confirmado.
     * Requiere transacción.
     */
    @Query(value = "select razon_social as razonSocial, saldo as saldo, "
            + "limite_cuenta_corriente as limiteCuentaCorriente, activo as activo, "
            + "tipo_documento as tipoDocumento, numero_documento as numeroDocumento, condicion_iva as condicionIva, "
            + "domicilio as domicilio from cliente where id = :id and ferreteria_id = :fid for update", nativeQuery = true)
    Optional<CuentaBloqueada> bloquearCuenta(@Param("fid") Long ferreteriaId, @Param("id") Long id);

    /**
     * {@code saldo = saldo + :delta}: relativo, nunca un valor leído antes. Solo lo llama {@code CuentaCorrienteService}
     * (lo vigila ModulosArchTest). Devuelve las filas afectadas (0 si el cliente es de otra ferretería).
     */
    @Modifying
    @Query(value = "update cliente set saldo = saldo + :delta where id = :id and ferreteria_id = :fid", nativeQuery = true)
    int sumarSaldo(@Param("fid") Long ferreteriaId, @Param("id") Long id, @Param("delta") BigDecimal delta);

    default Optional<CuentaBloqueada> bloquearCuentaScoped(Long id) {
        return bloquearCuenta(TenantContext.requireFerreteriaId(), id);
    }

    default int sumarSaldoScoped(Long id, BigDecimal delta) {
        return sumarSaldo(TenantContext.requireFerreteriaId(), id, delta);
    }

    default Page<Cliente> buscarScoped(String patron, String digitos, boolean soloActivos, Pageable pageable) {
        return buscar(TenantContext.requireFerreteriaId(), soloActivos, patron, digitos, pageable);
    }

    default Page<Cliente> todosScoped(boolean soloActivos, Pageable pageable) {
        return todos(TenantContext.requireFerreteriaId(), soloActivos, pageable);
    }

    default boolean existeDocumentoScoped(String numeroDocumento) {
        return existsByFerreteriaIdAndNumeroDocumento(TenantContext.requireFerreteriaId(), numeroDocumento);
    }

    /** Para editar: busca otro cliente con ese documento, excluyendo el propio. */
    default boolean existeOtroConDocumentoScoped(String numeroDocumento, Long idPropio) {
        return existsByFerreteriaIdAndNumeroDocumentoAndIdNot(TenantContext.requireFerreteriaId(), numeroDocumento,
                idPropio);
    }
}
