package com.reveil.auth;

/**
 * Réponse d'ouverture ou de renouvellement de session.
 *
 * @param token        jeton d'accès JWT (Authorization: Bearer), courte durée
 * @param expiresIn    durée de validité du jeton d'accès, en secondes
 * @param refreshToken jeton opaque de rafraîchissement (rotation à chaque usage)
 */
public record AuthResponse(String token, long expiresIn, String refreshToken, UserDto user) {

    public record UserDto(String id, String email) {
    }
}
