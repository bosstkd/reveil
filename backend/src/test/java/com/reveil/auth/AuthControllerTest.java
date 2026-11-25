package com.reveil.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reveil.config.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(ApiExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuthService authService;

    private static String json(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void registerReturns201WithTokenAndUser() throws Exception {
        when(authService.register(any())).thenReturn(
                new AuthResponse("jwt", new AuthResponse.UserDto("u1", "alice@example.com")));

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
                new AuthResponse("jwt", new AuthResponse.UserDto("u1", "alice@example.com")));
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
}
