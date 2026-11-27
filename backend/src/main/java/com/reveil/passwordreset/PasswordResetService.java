package com.reveil.passwordreset;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.reveil.auth.AuthService;
import com.reveil.mail.EmailMessage;
import com.reveil.mail.EmailSender;
import com.reveil.session.SessionRepository;
import com.reveil.user.User;
import com.reveil.user.UserRepository;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Mot de passe oublié.
 * <ul>
 *   <li>Demande : si le compte existe, un jeton aléatoire (256 bits) est créé — seul son hash SHA-256 est stocké —
 *       et envoyé par e-mail (hors requête). Réponse identique dans tous les cas.</li>
 *   <li>Réinitialisation : le jeton est retiré atomiquement s'il n'a pas expiré ({@code findAndRemove} : un seul
 *       usage, même en cas de requêtes simultanées), puis le hash du mot de passe est remplacé et toutes les
 *       sessions du compte sont révoquées. Photos et événements ne sont pas touchés.</li>
 * </ul>
 */
@Service
public class PasswordResetService {

    public static final String CONFIRMATION_MESSAGE =
            "Si un compte est associé à cet e-mail, un lien de réinitialisation vient de lui être envoyé. "
                    + "Il est valable 1 heure.";
    static final String SUBJECT = "Réinitialisation de votre mot de passe Reveil";

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final SessionRepository sessions;
    private final MongoTemplate mongo;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final Executor mailExecutor;
    private final PasswordResetSettings settings;
    private final Clock clock;

    @Autowired
    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens, SessionRepository sessions,
                                MongoTemplate mongo, PasswordEncoder passwordEncoder, EmailSender emailSender,
                                PasswordResetSettings settings) {
        this(users, tokens, sessions, mongo, passwordEncoder, emailSender, newMailExecutor(), settings,
                Clock.systemUTC());
    }

    PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens, SessionRepository sessions,
                         MongoTemplate mongo, PasswordEncoder passwordEncoder, EmailSender emailSender,
                         Executor mailExecutor, PasswordResetSettings settings, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.sessions = sessions;
        this.mongo = mongo;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.mailExecutor = mailExecutor;
        this.settings = settings;
        this.clock = clock;
    }

    /** Envoie un lien si le compte existe ; sinon ne fait rien. Ne révèle jamais l'existence du compte. */
    public void requestReset(String email) {
        Optional<User> found = users.findByEmail(AuthService.normalize(email));
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();
        Instant now = clock.instant();
        if (tokens.countByUserIdAndExpiresAtAfter(user.getId(), now) >= settings.maxActiveLinks()) {
            log.info("Réinitialisation du mot de passe : trop de liens actifs pour le compte {}, demande ignorée",
                    user.getId());
            return;
        }
        String token = newToken();
        tokens.save(new PasswordResetToken(hash(token), user.getId(), now, now.plus(settings.validity())));
        EmailMessage message = new EmailMessage(user.getEmail(), SUBJECT, body(token));
        try {
            mailExecutor.execute(() -> send(message, user.getId()));
        } catch (RejectedExecutionException e) {
            log.error("Réinitialisation du mot de passe : file d'envoi saturée, e-mail du compte {} abandonné",
                    user.getId());
        }
    }

    /** Remplace le mot de passe si le jeton est valide (non expiré, jamais utilisé) ; sinon 400. */
    public void resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new InvalidResetTokenException();
        }
        Instant now = clock.instant();
        PasswordResetToken consumed = mongo.findAndRemove(
                query(where("_id").is(hash(token.trim())).and("expiresAt").gt(now)), PasswordResetToken.class);
        if (consumed == null) {
            throw new InvalidResetTokenException();
        }
        User updated = mongo.findAndModify(query(where("id").is(consumed.getUserId())),
                new Update().set("passwordHash", passwordEncoder.encode(newPassword)), User.class);
        if (updated == null) {
            // Compte supprimé entre-temps.
            throw new InvalidResetTokenException();
        }
        // Les autres liens envoyés deviennent inutilisables ; tous les appareils doivent se reconnecter.
        tokens.deleteByUserId(updated.getId());
        sessions.deleteByUserId(updated.getId());
        log.info("Mot de passe du compte {} réinitialisé", updated.getId());
    }

    /**
     * E-mails envoyés hors de la requête : le temps de réponse ne dépend ni de l'existence du compte ni du serveur
     * SMTP. File bornée : au-delà, l'envoi est abandonné (journalisé) plutôt que de saturer la mémoire.
     */
    private static ExecutorService newMailExecutor() {
        AtomicInteger count = new AtomicInteger();
        return new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(500), r -> {
            Thread t = new Thread(r, "reset-mail-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        if (mailExecutor instanceof ExecutorService service) {
            service.shutdown();
        }
    }

    private void send(EmailMessage message, String userId) {
        try {
            emailSender.send(message);
        } catch (RuntimeException e) {
            log.error("Échec de l'envoi de l'e-mail de réinitialisation du compte {}", userId, e);
        }
    }

    private String body(String token) {
        String web = settings.webUrl().replaceAll("/+$", "") + "/reinitialiser-mot-de-passe?token=" + token;
        String mobile = settings.mobileUrl() + "?token=" + token;
        long minutes = settings.validity().toMinutes();
        return "Bonjour,\n\n"
                + "Vous avez demandé à réinitialiser le mot de passe de votre compte Reveil.\n"
                + "Pour choisir un nouveau mot de passe, ouvrez ce lien :\n\n"
                + web + "\n\n"
                + "Depuis l'application mobile Reveil : " + mobile + "\n\n"
                + "Ce lien est valable " + duration(minutes)
                + " et ne peut servir qu'une seule fois. Vos événements et vos photos sont conservés.\n\n"
                + "Si vous n'êtes pas à l'origine de cette demande, ignorez cet e-mail : votre mot de passe reste "
                + "inchangé.\n\n— Reveil\n";
    }

    private static String duration(long minutes) {
        if (minutes == 60) {
            return "1 heure";
        }
        return minutes % 60 == 0 ? (minutes / 60) + " heures" : minutes + " minutes";
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
