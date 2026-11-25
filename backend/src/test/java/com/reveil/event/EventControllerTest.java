package com.reveil.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reveil.auth.TokenService;
import com.reveil.config.ApiExceptionHandler;
import com.reveil.config.SecurityConfig;
import com.reveil.session.SessionService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EventController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class})
class EventControllerTest {

    private static final String VALID =
            "{\"title\":\"Concert\",\"date\":\"2026-12-24\",\"time\":\"20:30\",\"timeZone\":\"Europe/Paris\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    EventService eventService;
    @MockitoBean
    TokenService tokenService;
    @MockitoBean
    SessionService sessionService;

    @BeforeEach
    void authenticate() {
        when(tokenService.verify("good")).thenReturn(Optional.of(new TokenService.AccessToken("u1", "a@b.co", "s1")));
        when(sessionService.isActive("s1")).thenReturn(true);
    }

    private static EventResponse response(boolean past) {
        return new EventResponse("e1", "Concert", Instant.parse("2026-12-24T19:30:00Z"),
                LocalDateTime.parse("2026-12-24T20:30:00"), "Europe/Paris", null, past,
                past ? List.of(EventResponse.PAST_WARNING) : List.of());
    }

    @Test
    void createRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
        verify(eventService, never()).create(any(), any());
    }

    @Test
    void createReturns201WithUtcInstantAndTimeZone() throws Exception {
        when(eventService.create(eq("u1"), any())).thenReturn(response(false));

        mvc.perform(post("/api/events").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("e1"))
                .andExpect(jsonPath("$.startsAt").value("2026-12-24T19:30:00Z"))
                .andExpect(jsonPath("$.localDateTime").value("2026-12-24T20:30:00"))
                .andExpect(jsonPath("$.timeZone").value("Europe/Paris"))
                .andExpect(jsonPath("$.past").value(false))
                .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void pastDateStillReturns201WithWarning() throws Exception {
        when(eventService.create(eq("u1"), any())).thenReturn(response(true));

        mvc.perform(post("/api/events").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.past").value(true))
                .andExpect(jsonPath("$.warnings[0]").value(EventResponse.PAST_WARNING));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"date\":\"2026-12-24\",\"time\":\"20:30\",\"timeZone\":\"UTC\"}|title",
            "{\"title\":\"   \",\"date\":\"2026-12-24\",\"time\":\"20:30\",\"timeZone\":\"UTC\"}|title",
            "{\"title\":\"Concert\",\"time\":\"20:30\",\"timeZone\":\"UTC\"}|date",
            "{\"title\":\"Concert\",\"date\":\"2026-12-24\",\"timeZone\":\"UTC\"}|time",
            "{\"title\":\"Concert\",\"date\":\"2026-12-24\",\"time\":\"20:30\"}|timeZone",
            "{\"title\":\"Concert\",\"date\":\"2026-12-24\",\"time\":\"20:30\",\"timeZone\":\"Mars/Olympus\"}|timeZone",
    })
    void missingOrInvalidFieldBlocksCreation(String body, String field) throws Exception {
        mvc.perform(post("/api/events").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(field));
        verify(eventService, never()).create(any(), any());
    }

    @Test
    void malformedDateReturns400() throws Exception {
        mvc.perform(post("/api/events").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Concert\",\"date\":\"24/12/2026\",\"time\":\"20:30\",\"timeZone\":\"UTC\"}"))
                .andExpect(status().isBadRequest());
        verify(eventService, never()).create(any(), any());
    }

    @Test
    void listReturnsTheCurrentUserEvents() throws Exception {
        when(eventService.list("u1")).thenReturn(List.of(response(false)));

        mvc.perform(get("/api/events").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Concert"));
    }
}
