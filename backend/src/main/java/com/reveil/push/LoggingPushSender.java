package com.reveil.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utilisé quand FCM n'est pas configuré (développement, environnement de test) : journalise seulement. */
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public PushResult send(PushMessage message) {
        log.info("[push désactivé] « {} » — {} {}", message.title(), message.body(), message.data());
        return PushResult.SENT;
    }
}
