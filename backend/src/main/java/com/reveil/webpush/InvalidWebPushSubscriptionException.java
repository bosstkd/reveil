package com.reveil.webpush;

/** Abonnement Web Push refusé (400) : {@code field} = champ en cause. */
public class InvalidWebPushSubscriptionException extends RuntimeException {

    private final String field;

    public InvalidWebPushSubscriptionException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
