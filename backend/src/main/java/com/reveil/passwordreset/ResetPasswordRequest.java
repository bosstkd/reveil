package com.reveil.passwordreset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Mêmes règles de mot de passe que l'inscription ({@code RegisterRequest}). */
public record ResetPasswordRequest(
        @NotBlank(message = InvalidResetTokenException.MESSAGE)
        @Size(max = 200, message = InvalidResetTokenException.MESSAGE)
        String token,

        @NotBlank(message = "Le mot de passe est obligatoire")
        @Size(min = 8, max = 72, message = "Le mot de passe doit contenir entre 8 et 72 caractères")
        @Pattern(regexp = ".*\\d.*", message = "Le mot de passe doit contenir au moins 1 chiffre")
        String password) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[token=***, password=***]";
    }
}
