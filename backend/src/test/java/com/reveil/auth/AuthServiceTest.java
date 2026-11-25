package com.reveil.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.reveil.session.SessionService;
import com.reveil.user.User;
import com.reveil.user.UserRepository;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

    UserRepository users;
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    TokenService tokens = new TokenService("", Duration.ofHours(1));
    SessionService sessions;
    AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        sessions = mock(SessionService.class);
        when(sessions.open(any())).thenAnswer(inv ->
                new SessionService.Issued("sid-1", inv.getArgument(0), "sid-1.secret"));
        service = new AuthService(users, encoder, tokens, sessions);
        when(users.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", "id-1");
            return u;
        });
    }

    @Test
    void hashesPasswordWithBcryptAndNormalizesEmail() {
        AuthResponse res = service.register(new RegisterRequest("  Alice@Example.COM ", "motdepasse1"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getValue().getPasswordHash())
                .isNotEqualTo("motdepasse1")
                .startsWith("$2");
        assertThat(encoder.matches("motdepasse1", saved.getValue().getPasswordHash())).isTrue();

        assertThat(res.user().email()).isEqualTo("alice@example.com");
        assertThat(tokens.parseSubject(res.token())).isEqualTo("id-1");
        assertThat(res.refreshToken()).isEqualTo("sid-1.secret");
        assertThat(tokens.verify(res.token())).get().extracting(TokenService.AccessToken::sessionId).isEqualTo("sid-1");
    }

    @Test
    void rejectsExistingEmail() {
        when(users.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterRequest("ALICE@example.com", "motdepasse1")))
                .isInstanceOf(EmailAlreadyUsedException.class)
                .hasMessage("Un compte existe déjà avec cet e-mail");
        verify(users, never()).save(any());
    }

    @Test
    void translatesConcurrentDuplicateKey() {
        org.mockito.Mockito.doThrow(new DuplicateKeyException("E11000")).when(users).save(any());

        assertThatThrownBy(() -> service.register(new RegisterRequest("alice@example.com", "motdepasse1")))
                .isInstanceOf(EmailAlreadyUsedException.class);
    }

    // ---------- Connexion ----------

    private User existing(String email, String password) {
        User u = new User(email, encoder.encode(password));
        ReflectionTestUtils.setField(u, "id", "id-7");
        when(users.findByEmail(email)).thenReturn(java.util.Optional.of(u));
        return u;
    }

    @Test
    void loginWithValidCredentialsOpensSessionAndReturnsTokens() {
        existing("alice@example.com", "motdepasse1");

        AuthResponse res = service.login(new LoginRequest("  ALICE@example.com ", "motdepasse1"));

        assertThat(res.user().id()).isEqualTo("id-7");
        assertThat(res.refreshToken()).isEqualTo("sid-1.secret");
        assertThat(res.expiresIn()).isEqualTo(3600);
        TokenService.AccessToken t = tokens.verify(res.token()).orElseThrow();
        assertThat(t.userId()).isEqualTo("id-7");
        assertThat(t.email()).isEqualTo("alice@example.com");
        assertThat(t.sessionId()).isEqualTo("sid-1");
        verify(sessions).open("id-7");
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameGenericError() {
        existing("alice@example.com", "motdepasse1");

        assertThatThrownBy(() -> service.login(new LoginRequest("alice@example.com", "mauvais123")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("E-mail ou mot de passe incorrect");
        assertThatThrownBy(() -> service.login(new LoginRequest("inconnu@example.com", "motdepasse1")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("E-mail ou mot de passe incorrect");
        verify(sessions, never()).open(any());
    }

    @Test
    void blankCredentialsGiveTheSameGenericError() {
        for (LoginRequest r : new LoginRequest[] {new LoginRequest(null, "x"), new LoginRequest("a@b.co", ""),
                new LoginRequest(" ", " "), null}) {
            assertThatThrownBy(() -> service.login(r)).isInstanceOf(InvalidCredentialsException.class);
        }
    }

    @Test
    void logoutRevokesTheCurrentSessionOnly() {
        service.logout("sid-1.secret", new AuthenticatedUser("id-7", "a@b.co", "sid-1"));
        verify(sessions).revoke("sid-1.secret");
        verify(sessions).revokeById("sid-1");
    }
}
