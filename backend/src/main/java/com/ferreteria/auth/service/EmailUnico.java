package com.ferreteria.auth.service;

import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.tenant.UnscopedTenantAccess;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Chequeo de email repetido. El email es unico en todo el sistema (uk_usuario_email), asi que la consulta no puede
 * filtrar por ferreteria; por eso vive aca, aislada, y solo devuelve un booleano.
 */
@Component
@UnscopedTenantAccess(reason = "El email es único en todo el sistema (uk_usuario_email): el chequeo mira todas las ferreterías y solo devuelve un booleano.")
public class EmailUnico {

    public static final String MENSAJE = "Ese email ya está en uso.";

    private final UsuarioRepository usuarios;

    public EmailUnico(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    /** Se guarda y se busca recortado y en minusculas: el chequeo y la unique de la base coinciden. */
    public static String normalizar(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public boolean enUso(String email) {
        return usuarios.existsByEmailIgnoreCase(normalizar(email));
    }

    /** Igual que {@link #enUso}, sin contar al usuario que se esta editando. */
    public boolean enUsoPorOtro(String email, Long usuarioId) {
        return usuarios.existsByEmailIgnoreCaseAndIdNot(normalizar(email), usuarioId);
    }
}
