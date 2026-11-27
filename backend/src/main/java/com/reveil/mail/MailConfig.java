package com.reveil.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Choix de l'expéditeur d'e-mails : SMTP si {@code spring.mail.host} est renseigné (identifiants
 * {@code spring.mail.username/password} fournis par l'environnement, jamais dans le dépôt), sinon journalisation.
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    public EmailSender emailSender(@Value("${spring.mail.host:}") String host,
                                   @Value("${reveil.mail.from:Reveil <no-reply@reveil.local>}") String from,
                                   @Value("${reveil.mail.log-content:false}") boolean logContent,
                                   ObjectProvider<JavaMailSender> javaMailSender) {
        JavaMailSender smtp = host.isBlank() ? null : javaMailSender.getIfAvailable();
        if (smtp == null) {
            log.warn("spring.mail.host non défini : les e-mails sont seulement journalisés");
            return new LoggingEmailSender(logContent);
        }
        return new SmtpEmailSender(smtp, from);
    }
}
