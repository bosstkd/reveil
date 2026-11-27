package com.reveil.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reveil.config.ApiExceptionHandler;
import com.reveil.config.SecurityConfig;
import com.reveil.session.SessionService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.reveil.session.InvalidRefreshTokenException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class})
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuthService authService;
    @MockitoBean
    TokenService tokenService;
    @MockitoBean
    SessionService sessionService;

    private static String json(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void registerReturns201WithTokenAndUser() throws Exception {
        when(authService.register(any())).thenReturn(
                new AuthResponse("jwt", 900, "sid.secret", new AuthResponse.UserDto("u1", "alice@example.com")));

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("alice@example.com", "motdepasse1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt"))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "alice", "alice@", "alice@example", "a b@example.com"})
    void rejectsInvalidEmail(String email) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, "motdepasse1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        verify(authService, never()).register(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "court1", "bcdefg1", "sanschiffre"})
    void rejectsWeakPassword(String password) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("alice@example.com", password)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        verify(authService, never()).register(any());
    }

    @Test
    void acceptsPasswordOfExactly8CharsWithOneDigit() throws Exception {
        when(authService.register(any())).thenReturn(
                new AuthResponse("jwt", 900, "sid.secret", new AuthResponse.UserDto("u1", "alice@example.com")));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("alice@example.com", "abcdefg1")))
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateEmailReturns409WithExpectedMessage() throws Exception {
        when(authService.register(any())).thenThrow(new EmailAlreadyUsedException());

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("alice@example.com", "motdepasse1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Un compte existe déjà avec cet e-mail"));
    }

    @Test
    void malformedBodyReturns400() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    // ---------- Connexion / session ----------

    @Test
    void loginReturns200WithTokens() throws Exception {
        when(authService.login(any())).thenReturn(
                new AuthResponse("jwt", 900, "sid.secret", new AuthResponse.UserDto("u1", "alice@example.com")));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("alice@example.com", "motdepasse1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt"))
                .andExpect(jsonPath("$.refreshToken").value("sid.secret"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.id").value("u1"));
    }

    @Test
    void invalidLoginReturns401WithGenericMessageAndNoField() throws Exception {
        when(authService.login(any())).thenThrow(new InvalidCredentialsException());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("alice@example.com", "mauvais")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("E-mail ou mot de passe incorrect"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void refreshReturnsNewTokensOr401() throws Exception {
        when(authService.refresh("sid.ok")).thenReturn(
                new AuthResponse("jwt2", 900, "sid.new", new AuthResponse.UserDto("u1", "alice@example.com")));
        when(authService.refresh("sid.ko")).thenThrow(new InvalidRefreshTokenException());

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"sid.ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("sid.new"));
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"sid.ko\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutIsPublicAndReturns204() throws Exception {
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"sid.secret\"}"))
                .andExpect(status().isNoContent());
        verify(authService).logout(eq("sid.secret"), isNull());
    }

    @Test
    void meRequiresAValidBearerTokenOnAnActiveSession() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentification requise"));

        when(tokenService.verify("good")).thenReturn(Optional.of(new TokenService.AccessToken("u1", "a@b.co", "s1")));
        when(sessionService.isActive("s1")).thenReturn(true);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("u1"))
                .andExpect(jsonPath("$.email").value("a@b.co"));

        when(sessionService.isActive("s1")).thenReturn(false);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer good"))
                .andExpect(status().isUnauthorized());

        when(tokenService.verify("bad")).thenReturn(Optional.empty());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer bad"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherApiEndpointsAreProtected() throws Exception {
        mvc.perform(get("/api/events")).andExpect(status().isUnauthorized());
    }
}
