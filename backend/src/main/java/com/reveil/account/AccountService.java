package com.reveil.account;

import com.reveil.user.User;
import com.reveil.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Suppression du compte (RGPD). Après confirmation du mot de passe :
 * <ol>
 *   <li>une demande {@link AccountDeletion} est enregistrée (échéance : {@code reveil.accounts.purge-delay}, 24 h) ;</li>
 *   <li>le compte et toutes ses sessions sont supprimés : connexion et jetons refusés immédiatement ;</li>
 *   <li>événements (rappels compris), photos (GridFS compris) et jetons d'appareils sont supprimés aussitôt ;</li>
 *   <li>le planificateur rejoue la purge après {@code purge-grace} (écritures encore en vol, échec éventuel)
 *       puis clôt la demande.</li>
 * </ol>
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final UserRepository users;
    private final AccountDeletionRepository deletions;
    private final AccountPurger purger;
    private final PasswordEncoder passwordEncoder;
    private final AccountSettings settings;
    private final Clock clock;

    @Autowired
    public AccountService(UserRepository users, AccountDeletionRepository deletions, AccountPurger purger,
                          PasswordEncoder passwordEncoder, AccountSettings settings) {
        this(users, deletions, purger, passwordEncoder, settings, Clock.systemUTC());
    }

    AccountService(UserRepository users, AccountDeletionRepository deletions, AccountPurger purger,
                   PasswordEncoder passwordEncoder, AccountSettings settings, Clock clock) {
        this.users = users;
        this.deletions = deletions;
        this.purger = purger;
        this.passwordEncoder = passwordEncoder;
        this.settings = settings;
        this.clock = clock;
    }

    /** Supprime le compte {@code userId} si {@code password} est son mot de passe ; sinon 403. */
    public void delete(String userId, String password) {
        User user = users.findById(userId).orElseThrow(IncorrectPasswordException::new);
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IncorrectPasswordException();
        }
        Instant now = clock.instant();
        // La demande est enregistrée avant toute suppression : en cas d'arrêt brutal, la purge reprendra.
        deletions.save(new AccountDeletion(userId, now, now.plus(settings.purgeDelay())));
        users.deleteById(userId);
        try {
            purger.purge(userId);
        } catch (RuntimeException e) {
            // Le compte n'existe plus (aucun accès possible) ; le planificateur terminera la purge.
            log.error("Purge immédiate du compte {} incomplète : reprise par le planificateur", userId, e);
        }
        log.info("Compte {} supprimé", userId);
    }

    /**
     * Passe de contrôle : rejoue la purge des demandes de plus de {@code purge-grace} puis les clôt.
     * Renvoie le nombre de demandes clôturées. Une demande en échec reste en file pour le passage suivant.
     */
    public int purgePending() {
        Instant now = clock.instant();
        List<AccountDeletion> pending = deletions.findByRequestedAtLessThanEqualOrderByRequestedAtAsc(
                now.minus(settings.purgeGrace()));
        int done = 0;
        for (AccountDeletion deletion : pending) {
            try {
                // Recréation impossible : un compte supprimé ne revient pas, mais on s'assure qu'il n'existe plus.
                users.deleteById(deletion.getUserId());
                purger.purge(deletion.getUserId());
                deletions.deleteById(deletion.getUserId());
                done++;
            } catch (RuntimeException e) {
                if (now.isAfter(deletion.getDeadline())) {
                    log.error("Échéance de suppression du compte {} dépassée", deletion.getUserId(), e);
                } else {
                    log.warn("Purge du compte {} en échec, nouvelle tentative au prochain passage",
                            deletion.getUserId(), e);
                }
            }
        }
        return done;
    }
}
