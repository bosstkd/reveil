package com.reveil.passwordreset;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Réinitialisation du mot de passe ({@code reveil.password-reset}).
 *
 * @param validity       durée de validité d'un lien (1 h)
 * @param maxActiveLinks nombre maximal de liens valides simultanément par compte (au-delà : demande ignorée
 *                       silencieusement, la réponse reste identique)
 * @param webUrl         adresse publique de l'interface web (ex. https://hote/test/reveil) : base du lien envoyé.
 *                       Configurée, jamais déduite de la requête (en-tête Host falsifiable).
 * @param mobileUrl      lien profond de l'application mobile (schéma {@code reveil://})
 */
@ConfigurationProperties(prefix = "reveil.password-reset")
public record PasswordResetSettings(@DefaultValue("PT1H") Duration validity,
                                    @DefaultValue("3") int maxActiveLinks,
                                    @DefaultValue("http://localhost:4200") String webUrl,
                                    @DefaultValue("reveil://reinitialiser-mot-de-passe") String mobileUrl) {
}
