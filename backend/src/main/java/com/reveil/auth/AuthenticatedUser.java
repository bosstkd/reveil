package com.reveil.auth;

/** Principal Spring Security issu d'un jeton d'accès valide. */
public record AuthenticatedUser(String id, String email, String sessionId) {
}
