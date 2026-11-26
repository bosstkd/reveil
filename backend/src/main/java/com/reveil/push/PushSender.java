package com.reveil.push;

/** Envoi d'une notification push à un appareil. */
public interface PushSender {

    PushResult send(PushMessage message);
}
