package com.reveil.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Choix de l'expéditeur push. FCM est activé si {@code reveil.push.fcm.project-id} est renseigné ; les
 * identifiants du compte de service viennent de {@code reveil.push.fcm.credentials-json} (contenu JSON) ou,
 * à défaut, des « Application Default Credentials » (GOOGLE_APPLICATION_CREDENTIALS). Jamais dans le dépôt.
 */
@Configuration
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);
    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    @Bean
    public PushSender pushSender(@Value("${reveil.push.fcm.project-id:}") String projectId,
                                 @Value("${reveil.push.fcm.credentials-json:}") String credentialsJson,
                                 @Value("${reveil.push.fcm.base-url:https://fcm.googleapis.com}") String baseUrl,
                                 ObjectMapper json) throws IOException {
        if (projectId.isBlank()) {
            log.warn("reveil.push.fcm.project-id non défini : les notifications push sont seulement journalisées");
            return new LoggingPushSender();
        }
        GoogleCredentials credentials = (credentialsJson.isBlank()
                ? GoogleCredentials.getApplicationDefault()
                : GoogleCredentials.fromStream(new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8))))
                .createScoped(List.of(FCM_SCOPE));
        Supplier<String> token = () -> {
            try {
                credentials.refreshIfExpired();
                return credentials.getAccessToken().getTokenValue();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        return new FcmPushSender(baseUrl, projectId.trim(), token, json, Clock.systemUTC(), Duration.ofSeconds(5));
    }
}
