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
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuse (403 JSON) toute requête arrivée en clair quand {@code reveil.security.require-https} est actif.
 * « En clair » = {@code request.isSecure()} faux : TLS terminé par l'API elle-même, ou en-tête
 * {@code X-Forwarded-Proto: https} posé par un relais interne de confiance ({@code server.forward-headers-strategy=native}).
 * Pas de redirection : le corps (mot de passe, jetons) aurait déjà circulé en clair ; le client doit être corrigé.
 * Les sondes de santé restent accessibles (orchestrateur sur le réseau interne).
 */
public class HttpsEnforcementFilter extends OncePerRequestFilter {

    static final String MESSAGE = "Connexion HTTPS requise";

    private final ObjectMapper json;

    public HttpsEnforcementFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
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
