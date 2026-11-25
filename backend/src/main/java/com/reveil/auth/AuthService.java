package com.reveil.auth;

import com.reveil.user.User;
import com.reveil.user.UserRepository;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
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
        String token = tokenService.issue(saved.getId(), saved.getEmail());
        return new AuthResponse(token, new AuthResponse.UserDto(saved.getId(), saved.getEmail()));
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
