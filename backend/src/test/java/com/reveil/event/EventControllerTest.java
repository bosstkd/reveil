package com.reveil.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                past ? List.of(EventResponse.PAST_WARNING) : List.of(),
                past ? List.of() : List.of(new EventReminder(60, Instant.parse("2026-12-24T18:30:00Z"), "scheduled")));
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
    void listRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/events")).andExpect(status().isUnauthorized());
        verify(eventService, never()).list(any(), any(), anyInt(), anyInt());
    }

    @Test
    void listDefaultsToUpcomingFirstPageOfTheCurrentUser() throws Exception {
        when(eventService.list("u1", EventPeriod.UPCOMING, 0, EventController.DEFAULT_PAGE_SIZE))
                .thenReturn(new EventPage("upcoming", List.of(response(false)), 0, 20, 1, false));

        mvc.perform(get("/api/events").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.when").value("upcoming"))
                .andExpect(jsonPath("$.items[0].title").value("Concert"))
                .andExpect(jsonPath("$.items[0].startsAt").value("2026-12-24T19:30:00Z"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void listPassesFilterAndPagination() throws Exception {
        when(eventService.list("u1", EventPeriod.PAST, 2, 100))
                .thenReturn(new EventPage("past", List.of(response(true)), 2, 100, 201, false));

        mvc.perform(get("/api/events").param("when", "PAST").param("page", "2").param("size", "100")
                        .header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.when").value("past"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items[0].past").value(true));
    }

    @ParameterizedTest
    @CsvSource({"when,later", "page,-1", "size,0", "size,101", "page,abc"})
    void invalidListParameterReturns400(String name, String value) throws Exception {
        mvc.perform(get("/api/events").param(name, value).header("Authorization", "Bearer good"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(name));
        verify(eventService, never()).list(any(), any(), anyInt(), anyInt());
    }

    @Test
    void getReturnsTheEventOfTheCurrentUser() throws Exception {
        when(eventService.get("u1", "e1")).thenReturn(response(false));
        mvc.perform(get("/api/events/e1").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("e1"))
                .andExpect(jsonPath("$.title").value("Concert"))
                .andExpect(jsonPath("$.startsAt").value("2026-12-24T19:30:00Z"))
                .andExpect(jsonPath("$.localDateTime").value("2026-12-24T20:30:00"))
                .andExpect(jsonPath("$.timeZone").value("Europe/Paris"))
                .andExpect(jsonPath("$.reminders[0].minutesBefore").value(60))
                .andExpect(jsonPath("$.reminders[0].remindAt").value("2026-12-24T18:30:00Z"))
                .andExpect(jsonPath("$.reminders[0].status").value("scheduled"));
    }

    @Test
    void getUnknownOrDeletedEventReturns404() throws Exception {
        when(eventService.get("u1", "gone")).thenThrow(new EventNotFoundException());
        mvc.perform(get("/api/events/gone").header("Authorization", "Bearer good"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Événement introuvable"));
    }

    @Test
    void getRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/events/e1")).andExpect(status().isUnauthorized());
    }

    @Test
    void updateReturns200WithTheUpdatedEvent() throws Exception {
        when(eventService.update(eq("u1"), eq("e1"), any())).thenReturn(response(false));

        mvc.perform(put("/api/events/e1").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("e1"))
                .andExpect(jsonPath("$.startsAt").value("2026-12-24T19:30:00Z"));
        verify(eventService).update(eq("u1"), eq("e1"),
                eq(new CreateEventRequest("Concert", java.time.LocalDate.of(2026, 12, 24),
                        java.time.LocalTime.of(20, 30), "Europe/Paris", null)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"date\":\"2026-12-24\",\"time\":\"20:30\",\"timeZone\":\"UTC\"}|title",
            "{\"title\":\"A\",\"time\":\"20:30\",\"timeZone\":\"UTC\"}|date",
            "{\"title\":\"A\",\"date\":\"2026-12-24\",\"timeZone\":\"UTC\"}|time",
            "{\"title\":\"A\",\"date\":\"2026-12-24\",\"time\":\"20:30\",\"timeZone\":\"Mars/Base\"}|timeZone",
    })
    void updateIsValidatedLikeCreation(String body, String field) throws Exception {
        mvc.perform(put("/api/events/e1").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(field));
        verify(eventService, never()).update(any(), any(), any());
    }

    @Test
    void updateOfAnotherUsersEventReturns403() throws Exception {
        when(eventService.update(eq("u1"), eq("e2"), any())).thenThrow(new EventAccessDeniedException());
        mvc.perform(put("/api/events/e2").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Vous n'êtes pas autorisé à modifier cet événement"));
    }

    @Test
    void updateOfAnUnknownEventReturns404() throws Exception {
        when(eventService.update(eq("u1"), eq("gone"), any())).thenThrow(new EventNotFoundException());
        mvc.perform(put("/api/events/gone").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/events/e1").header("Authorization", "Bearer good"))
                .andExpect(status().isNoContent());
        verify(eventService).delete("u1", "e1");
    }

    @Test
    void deleteOfAnotherUsersEventReturns403() throws Exception {
        doThrow(new EventAccessDeniedException()).when(eventService).delete("u1", "e2");
        mvc.perform(delete("/api/events/e2").header("Authorization", "Bearer good"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteOfAnUnknownEventReturns404() throws Exception {
        doThrow(new EventNotFoundException()).when(eventService).delete("u1", "gone");
        mvc.perform(delete("/api/events/gone").header("Authorization", "Bearer good"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateAndDeleteRequireAuthentication() throws Exception {
        mvc.perform(put("/api/events/e1").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/events/e1")).andExpect(status().isUnauthorized());
        verify(eventService, never()).update(any(), any(), any());
        verify(eventService, never()).delete(any(), any());
    }
}
