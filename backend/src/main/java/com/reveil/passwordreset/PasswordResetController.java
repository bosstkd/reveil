package com.reveil.passwordreset;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Mot de passe oublié : routes publiques. */
@RestController
@RequestMapping("/api/auth/password-reset")
public class PasswordResetController {

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    /** Toujours 202 avec le même message, que le compte existe ou non. */
    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> request(@Valid @RequestBody ForgotPasswordRequest request) {
        service.requestReset(request.email());
        return Map.of("message", PasswordResetService.CONFIRMATION_MESSAGE);
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(request.token(), request.password());
    }
}
