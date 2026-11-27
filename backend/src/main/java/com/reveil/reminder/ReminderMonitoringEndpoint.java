package com.reveil.reminder;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * {@code GET /actuator/reminders} : taux de rappels à l'heure, taux d'échec et état de l'alerte sur la fenêtre
 * glissante. Exposé sur le port de gestion interne seulement (voir {@code ManagementPort}).
 */
@Component
@Endpoint(id = "reminders")
public class ReminderMonitoringEndpoint {

    private final ReminderMonitor monitor;

    public ReminderMonitoringEndpoint(ReminderMonitor monitor) {
        this.monitor = monitor;
    }

    @ReadOperation
    public ReminderMonitoringSnapshot reminders() {
        return monitor.snapshot();
    }
}
