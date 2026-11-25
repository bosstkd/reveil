package com.reveil.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EventServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    EventRepository repository;
    EventService service;

    @BeforeEach
    void setUp() {
        repository = mock(EventRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new EventService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Event saved() {
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void convertsLocalDateTimeToUtcWithTheUserTimeZone() {
        EventResponse res = service.create("u1", new CreateEventRequest("  Concert ",
                LocalDate.of(2026, 12, 24), LocalTime.of(20, 30), "Europe/Paris", " Olympia "));

        Event e = saved();
        assertThat(e.getStartsAt()).isEqualTo(Instant.parse("2026-12-24T19:30:00Z")); // UTC+1 en hiver
        assertThat(e.getTimeZone()).isEqualTo("Europe/Paris");
        assertThat(e.getTitle()).isEqualTo("Concert");
        assertThat(e.getLocation()).isEqualTo("Olympia");
        assertThat(e.getOwnerId()).isEqualTo("u1");
        assertThat(res.localDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 24, 20, 30));
        assertThat(res.past()).isFalse();
        assertThat(res.warnings()).isEmpty();
    }

    @Test
    void appliesSummerTimeOffset() {
        service.create("u1", new CreateEventRequest("Pique-nique",
                LocalDate.of(2027, 7, 14), LocalTime.of(12, 0), "Europe/Paris", null));
        assertThat(saved().getStartsAt()).isEqualTo(Instant.parse("2027-07-14T10:00:00Z")); // UTC+2
    }

    @Test
    void sameLocalTimeInAnotherZoneGivesAnotherInstant() {
        service.create("u1", new CreateEventRequest("Réunion",
                LocalDate.of(2026, 12, 24), LocalTime.of(20, 30), "America/New_York", null));
        assertThat(saved().getStartsAt()).isEqualTo(Instant.parse("2026-12-25T01:30:00Z"));
    }

    @Test
    void blankLocationIsStoredAsNull() {
        service.create("u1", new CreateEventRequest("Réunion",
                LocalDate.of(2026, 12, 1), LocalTime.of(9, 0), "UTC", "   "));
        assertThat(saved().getLocation()).isNull();
    }

    @Test
    void pastDateIsAcceptedWithAWarning() {
        EventResponse res = service.create("u1", new CreateEventRequest("Hier",
                LocalDate.of(2026, 11, 24), LocalTime.of(10, 0), "Europe/Paris", null));

        assertThat(saved()).isNotNull();
        assertThat(res.past()).isTrue();
        assertThat(res.warnings()).containsExactly(EventResponse.PAST_WARNING);
    }
}
