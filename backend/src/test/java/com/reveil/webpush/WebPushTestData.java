package com.reveil.webpush;

import java.util.Map;

/** Corps de {@code PUT /api/push/web/subscription} avec des clés de navigateur valides. */
public final class WebPushTestData {

    private WebPushTestData() {
    }

    public static Map<String, Object> subscription(String endpoint) {
        TestBrowser browser = new TestBrowser();
        return Map.of("endpoint", endpoint, "keys", Map.of("p256dh", browser.p256dh(), "auth", browser.authSecret()));
    }
}
