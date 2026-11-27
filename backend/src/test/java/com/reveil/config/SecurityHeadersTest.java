package com.reveil.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reveil.auth.AuthController;
import com.reveil.auth.AuthService;
import com.reveil.auth.TokenService;
import com.reveil.session.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTPS exigé et en-têtes de sécurité (configuration de production : reveil.security.require-https=true). */
@WebMvcTest(AuthController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class})
@TestPropertySource(properties = "reveil.security.require-https=true")
class SecurityHeadersTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    AuthService authService;
    @MockitoBean
    TokenService tokenService;
    @MockitoBean
    SessionService sessionService;

    @Test
    void requeteEnClairRefuseeSansAtteindreLApi() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.co\",\"password\":\"secret123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Connexion HTTPS requise"));
        verify(authService, never()).login(any());
    }

    @Test
    void requeteHttpsAcceptee() throws Exception {
        mvc.perform(post("/api/auth/login").secure(true).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.co\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk());
        verify(authService).login(any());
    }

    @Test
    void enTetesDeSecuriteSurLesReponsesHttps() throws Exception {
        mvc.perform(get("/api/auth/me").secure(true))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", SecurityConfig.PERMISSIONS_POLICY))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
    }

    @Test
    void sondeDeSanteAccessibleEnClair() throws Exception {
        // Pas d'actuator dans ce découpage : 401/404, mais jamais le refus HTTPS.
        mvc.perform(get("/actuator/health"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    if (body.contains(HttpsEnforcementFilter.MESSAGE)) {
                        throw new AssertionError("Sonde de santé refusée en clair");
                    }
                });
    }
}
