package com.reveil.passwordreset;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PasswordResetSettings.class)
public class PasswordResetConfig {
}
