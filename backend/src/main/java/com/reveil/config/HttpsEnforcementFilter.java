package com.reveil.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuse (403 JSON) toute requête arrivée en clair quand {@code reveil.security.require-https} est actif.
 * « En clair » = {@code request.isSecure()} faux : TLS terminé par l'API elle-même, ou en-tête
 * {@code X-Forwarded-Proto: https} posé par un relais interne de confiance ({@code server.forward-headers-strategy=native}).
 * Pas de redirection : le corps (mot de passe, jetons) aurait déjà circulé en clair ; le client doit être corrigé.
 * Les sondes de santé et le port de gestion interne ({@link ManagementPort} : collecte Prometheus) restent
 * accessibles (réseau interne).
 */
public class HttpsEnforcementFilter extends OncePerRequestFilter {

    static final String MESSAGE = "Connexion HTTPS requise";

    private final ObjectMapper json;
    private final RequestMatcher internal;

    public HttpsEnforcementFilter(ObjectMapper json) {
        this(json, request -> false);
    }

    public HttpsEnforcementFilter(ObjectMapper json, RequestMatcher internal) {
        this.json = json;
        this.internal = internal;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (internal.matches(request)) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.isSecure()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), Map.of("message", MESSAGE, "errors", List.of()));
    }
}
