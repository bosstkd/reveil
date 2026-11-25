package com.reveil.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Émet et vérifie les jetons d'accès JWT (courte durée, liés à une session par la claim « sid »). La clé vient de la variable d'environnement REVEIL_JWT_SECRET. */
@Service
public class TokenService {

    private static final Logger LOG = LoggerFactory.getLogger(TokenService.class);

    private final SecretKey key;
    private final Duration validity;

    public TokenService(@Value("${reveil.jwt.secret:}") String secret,
                        @Value("${reveil.jwt.validity:PT15M}") Duration validity) {
        this.validity = validity;
        if (secret == null || secret.isBlank()) {
            LOG.warn("REVEIL_JWT_SECRET non défini : clé éphémère générée (les sessions ne survivront pas au redémarrage)");
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = Keys.hmacShaKeyFor(random);
        } else {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException("REVEIL_JWT_SECRET doit faire au moins 32 octets");
            }
            this.key = Keys.hmacShaKeyFor(bytes);
        }
    }

    /** Contenu d'un jeton d'accès valide. */
    public record AccessToken(String userId, String email, String sessionId) {
    }

    public Duration validity() {
        return validity;
    }

    public String issue(String userId, String email, String sessionId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim("sid", sessionId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(validity)))
                .signWith(key)
                .compact();
    }

    /** Retourne l'identifiant utilisateur (subject) d'un jeton valide. */
    public String parseSubject(String token) {
        return claims(token).getSubject();
    }

    /** Jeton signé, non expiré et rattaché à une session ; vide sinon. */
    public Optional<AccessToken> verify(String token) {
        try {
            Claims c = claims(token);
            String sid = c.get("sid", String.class);
            if (c.getSubject() == null || sid == null) {
                return Optional.empty();
            }
            return Optional.of(new AccessToken(c.getSubject(), c.get("email", String.class), sid));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
