package com.reveil.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.reveil.user.User;
import com.reveil.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AccountServiceTest {

    static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    UserRepository users = mock(UserRepository.class);
    AccountDeletionRepository deletions = mock(AccountDeletionRepository.class);
    AccountPurger purger = mock(AccountPurger.class);
    PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    AccountSettings settings = new AccountSettings(Duration.ofHours(24), Duration.ofMinutes(10), "0 */15 * * * *");
    AccountService service = new AccountService(users, deletions, purger, encoder, settings,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void alice() {
        User alice = new User("alice@example.com", encoder.encode("secret123"));
        ReflectionTestUtils.setField(alice, "id", "u1");
        when(users.findById("u1")).thenReturn(Optional.of(alice));
    }

    @Test
    void mauvaisMotDePasseRefuseSansRienSupprimer() {
        assertThatThrownBy(() -> service.delete("u1", "mauvais1")).isInstanceOf(IncorrectPasswordException.class);
        assertThatThrownBy(() -> service.delete("u1", null)).isInstanceOf(IncorrectPasswordException.class);
        verify(users, never()).deleteById(anyString());
        verify(deletions, never()).save(any());
        verify(purger, never()).purge(anyString());
    }

    @Test
    void compteInconnuRefuse() {
        assertThatThrownBy(() -> service.delete("inconnu", "secret123")).isInstanceOf(IncorrectPasswordException.class);
        verify(purger, never()).purge(anyString());
    }

    @Test
    void bonMotDePasseEnregistreLaDemandePuisSupprimeCompteEtDonnees() {
        service.delete("u1", "secret123");

        ArgumentCaptor<AccountDeletion> saved = ArgumentCaptor.forClass(AccountDeletion.class);
        InOrder order = inOrder(deletions, users, purger);
        order.verify(deletions).save(saved.capture());
        order.verify(users).deleteById("u1");
        order.verify(purger).purge("u1");
        assertThat(saved.getValue().getUserId()).isEqualTo("u1");
        assertThat(saved.getValue().getRequestedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getDeadline()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void echecDeLaPurgeImmediateNEmpechePasLaSuppression() {
        doThrow(new DataAccessResourceFailureException("panne")).when(purger).purge("u1");
        service.delete("u1", "secret123");
        verify(users).deleteById("u1");
        // La demande reste enregistrée : le planificateur reprendra.
        verify(deletions, never()).deleteById(anyString());
    }

    @Test
    void passeDeControleRejoueLaPurgeApresLeDelaiDeGraceEtCloLaDemande() {
        when(deletions.findByRequestedAtLessThanEqualOrderByRequestedAtAsc(NOW.minus(Duration.ofMinutes(10))))
                .thenReturn(List.of(new AccountDeletion("u1", NOW.minusSeconds(3600), NOW.plusSeconds(3600))));

        assertThat(service.purgePending()).isEqualTo(1);

        verify(purger).purge("u1");
        verify(deletions).deleteById("u1");
    }

    @Test
    void passeDeControleEnEchecConserveLaDemande() {
        when(deletions.findByRequestedAtLessThanEqualOrderByRequestedAtAsc(any())).thenReturn(List.of(
                new AccountDeletion("u1", NOW.minusSeconds(3600), NOW.plusSeconds(3600)),
                new AccountDeletion("u2", NOW.minusSeconds(3600), NOW.plusSeconds(3600))));
        doThrow(new DataAccessResourceFailureException("panne")).when(purger).purge("u1");

        assertThat(service.purgePending()).isEqualTo(1);

        verify(deletions, never()).deleteById("u1");
        verify(deletions).deleteById("u2");
    }
}
