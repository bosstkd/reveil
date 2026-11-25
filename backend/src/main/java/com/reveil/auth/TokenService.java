package com.reveil.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Émet les jetons JWT de session. La clé vient de la variable d'environnement REVEIL_JWT_SECRET. */
@Service
public class TokenService {

    private static final Logger LOG = LoggerFactory.getLogger(TokenService.class);

    private final SecretKey key;
    private final Duration validity;

    public TokenService(@Value("${reveil.jwt.secret:}") String secret,
                        @Value("${reveil.jwt.validity:P7D}") Duration validity) {
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

    public String issue(String userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(validity)))
                .signWith(key)
                .compact();
    }

    /** Retourne l'identifiant utilisateur (subject) d'un jeton valide. */
    public String parseSubject(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
    }
}
