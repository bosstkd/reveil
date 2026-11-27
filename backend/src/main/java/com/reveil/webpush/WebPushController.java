package com.reveil.webpush;

import com.reveil.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Notifications du navigateur (Web Push) de l'utilisateur connecté. Routes protégées. */
@RestController
@RequestMapping("/api/push/web")
public class WebPushController {

    private final WebPushService webPush;

    public WebPushController(WebPushService webPush) {
        this.webPush = webPush;
    }

    /** {@code GET /api/push/web/public-key} : clé publique VAPID (base64url) pour {@code pushManager.subscribe}. */
    @GetMapping("/public-key")
    public Map<String, String> publicKey() {
        return Map.of("publicKey", webPush.publicKey());
    }

    /** {@code PUT /api/push/web/subscription} : active les notifications sur ce navigateur (idempotent). */
    @PutMapping("/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@Valid @RequestBody WebPushSubscriptionRequest request,
                          @AuthenticationPrincipal AuthenticatedUser current) {
        webPush.subscribe(current.id(), current.sessionId(), request);
    }

    /** {@code DELETE /api/push/web/subscription} {@code {endpoint}} : désactive les notifications de ce navigateur. */
    @DeleteMapping("/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@Valid @RequestBody WebPushUnsubscribeRequest request,
                            @AuthenticationPrincipal AuthenticatedUser current) {
        webPush.unsubscribe(current.id(), request.endpoint());
    }
}
