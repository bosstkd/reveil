package com.reveil.reminder;

import com.reveil.push.PushResult;

/** Statut d'un envoi de rappel à un destinataire (étiquette {@code status} des métriques et des journaux). */
public enum DeliveryStatus {
    /** Accepté par le service push. */
    SUCCESS("success", "succès"),
    /** Échec (réseau, quota, panne, erreur interne) : compte dans le taux d'échec surveillé. */
    FAILURE("failure", "échec"),
    /** Jeton / abonnement définitivement invalide (application désinstallée…) : oublié, hors taux d'échec. */
    INVALID_TOKEN("invalid_token", "jeton invalide");

    private final String code;
    private final String label;

    DeliveryStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static DeliveryStatus of(PushResult result) {
        return switch (result) {
            case SENT -> SUCCESS;
            case INVALID_TOKEN -> INVALID_TOKEN;
            case FAILED -> FAILURE;
        };
    }
}
