package com.reveil.account;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Suppression des comptes ({@code reveil.accounts}).
 *
 * @param purgeDelay   engagement de suppression complète (24 h) : échéance enregistrée avec la demande
 * @param purgeGrace   délai avant la passe de contrôle du planificateur (écritures encore en vol à la suppression)
 * @param purgeCron    fréquence de la passe de contrôle (doit rester très inférieure à {@code purgeDelay})
 */
@ConfigurationProperties(prefix = "reveil.accounts")
public record AccountSettings(@DefaultValue("PT24H") Duration purgeDelay,
                              @DefaultValue("PT10M") Duration purgeGrace,
                              @DefaultValue("0 */15 * * * *") String purgeCron) {
}
