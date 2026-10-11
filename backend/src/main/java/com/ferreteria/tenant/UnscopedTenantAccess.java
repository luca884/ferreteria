package com.ferreteria.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un acceso a datos que NO pasa por el tenant del request (login, jobs, super admin).
 * El test TenantScopingArchTest solo permite finders sin scope en codigo anotado con esto.
 * {@code reason()} es obligatorio para que cada excepcion quede documentada.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR})
public @interface UnscopedTenantAccess {

    String reason();
}
