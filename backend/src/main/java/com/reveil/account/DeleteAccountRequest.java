package com.reveil.account;

import jakarta.validation.constraints.NotBlank;

/** Confirmation de la suppression du compte : mot de passe actuel. */
public record DeleteAccountRequest(@NotBlank(message = "Saisissez votre mot de passe") String password) {
}
