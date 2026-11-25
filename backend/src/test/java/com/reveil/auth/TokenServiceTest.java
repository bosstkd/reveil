package com.reveil.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

    // Valeur de test uniquement, générée pour ce test (pas un secret réel).
    static final String TEST_KEY = "k".repeat(40);

    @Test
    void issuesAndVerifiesTokenBoundToASession() {
        TokenService tokens = new TokenService(TEST_KEY, Duration.ofMinutes(15));
        String jwt = tokens.issue("u1", "a@b.co", "s1");
        assertThat(tokens.verify(jwt)).contains(new TokenService.AccessToken("u1", "a@b.co", "s1"));
        assertThat(tokens.validity()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void rejectsTamperedExpiredForeignOrSessionlessTokens() {
        TokenService tokens = new TokenService(TEST_KEY, Duration.ofMinutes(15));
        String jwt = tokens.issue("u1", "a@b.co", "s1");
        assertThat(tokens.verify(jwt.substring(0, jwt.length() - 2) + "xx")).isEmpty();
        assertThat(tokens.verify("pas-un-jwt")).isEmpty();
        assertThat(tokens.verify("")).isEmpty();

        TokenService expired = new TokenService(TEST_KEY, Duration.ofSeconds(-5));
        assertThat(tokens.verify(expired.issue("u1", "a@b.co", "s1"))).isEmpty();

        TokenService other = new TokenService("", Duration.ofMinutes(15));
        assertThat(tokens.verify(other.issue("u1", "a@b.co", "s1"))).isEmpty();

        String noSid = Jwts.builder().subject("u1").expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(TEST_KEY.getBytes(StandardCharsets.UTF_8))).compact();
        assertThat(tokens.verify(noSid)).isEmpty();
    }

    @Test
    void refusesShortSecret() {
        assertThatThrownBy(() -> new TokenService("trop-court", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class);
    }
}
