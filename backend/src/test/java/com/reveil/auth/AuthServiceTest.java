package com.reveil.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        service = new AuthService(users, encoder, tokens);
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
}
