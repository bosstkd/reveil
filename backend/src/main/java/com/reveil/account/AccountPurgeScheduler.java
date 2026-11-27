package com.reveil.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Passe de contrôle des suppressions de compte ({@code reveil.accounts.purge-cron}, toutes les 15 min). */
@Component
@ConditionalOnProperty(name = "reveil.accounts.purge-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class AccountPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeScheduler.class);

    private final AccountService accounts;

    public AccountPurgeScheduler(AccountService accounts) {
        this.accounts = accounts;
    }

    @Scheduled(cron = "${reveil.accounts.purge-cron:0 */15 * * * *}")
    public void tick() {
        try {
            int done = accounts.purgePending();
            if (done > 0) {
                log.info("{} suppression(s) de compte clôturée(s)", done);
            }
        } catch (RuntimeException e) {
            log.error("Échec de la passe de suppression des comptes", e);
        }
    }
}
