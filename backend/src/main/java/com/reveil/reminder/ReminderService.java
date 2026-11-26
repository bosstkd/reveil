package com.reveil.reminder;

import com.reveil.device.DeviceService;
import com.reveil.device.DeviceToken;
import com.reveil.event.Event;
import com.reveil.push.PushMessage;
import com.reveil.push.PushResult;
import com.reveil.push.PushSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Envoi des rappels échus aux appareils du propriétaire de l'événement. */
@Service
public class ReminderService {

    /** Type de notification (champ {@code data.type}) reconnu par l'application mobile. */
    public static final String TYPE = "event-reminder";

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private final ReminderQueue queue;
    private final DeviceService devices;
    private final PushSender push;
    private final ReminderSettings settings;
    private final Clock clock;

    @Autowired
    public ReminderService(ReminderQueue queue, DeviceService devices, PushSender push, ReminderSettings settings) {
        this(queue, devices, push, settings, Clock.systemUTC());
    }

    ReminderService(ReminderQueue queue, DeviceService devices, PushSender push, ReminderSettings settings,
                    Clock clock) {
        this.queue = queue;
        this.devices = devices;
        this.push = push;
        this.settings = settings;
        this.clock = clock;
    }

    /** Traite tous les rappels échus ; renvoie le nombre de rappels pris en charge. */
    public int sendDueReminders() {
        Instant now = clock.instant();
        Instant dueBefore = now.plus(settings.lookahead());
        int processed = 0;
        for (Optional<Event> next = queue.claimNextDue(dueBefore, now); next.isPresent();
             next = queue.claimNextDue(dueBefore, now)) {
            remind(next.get(), now);
            processed++;
        }
        return processed;
    }

    /** Programme les rappels des événements antérieurs à cette fonctionnalité. */
    public long scheduleMissingReminders() {
        return queue.scheduleMissing(clock.instant(), settings.leadTime());
    }

    private void remind(Event event, Instant now) {
        if (!event.getStartsAt().isAfter(now)) {
            // Rappel en retard (API arrêtée) et événement déjà commencé : un rappel n'a plus de sens.
            log.info("Rappel abandonné : l'événement {} a déjà commencé", event.getId());
            return;
        }
        String title = "Rappel : " + event.getTitle();
        String body = body(event, now);
        Map<String, String> data = Map.of(
                "type", TYPE,
                "eventId", event.getId(),
                "link", "reveil://events/" + event.getId());
        for (DeviceToken device : devices.activeDevicesOf(event.getOwnerId())) {
            PushResult result = push.send(new PushMessage(device.getToken(), title, body, data, event.getStartsAt(),
                    "reminder-" + event.getId()));
            if (result == PushResult.INVALID_TOKEN) {
                devices.forget(device.getToken());
            } else if (result == PushResult.FAILED) {
                log.warn("Rappel de l'événement {} non remis à un appareil", event.getId());
            }
        }
    }

    /** « Commence dans 1 h, à 20:30 · Olympia » (heure dans le fuseau de saisie de l'événement). */
    static String body(Event event, Instant now) {
        long minutes = Math.round(Duration.between(now, event.getStartsAt()).toSeconds() / 60.0);
        String in;
        if (minutes < 1) {
            in = "maintenant";
        } else if (minutes < 60) {
            in = "dans " + minutes + " min";
        } else {
            in = "dans " + (minutes / 60) + " h" + (minutes % 60 == 0 ? "" : " " + (minutes % 60) + " min");
        }
        String at = HOUR.format(event.getStartsAt().atZone(ZoneId.of(event.getTimeZone())));
        String where = event.getLocation() == null ? "" : " · " + event.getLocation();
        return "Commence " + in + ", à " + at + where;
    }
}
