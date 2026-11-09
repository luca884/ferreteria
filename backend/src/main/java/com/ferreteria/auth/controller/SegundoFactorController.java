package com.ferreteria.auth.controller;

import com.ferreteria.auth.dto.AltaTotpRequest;
import com.ferreteria.auth.dto.AltaTotpResponse;
import com.ferreteria.auth.dto.LoginResponse;
import com.ferreteria.auth.dto.VerificarTotpRequest;
import com.ferreteria.auth.service.SegundoFactorService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El segundo paso del login del super admin (totp-super-admin). Públicos para Spring Security (SecurityConfig): los autoriza
 * el {@code tokenTotp} del cuerpo, no un {@code Authorization}.
 */
@RestController
@RequestMapping("/api/auth/totp")
public class SegundoFactorController {

    private final SegundoFactorService segundoFactor;

    public SegundoFactorController(SegundoFactorService segundoFactor) {
        this.segundoFactor = segundoFactor;
    }

    /** El QR para Google Authenticator, mientras el código de la app no esté activado (S2). */
    @PostMapping("/alta")
    public AltaTotpResponse alta(@RequestBody AltaTotpRequest request) {
        return segundoFactor.alta(request.tokenTotp());
    }

    /** El código de la app: con el bueno, la sesión (S1). */
    @PostMapping("/verificar")
    public LoginResponse verificar(@RequestBody VerificarTotpRequest request) {
        return segundoFactor.verificar(request.tokenTotp(), request.codigo());
    }
}
