package com.reveil.reminder;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Réglages des rappels ({@code reveil.reminders.*}).
 *
 * @param leadTime  délai entre le rappel et le début de l'événement (défaut : 1 heure)
 * @param lookahead marge d'anticipation du planificateur : un rappel dû dans moins de {@code lookahead}
 *                  part dès ce passage (absorbe le décalage d'horloge du déclenchement)
 */
@ConfigurationProperties(prefix = "reveil.reminders")
public record ReminderSettings(@DefaultValue("PT1H") Duration leadTime, @DefaultValue("PT5S") Duration lookahead) {
}
