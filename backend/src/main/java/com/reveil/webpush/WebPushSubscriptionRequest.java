package com.reveil.webpush;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Abonnement du navigateur, tel que produit par {@code PushSubscription.toJSON()}. */
public record WebPushSubscriptionRequest(
        @NotBlank(message = "L'adresse d'abonnement est obligatoire")
        @Size(max = 2048, message = "L'adresse d'abonnement est trop longue")
        String endpoint,

        @NotNull(message = "Les clés de l'abonnement sont obligatoires")
        @Valid
        Keys keys) {

    public record Keys(
            @NotBlank(message = "La clé p256dh est obligatoire")
            @Size(max = 200, message = "La clé p256dh est invalide")
            String p256dh,

            @NotBlank(message = "Le secret auth est obligatoire")
            @Size(max = 100, message = "Le secret auth est invalide")
            String auth) {
    }
}
