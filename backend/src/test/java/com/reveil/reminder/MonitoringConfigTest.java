package com.reveil.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reveil.mail.EmailSender;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Le tableau de bord Grafana et les règles d'alerte Prometheus ({@code monitoring/}) n'utilisent que des métriques
 * réellement exposées par l'API (un renommage côté Java casserait la supervision sans bruit).
 */
class MonitoringConfigTest {

    static final Path MONITORING = Path.of("..", "monitoring");
    static final Pattern METRIC = Pattern.compile("reveil_reminders_[a-z_]+");

    private static String scrape() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().commonTags(List.of(Tag.of("application", "reveil-backend")));
        ReminderMonitor monitor = new ReminderMonitor(registry, new ReminderMonitoringSettings(Duration.ofHours(1),
                Duration.ofMinutes(1), 0.05, 20, ""), mock(EmailSender.class), Runnable::run, Clock.systemUTC());
        monitor.evaluate();
        return registry.scrape();
    }

    private static Set<String> metricsIn(String text) {
        Set<String> names = new TreeSet<>();
        Matcher m = METRIC.matcher(text);
        while (m.find()) {
            names.add(m.group());
        }
        return names;
    }

    private static void assertExposed(Set<String> used, String scrape) {
        assertThat(used).isNotEmpty();
        for (String name : used) {
            assertThat(scrape).as("métrique %s", name).containsPattern("(?m)^" + name + "[{ ]");
        }
    }

    @Test
    void alertRulesUseExposedMetrics() throws Exception {
        String rules = Files.readString(MONITORING.resolve("prometheus/alerts.yml"));
        assertExposed(metricsIn(rules), scrape());
        assertThat(rules).contains("> 0.05").contains("[1h]").contains(">= 20");
    }

    @Test
    void dashboardIsValidAndUsesExposedMetrics() throws Exception {
        String raw = Files.readString(MONITORING.resolve("grafana/dashboards/reveil-rappels.json"));
        JsonNode dashboard = new ObjectMapper().readTree(raw);
        assertThat(dashboard.get("uid").asText()).isEqualTo("reveil-rappels");
        StringBuilder exprs = new StringBuilder();
        dashboard.get("panels").forEach(p -> p.get("targets").forEach(t -> exprs.append(t.get("expr").asText()).append('\n')));
        assertExposed(metricsIn(exprs.toString()), scrape());
    }
}
