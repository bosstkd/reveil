package com.reveil.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Détecte les rappels échus chaque minute, à la seconde 0 ({@code reveil.reminders.cron}). Les heures
 * d'événements étant à la minute, un rappel part au plus quelques secondes après son échéance.
 */
@Component
@ConditionalOnProperty(name = "reveil.reminders.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderService reminders;

    public ReminderScheduler(ReminderService reminders) {
        this.reminders = reminders;
    }

    @Scheduled(cron = "${reveil.reminders.cron:0 * * * * *}")
    public void tick() {
        try {
            int sent = reminders.sendDueReminders();
            if (sent > 0) {
                log.info("{} rappel(s) traité(s)", sent);
            }
        } catch (RuntimeException e) {
            // Les rappels non pris restent en file : ils partiront au prochain passage.
            log.error("Échec du traitement des rappels", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleMissing() {
        try {
            long n = reminders.scheduleMissingReminders();
            if (n > 0) {
                log.info("Rappel programmé pour {} événement(s) existant(s)", n);
            }
        } catch (RuntimeException e) {
            log.error("Programmation des rappels existants impossible", e);
        }
    }
}
