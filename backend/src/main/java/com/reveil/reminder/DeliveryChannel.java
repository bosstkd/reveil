package com.reveil.reminder;

/** Canal d'envoi d'un rappel (valeur de l'étiquette {@code channel} des métriques et des journaux). */
public enum DeliveryChannel {
    /** Application mobile (Firebase Cloud Messaging). */
    FCM("fcm"),
    /** Navigateur abonné (Web Push, story 14). */
    WEB_PUSH("webpush");

    private final String code;

    DeliveryChannel(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
