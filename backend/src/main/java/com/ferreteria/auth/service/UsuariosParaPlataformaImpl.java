package com.ferreteria.auth.service;

import com.ferreteria.auth.api.UsuariosParaPlataforma;
import com.ferreteria.auth.model.PoliticaDeContrasena;
import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.auth.repository.UsuarioRepository.ConteoPorRol;
import com.ferreteria.common.exception.ErrorDeBase;
import com.ferreteria.tenant.UnscopedTenantAccess;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que la plataforma (super admin) necesita de los usuarios. Opera por id de ferretería explícito y nunca sobre datos de
 * negocio; la ferretería NO sale del JWT (el super admin no tiene).
 */
@Service
@UnscopedTenantAccess(reason = "El super admin opera sobre ferreterías por id explícito; nunca sobre datos de negocio.")
public class UsuariosParaPlataformaImpl implements UsuariosParaPlataforma {

    private final UsuarioRepository usuarios;
    private final EmailUnico emailUnico;
    private final PasswordEncoder passwordEncoder;

    public UsuariosParaPlataformaImpl(UsuarioRepository usuarios, EmailUnico emailUnico, PasswordEncoder passwordEncoder) {
        this.usuarios = usuarios;
        this.emailUnico = emailUnico;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long crearDueno(long ferreteriaId, String nombre, String email, String password) {
        PoliticaDeContrasena.validar("password", password);
        Usuario dueno = Usuario.nuevo(ferreteriaId, EmailUnico.normalizar(email), passwordEncoder.encode(password),
                nombre.trim(), Rol.DUENO);
        try {
            return usuarios.saveAndFlush(dueno).getId();
        } catch (DataIntegrityViolationException e) {
            if (ErrorDeBase.es(e, ErrorDeBase.DUPLICADO)) {
                throw new EmailEnUsoException();
            }
            throw e;
        }
    }

    @Override
    public boolean emailEnUso(String email) {
        return emailUnico.enUso(email);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ResumenUsuarios> resumenPorFerreteria() {
        Map<Long, long[]> cuentas = new HashMap<>();
        for (ConteoPorRol c : usuarios.conteoActivosPorRol()) {
            long[] cuenta = cuentas.computeIfAbsent(c.ferreteriaId(), k -> new long[2]);
            if (c.rol() == Rol.DUENO) {
                cuenta[0] = c.cantidad();
            } else if (c.rol() == Rol.VENDEDOR) {
                cuenta[1] = c.cantidad();
            }
        }
        Map<Long, Usuario> duenoPorFerreteria = new HashMap<>();
        for (Usuario d : usuarios.duenosActivos()) {
            duenoPorFerreteria.merge(d.getFerreteriaId(), d, (a, b) -> a.getId() <= b.getId() ? a : b);
        }
        Set<Long> ids = new HashSet<>(cuentas.keySet());
        ids.addAll(duenoPorFerreteria.keySet());
        Map<Long, ResumenUsuarios> resumen = new HashMap<>();
        for (Long id : ids) {
            long[] cuenta = cuentas.getOrDefault(id, new long[2]);
            Usuario dueno = duenoPorFerreteria.get(id);
            resumen.put(id, new ResumenUsuarios(cuenta[0], cuenta[1],
                    dueno == null ? null : dueno.getId(), dueno == null ? null : dueno.getEmail()));
        }
        return resumen;
    }

    @Override
    @Transactional
    public boolean resetearContrasenaDeDueno(long ferreteriaId, long usuarioId, String password) {
        Optional<Usuario> dueno = usuarios.duenoDe(ferreteriaId, usuarioId);
        if (dueno.isEmpty()) {
            return false;
        }
        PoliticaDeContrasena.validar("password", password);
        dueno.get().cambiarPasswordHash(passwordEncoder.encode(password));
        usuarios.saveAndFlush(dueno.get());
        return true;
    }
}
