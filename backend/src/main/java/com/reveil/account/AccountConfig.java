package com.reveil.account;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(AccountSettings.class)
public class AccountConfig {

    /** Planification active sauf {@code reveil.accounts.purge-scheduler-enabled=false} (tests). */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "reveil.accounts.purge-scheduler-enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
    }
}
