package com.reveil.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reveil.device.DeviceService;
import com.reveil.event.EventRepository;
import com.reveil.push.PushMessage;
import com.reveil.push.PushResult;
import com.reveil.push.PushSender;
import com.reveil.webpush.WebPushService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Supervision de bout en bout (story 15) : serveur réel, port de gestion distinct (comme docker-compose), rappels
 * envoyés via MongoDB embarqué, métriques lues au format Prometheus et via {@code /actuator/reminders}.
 */
// Export Prometheus désactivé par défaut dans les tests Spring Boot : réactivé ici.
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"management.server.port=0", "reveil.security.require-https=true"})
@ActiveProfiles("test")
class ReminderMonitoringIntegrationTest {

    @LocalServerPort
    int port;
    @LocalManagementPort
    int managementPort;
    @Autowired
    ObjectMapper json;
    @Autowired
    EventRepository events;
    @Autowired
    ReminderQueue queue;
    @Autowired
    DeviceService devices;
    @Autowired
    WebPushService webPush;
    @Autowired
    ReminderSettings settings;
    @Autowired
    ReminderMonitor monitor;
    @MockitoBean
    PushSender push;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        events.deleteAll();
        when(push.send(any())).thenAnswer(inv ->
                ((PushMessage) inv.getArgument(0)).token().startsWith("panne") ? PushResult.FAILED : PushResult.SENT);
    }

    private HttpResponse<String> call(int port, String method, String path, String bearer, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                // Relais interne de confiance (localhost) : la requête est considérée HTTPS (exigé par l'API).
                .header("X-Forwarded-Proto", "https")
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", bearer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> plain(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static double sample(String prometheus, String metric, String labels) {
        return prometheus.lines()
                .filter(l -> l.startsWith(metric + "{")
                        && java.util.Arrays.stream(labels.split(",")).allMatch(l::contains))
                .mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1)))
                .sum();
    }

    @Test
    void remindersAreMeasuredAndExposedOnTheInternalManagementPortOnly() throws Exception {
        String prometheusBefore = plain(managementPort, "/actuator/prometheus").body();

        HttpResponse<String> registered = call(port, "POST", "/api/auth/register", null,
                "{\"email\":\"supervision@example.com\",\"password\":\"secret123\"}");
        assertThat(registered.statusCode()).isEqualTo(201);
        JsonNode auth = json.readTree(registered.body());
        String bearer = "Bearer " + auth.get("token").asText();
        for (String token : new String[] {"ok-1", "panne-1"}) {
            assertThat(call(port, "PUT", "/api/devices", bearer,
                    "{\"token\":\"" + token + "\",\"platform\":\"android\"}").statusCode()).isEqualTo(204);
        }
        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.MINUTES);
        HttpResponse<String> created = call(port, "POST", "/api/events", bearer,
                "{\"title\":\"Concert\",\"date\":\"" + start.toLocalDate() + "\",\"time\":\"" + start.toLocalTime()
                        + "\",\"timeZone\":\"UTC\",\"reminders\":[1440,60]}");
        assertThat(created.statusCode()).isEqualTo(201);
        Instant startsAt = start.toInstant(ZoneOffset.UTC);

        // Rappel « 1 jour » traité 2 s après l'échéance (à l'heure), « 1 h » 3 min après (en retard).
        new ReminderService(queue, devices, push, webPush, settings, monitor,
                Clock.fixed(startsAt.minus(Duration.ofDays(1)).plusSeconds(2), ZoneOffset.UTC)).sendDueReminders();
        new ReminderService(queue, devices, push, webPush, settings, monitor,
                Clock.fixed(startsAt.minus(Duration.ofMinutes(57)), ZoneOffset.UTC)).sendDueReminders();

        String prometheus = plain(managementPort, "/actuator/prometheus").body();
        assertThat(sample(prometheus, "reveil_reminders_deliveries_total", "channel=\"fcm\",status=\"success\"")
                - sample(prometheusBefore, "reveil_reminders_deliveries_total", "channel=\"fcm\",status=\"success\""))
                .isEqualTo(2);
        assertThat(sample(prometheus, "reveil_reminders_deliveries_total", "channel=\"fcm\",status=\"failure\"")
                - sample(prometheusBefore, "reveil_reminders_deliveries_total", "channel=\"fcm\",status=\"failure\""))
                .isEqualTo(2);
        assertThat(sample(prometheus, "reveil_reminders_dispatched_total", "on_time=\"true\"")
                - sample(prometheusBefore, "reveil_reminders_dispatched_total", "on_time=\"true\"")).isEqualTo(1);
        assertThat(sample(prometheus, "reveil_reminders_dispatched_total", "on_time=\"false\"")
                - sample(prometheusBefore, "reveil_reminders_dispatched_total", "on_time=\"false\"")).isEqualTo(1);
        assertThat(prometheus).contains("reveil_reminders_dispatch_delay_seconds_bucket{application=\"reveil-backend\",le=\"60.0\"}")
                .contains("reveil_reminders_on_time_ratio{").contains("reveil_reminders_failure_ratio{")
                .contains("reveil_reminders_alert_active{");

        HttpResponse<String> summary = plain(managementPort, "/actuator/reminders");
        assertThat(summary.statusCode()).isEqualTo(200);
        JsonNode s = json.readTree(summary.body());
        assertThat(s.get("window").asText()).isEqualTo("PT1H");
        assertThat(s.at("/deliveries/failure").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(s.has("onTimeRate")).isTrue();
        assertThat(s.at("/alert/failureThreshold").asDouble()).isEqualTo(0.05);

        // Port public : métriques refusées, même authentifié ; la sonde de santé n'y est plus (port de gestion).
        assertThat(call(port, "GET", "/actuator/prometheus", bearer, null).statusCode()).isIn(401, 403, 404);
        assertThat(call(port, "GET", "/actuator/reminders", bearer, null).statusCode()).isIn(401, 403, 404);
        assertThat(plain(port, "/api/auth/me").statusCode()).isEqualTo(403); // HTTPS toujours exigé sur l'API
        assertThat(plain(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
    }
}
