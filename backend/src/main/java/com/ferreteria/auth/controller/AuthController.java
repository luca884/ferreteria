package com.ferreteria.auth.controller;

import com.ferreteria.auth.dto.CambiarContrasenaRequest;
import com.ferreteria.auth.dto.LoginRequest;
import com.ferreteria.auth.dto.LoginResponse;
import com.ferreteria.auth.dto.SesionResponse;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public SesionResponse me(@AuthenticationPrincipal AuthUser authUser) {
        return authService.sesionActual(authUser);
    }

    /** Cambia la contraseña del usuario del token; vale para todo rol (cae en anyRequest().authenticated()). */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cambiarContrasena(@Valid @RequestBody CambiarContrasenaRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        authService.cambiarContrasena(authUser, request.passwordActual(), request.passwordNueva());
    }
}
