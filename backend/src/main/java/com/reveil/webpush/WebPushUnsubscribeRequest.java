package com.reveil.webpush;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Désactivation des notifications sur le navigateur courant. */
public record WebPushUnsubscribeRequest(
        @NotBlank(message = "L'adresse d'abonnement est obligatoire")
        @Size(max = 2048, message = "L'adresse d'abonnement est trop longue")
        String endpoint) {
}
