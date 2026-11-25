package com.reveil.config;

import com.reveil.auth.AuthenticatedUser;
import com.reveil.auth.TokenService;
import com.reveil.session.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentifie la requête à partir de « Authorization: Bearer &lt;JWT&gt; ».
 * Le jeton doit être valide ET sa session encore active (une déconnexion le rend inutilisable immédiatement).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final TokenService tokens;
    private final SessionService sessions;

    public JwtAuthenticationFilter(TokenService tokens, SessionService sessions) {
        this.tokens = tokens;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            tokens.verify(header.substring(PREFIX.length()).trim())
                    .filter(t -> sessions.isActive(t.sessionId()))
                    .ifPresent(t -> {
                        AuthenticatedUser principal = new AuthenticatedUser(t.userId(), t.email(), t.sessionId());
                        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER")));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
        }
        chain.doFilter(request, response);
    }
}
