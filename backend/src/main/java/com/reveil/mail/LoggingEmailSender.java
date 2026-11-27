package com.reveil.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sans serveur SMTP configuré : l'e-mail n'est pas envoyé, seulement journalisé. Le corps (qui peut contenir un
 * lien de réinitialisation) n'est journalisé que si {@code reveil.mail.log-content=true} (environnements de test).
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private final boolean logContent;

    public LoggingEmailSender(boolean logContent) {
        this.logContent = logContent;
    }

    @Override
    public void send(EmailMessage message) {
        if (logContent) {
            log.info("E-mail non envoyé (SMTP non configuré) à {} — {}\n{}", message.to(), message.subject(),
                    message.body());
        } else {
            log.info("E-mail non envoyé (SMTP non configuré) à {} — {}", message.to(), message.subject());
        }
    }
}
