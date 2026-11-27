package com.reveil.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Sessions par appareil, identifiées par un jeton de rafraîchissement opaque
 * « &lt;idSession&gt;.&lt;secret&gt; ». Rotation à chaque rafraîchissement, expiration glissante,
 * révocation à la déconnexion, détection de rejeu (session révoquée si un ancien jeton réapparaît).
 */
@Service
public class SessionService {

    /** Jeton de rafraîchissement émis + session associée. */
    public record Issued(String sessionId, String userId, String refreshToken) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionRepository sessions;
    private final Duration validity;
    private final Duration rotationGrace;
    private final Clock clock;

    @Autowired
    public SessionService(SessionRepository sessions,
                          @Value("${reveil.session.validity:P30D}") Duration validity,
                          @Value("${reveil.session.rotation-grace:PT1M}") Duration rotationGrace) {
        this(sessions, validity, rotationGrace, Clock.systemUTC());
    }

    SessionService(SessionRepository sessions, Duration validity, Duration rotationGrace, Clock clock) {
        this.sessions = sessions;
        this.validity = validity;
        this.rotationGrace = rotationGrace;
        this.clock = clock;
    }

    public Duration validity() {
        return validity;
    }

    public Issued open(String userId) {
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        String secret = newSecret();
        sessions.save(new Session(id, userId, hash(secret), now, now.plus(validity)));
        return new Issued(id, userId, id + "." + secret);
    }

    /** Valide le jeton, le remplace par un nouveau et prolonge la session. */
    public Issued refresh(String refreshToken) {
        for (int attempt = 0; ; attempt++) {
            try {
                return tryRefresh(refreshToken);
            } catch (OptimisticLockingFailureException e) {
                // Deux rafraîchissements simultanés : on relit la session (l'ancien jeton est alors toléré).
                if (attempt >= 2) {
                    throw new InvalidRefreshTokenException();
                }
            }
        }
    }

    private Issued tryRefresh(String refreshToken) {
        Parsed parsed = parse(refreshToken).orElseThrow(InvalidRefreshTokenException::new);
        Instant now = clock.instant();
        Session session = sessions.findById(parsed.sessionId())
                .filter(s -> !s.isExpired(now))
                .orElseThrow(InvalidRefreshTokenException::new);

        String presented = hash(parsed.secret());
        boolean current = constantTimeEquals(presented, session.getTokenHash());
        boolean previousInGrace = session.getPreviousTokenHash() != null
                && constantTimeEquals(presented, session.getPreviousTokenHash())
                && session.getRotatedAt() != null
                && now.isBefore(session.getRotatedAt().plus(rotationGrace));
        if (!current && !previousInGrace) {
            // Jeton rejoué ou forgé : par précaution la session entière est révoquée.
            sessions.deleteById(session.getId());
            throw new InvalidRefreshTokenException();
        }

        String secret = newSecret();
        session.rotate(hash(secret), now, now.plus(validity));
        sessions.save(session);
        return new Issued(session.getId(), session.getUserId(), session.getId() + "." + secret);
    }

    /** Révoque la session désignée par le jeton (déconnexion de l'appareil courant). Idempotent. */
    public void revoke(String refreshToken) {
        parse(refreshToken).ifPresent(p -> sessions.findById(p.sessionId())
                .filter(s -> constantTimeEquals(hash(p.secret()), s.getTokenHash())
                        || (s.getPreviousTokenHash() != null && constantTimeEquals(hash(p.secret()), s.getPreviousTokenHash())))
                .ifPresent(s -> sessions.deleteById(s.getId())));
    }

    public void revokeById(String sessionId) {
        if (sessionId != null) {
            sessions.deleteById(sessionId);
        }
    }

    /** Vrai si la session existe et n'a pas expiré (vérifié à chaque requête authentifiée). */
    public boolean isActive(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        Instant now = clock.instant();
        return sessions.findById(sessionId).map(s -> !s.isExpired(now)).orElse(false);
    }

    private record Parsed(String sessionId, String secret) {
    }

    private static Optional<Parsed> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1 || token.length() > 200) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(token.substring(0, dot), token.substring(dot + 1)));
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
