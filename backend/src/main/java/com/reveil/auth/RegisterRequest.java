package com.reveil.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "L'e-mail est obligatoire")
        @Email(regexp = ValidationRules.EMAIL_REGEX, message = "L'e-mail n'est pas valide")
        @Size(max = 254, message = "L'e-mail est trop long")
        String email,

        @NotBlank(message = "Le mot de passe est obligatoire")
        @Size(min = 8, max = 72, message = "Le mot de passe doit contenir entre 8 et 72 caractères")
        @Pattern(regexp = ".*\\d.*", message = "Le mot de passe doit contenir au moins 1 chiffre")
        String password) {

    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=***]";
    }
}
