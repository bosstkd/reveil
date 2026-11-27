package com.reveil.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.reveil.mail.EmailMessage;
import com.reveil.mail.EmailSender;
import com.reveil.session.SessionRepository;
import com.reveil.user.User;
import com.reveil.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class PasswordResetServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    UserRepository users = mock(UserRepository.class);
    PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
    SessionRepository sessions = mock(SessionRepository.class);
    MongoTemplate mongo = mock(MongoTemplate.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    EmailSender sender = mock(EmailSender.class);
    PasswordResetService service;
    User alice;

    @BeforeEach
    void setUp() {
        PasswordResetSettings settings = new PasswordResetSettings(Duration.ofHours(1), 3,
                "https://reveil.example.com/", "reveil://reinitialiser-mot-de-passe");
        // Exécuteur synchrone : l'envoi est observable immédiatement.
        service = new PasswordResetService(users, tokens, sessions, mongo, encoder, sender, Runnable::run, settings,
                Clock.fixed(NOW, ZoneOffset.UTC));
        alice = new User("alice@example.com", "hash");
        ReflectionTestUtils.setField(alice, "id", "u1");
    }

    @Test
    void compteInconnuAucunJetonNiEmail() {
        when(users.findByEmail("personne@example.com")).thenReturn(Optional.empty());
        service.requestReset(" Personne@Example.com ");
        verify(tokens, never()).save(any());
        verifyNoInteractions(sender);
    }

    @Test
    void compteExistantJetonDUneHeureEtLienDansLEmail() {
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        service.requestReset("alice@example.com");

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokens).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo("u1");
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));

        ArgumentCaptor<EmailMessage> mail = ArgumentCaptor.forClass(EmailMessage.class);
        verify(sender).send(mail.capture());
        String body = mail.getValue().body();
        String token = body.replaceAll("(?s).*reinitialiser-mot-de-passe\\?token=([A-Za-z0-9_-]+).*", "$1");
        assertThat(token).hasSize(43); // 256 bits en base64url
        assertThat(PasswordResetService.hash(token)).isEqualTo(saved.getValue().getTokenHash());
        assertThat(body).contains("https://reveil.example.com/reinitialiser-mot-de-passe?token=" + token)
                .contains("reveil://reinitialiser-mot-de-passe?token=" + token)
                .contains("valable 1 heure");
        assertThat(mail.getValue().toString()).doesNotContain(token);
    }

    @Test
    void tropDeLiensActifsDemandeIgnoree() {
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(tokens.countByUserIdAndExpiresAtAfter(eq("u1"), eq(NOW))).thenReturn(3L);
        service.requestReset("alice@example.com");
        verify(tokens, never()).save(any());
        verifyNoInteractions(sender);
    }

    @Test
    void echecDEnvoiSansErreurPourLeDemandeur() {
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        doThrow(new IllegalStateException("SMTP indisponible")).when(sender).send(any());
        service.requestReset("alice@example.com"); // aucune exception : réponse identique
        verify(sender).send(any());
    }

    @Test
    void jetonVideRefuse() {
        assertThatThrownBy(() -> service.resetPassword(" ", "nouveau456"))
                .isInstanceOf(InvalidResetTokenException.class);
        verifyNoInteractions(mongo);
    }
}
