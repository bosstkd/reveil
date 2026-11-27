package com.reveil.reminder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(ReminderSettings.class)
public class ReminderConfig {

    /** Planification active sauf {@code reveil.reminders.scheduler-enabled=false} (tests). */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "reveil.reminders.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
    }
}
