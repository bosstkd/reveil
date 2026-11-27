package com.reveil.webpush;

import com.reveil.push.PushResult;

/** Envoi d'une notification à un navigateur abonné. */
public interface WebPushSender {

    PushResult send(WebPushSubscription subscription, WebPushMessage message);
}
