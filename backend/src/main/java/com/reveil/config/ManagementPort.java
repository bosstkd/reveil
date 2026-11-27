package com.reveil.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Port du serveur de gestion (Actuator : métriques Prometheus, supervision des rappels) quand
 * {@code management.server.port} (REVEIL_MANAGEMENT_PORT) le sépare du port de l'API. Ce port est réservé au
 * réseau interne (jamais relayé par nginx ni publié) : ses endpoints sont accessibles sans jeton et en clair
 * (collecte par Prometheus). Sans port distinct, ils restent refusés sur le port public.
 */
public class ManagementPort implements ApplicationListener<WebServerInitializedEvent>, RequestMatcher {

    static final String NAMESPACE = "management";

    private volatile int port = -1;

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (NAMESPACE.equals(event.getApplicationContext().getServerNamespace())) {
            port = event.getWebServer().getPort();
        }
    }

    /** Port effectif du serveur de gestion distinct, -1 s'il n'y en a pas. */
    public int port() {
        return port;
    }

    /** Requête reçue sur le port de gestion distinct. */
    @Override
    public boolean matches(HttpServletRequest request) {
        int p = port;
        return p > 0 && request.getLocalPort() == p;
    }
}
