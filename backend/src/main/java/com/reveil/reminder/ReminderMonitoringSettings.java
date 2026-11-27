package com.reveil.reminder;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Supervision de l'envoi des rappels ({@code reveil.monitoring.reminders.*}, story 15).
 *
 * @param window           fenêtre glissante des indicateurs et de l'alerte (défaut 1 h)
 * @param onTimeTolerance  écart maximal (strict) avec l'heure prévue pour qu'un rappel soit « à l'heure » (1 min)
 * @param failureThreshold taux d'échec des envois au-delà duquel l'alerte est déclenchée (0,05 = 5 %)
 * @param minDeliveries    nombre minimal d'envois dans la fenêtre pour évaluer l'alerte (évite 1 échec sur 2 = 50 %)
 * @param alertEmail       destinataire de l'e-mail d'alerte (REVEIL_ALERT_EMAIL) ; vide : alerte seulement journalisée
 */
@ConfigurationProperties(prefix = "reveil.monitoring.reminders")
public record ReminderMonitoringSettings(
        @DefaultValue("PT1H") Duration window,
        @DefaultValue("PT1M") Duration onTimeTolerance,
        @DefaultValue("0.05") double failureThreshold,
        @DefaultValue("20") long minDeliveries,
        @DefaultValue("") String alertEmail) {
}
