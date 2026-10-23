package com.ferreteria.auth.service;

import com.ferreteria.auth.api.TopesDeDescuento;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TopesDeDescuentoImpl implements TopesDeDescuento {

    private final UsuarioRepository usuarios;

    public TopesDeDescuentoImpl(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> topeDe(long usuarioId) {
        return usuarios.findScoped(usuarioId).map(Usuario::getTopeDescuento);
    }
}
