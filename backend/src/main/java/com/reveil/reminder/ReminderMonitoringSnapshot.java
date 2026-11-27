package com.reveil.reminder;

import java.time.Duration;

/**
 * État de la supervision des rappels sur la fenêtre glissante (endpoint {@code /actuator/reminders}).
 *
 * @param window            fenêtre (ISO-8601, ex. PT1H)
 * @param deliveries        envois par statut
 * @param failureRate       échecs / envois (null sans envoi)
 * @param reminders         rappels envoyés (à l'heure / en retard)
 * @param onTimeRate        part des rappels envoyés à moins de {@code onTimeTolerance} de l'heure prévue (null sans rappel)
 * @param alert             état de l'alerte sur le taux d'échec
 */
public record ReminderMonitoringSnapshot(Duration window, Deliveries deliveries, Double failureRate,
                                         Reminders reminders, Double onTimeRate, Alert alert) {

    public record Deliveries(long total, long success, long failure, long invalidToken) {
    }

    public record Reminders(long sent, long onTime, long late, Duration onTimeTolerance) {
    }

    public record Alert(boolean active, double failureThreshold, long minDeliveries) {
    }
}
