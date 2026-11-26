package com.reveil.photo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PhotoSettings.class)
public class PhotoConfig {
}
