package com.reveil.webpush;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WebPushSettings.class)
public class WebPushConfig {

    @Bean
    public WebPushSender webPushSender(VapidKeyStore keys, WebPushSettings settings, ObjectMapper json) {
        return new HttpWebPushSender(keys::keys, settings.subject(), json, Clock.systemUTC(), settings.timeout());
    }
}
