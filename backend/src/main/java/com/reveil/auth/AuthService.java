package com.reveil.auth;

import com.reveil.session.InvalidRefreshTokenException;
import com.reveil.session.SessionService;
import com.reveil.user.User;
import com.reveil.user.UserRepository;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final SessionService sessionService;
    /** Hash factice : un e-mail inconnu coûte autant qu'un mauvais mot de passe (pas d'énumération par le temps). */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService,
                       SessionService sessionService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.sessionService = sessionService;
        this.dummyHash = passwordEncoder.encode("reveil-dummy-password-1");
    }

    public AuthResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyUsedException();
        }
        User saved;
        try {
            saved = users.save(new User(email, passwordEncoder.encode(request.password())));
        } catch (DuplicateKeyException e) {
            // Inscription concurrente : l'index unique fait foi.
            throw new EmailAlreadyUsedException();
        }
        return openSession(saved);
    }

    public AuthResponse login(LoginRequest request) {
        if (request == null || isBlank(request.email()) || isBlank(request.password())) {
            throw new InvalidCredentialsException();
        }
        Optional<User> user = users.findByEmail(normalize(request.email()));
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !matches) {
            throw new InvalidCredentialsException();
        }
        return openSession(user.get());
    }

    public AuthResponse refresh(String refreshToken) {
        SessionService.Issued issued = sessionService.refresh(refreshToken);
        User user = users.findById(issued.userId()).orElseThrow(() -> {
            sessionService.revokeById(issued.sessionId());
            return new InvalidRefreshTokenException();
        });
        return response(user, issued);
    }

    /** Déconnexion de l'appareil courant : seule la session désignée est révoquée. */
    public void logout(String refreshToken, AuthenticatedUser current) {
        if (!isBlank(refreshToken)) {
            sessionService.revoke(refreshToken);
        }
        if (current != null) {
            sessionService.revokeById(current.sessionId());
        }
    }

    private AuthResponse openSession(User user) {
        return response(user, sessionService.open(user.getId()));
    }

    private AuthResponse response(User user, SessionService.Issued issued) {
        String token = tokenService.issue(user.getId(), user.getEmail(), issued.sessionId());
        return new AuthResponse(token, tokenService.validity().toSeconds(), issued.refreshToken(),
                new AuthResponse.UserDto(user.getId(), user.getEmail()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
