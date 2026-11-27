package com.reveil.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reveil.auth.TokenService;
import com.reveil.session.SessionService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

/**
 * API sans état : authentification par jeton Bearer, tout est protégé sauf l'ouverture de session.
 * HTTPS exigé ({@code reveil.security.require-https}) et en-têtes de sécurité sur toutes les réponses.
 */
@Configuration
public class SecurityConfig {

    /** L'API ne sert que du JSON et des images : aucune ressource active, aucun affichage en cadre. */
    static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=()";
    /** HSTS : 1 an, sous-domaines compris (envoyé uniquement sur une requête HTTPS). */
    static final long HSTS_MAX_AGE_SECONDS = 31_536_000;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, TokenService tokens, SessionService sessions,
                                                   ObjectMapper json,
                                                   @Value("${reveil.security.require-https:true}") boolean requireHttps)
            throws Exception {
        if (requireHttps) {
            http.addFilterBefore(new HttpsEnforcementFilter(json), DisableEncodeUrlFilter.class);
        }
        return http
                .csrf(AbstractHttpConfigurer::disable) // pas de cookie de session : CSRF sans objet
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)
                                .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS))
                        .contentTypeOptions(c -> { }) // X-Content-Type-Options: nosniff
                        .frameOptions(f -> f.deny())
                        .contentSecurityPolicy(c -> c.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                write(res, json, HttpServletResponse.SC_UNAUTHORIZED, "Authentification requise"))
                        .accessDeniedHandler((req, res, ex) ->
                                write(res, json, HttpServletResponse.SC_FORBIDDEN, "Accès refusé")))
                .addFilterBefore(new JwtAuthenticationFilter(tokens, sessions),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static void write(HttpServletResponse res, ObjectMapper json, int status, String message)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getOutputStream(), Map.of("message", message, "errors", List.of()));
    }
}
