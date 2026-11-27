package com.reveil.passwordreset;

import com.reveil.auth.ValidationRules;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequest(
        @NotBlank(message = "L'e-mail est obligatoire")
        @Email(regexp = ValidationRules.EMAIL_REGEX, message = "L'e-mail n'est pas valide")
        @Size(max = 254, message = "L'e-mail est trop long")
        String email) {
}
