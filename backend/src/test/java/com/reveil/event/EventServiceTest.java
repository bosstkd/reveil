package com.reveil.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.reveil.reminder.ReminderSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class EventServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    EventRepository repository;
    ApplicationEventPublisher publisher;
    EventService service;

    @BeforeEach
    void setUp() {
        repository = mock(EventRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        publisher = mock(ApplicationEventPublisher.class);
        service = new EventService(repository, new ReminderSettings(Duration.ofHours(1), Duration.ofSeconds(5)),
                publisher, Clock.fixed(NOW, ZoneOffset.UTC));
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

    @Test
    void upcomingListsFromNowByAscendingDate() {
        Event soon = new Event("Bientôt", NOW.plusSeconds(3600), "UTC", null, "u1");
        when(repository.findByOwnerIdAndStartsAtGreaterThanEqual(eq("u1"), eq(NOW), any()))
                .thenReturn(new PageImpl<>(List.of(soon), PageRequest.of(0, 1), 3));

        EventPage page = service.list("u1", EventPeriod.UPCOMING, 0, 1);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByOwnerIdAndStartsAtGreaterThanEqual(eq("u1"), eq(NOW), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "startsAt"));
        assertThat(page.when()).isEqualTo("upcoming");
        assertThat(page.items()).extracting(EventResponse::title).containsExactly("Bientôt");
        assertThat(page.items().get(0).past()).isFalse();
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void pastListsBeforeNowMostRecentFirst() {
        Event yesterday = new Event("Hier", NOW.minusSeconds(86_400), "UTC", null, "u1");
        when(repository.findByOwnerIdAndStartsAtLessThan(eq("u1"), eq(NOW), any()))
                .thenReturn(new PageImpl<>(List.of(yesterday), PageRequest.of(1, 10), 11));

        EventPage page = service.list("u1", EventPeriod.PAST, 1, 10);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByOwnerIdAndStartsAtLessThan(eq("u1"), eq(NOW), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "startsAt"));
        assertThat(page.when()).isEqualTo("past");
        assertThat(page.items().get(0).past()).isTrue();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void schedulesTheDefaultReminderOneHourBeforeTheEvent() {
        service.create("u1", new CreateEventRequest("Concert", LocalDate.of(2026, 12, 24), LocalTime.of(20, 30),
                "Europe/Paris", null));
        assertThat(saved().getReminderDueAt()).isEqualTo(Instant.parse("2026-12-24T18:30:00Z"));
    }

    @Test
    void eventStartingWithinTheHourGetsAnAlreadyDueReminder() {
        service.create("u1", new CreateEventRequest("Bientôt", LocalDate.of(2026, 11, 25), LocalTime.of(12, 20),
                "UTC", null));
        assertThat(saved().getReminderDueAt()).isEqualTo(Instant.parse("2026-11-25T11:20:00Z")).isBefore(NOW);
    }

    @Test
    void pastEventGetsNoReminder() {
        service.create("u1", new CreateEventRequest("Hier", LocalDate.of(2026, 11, 24), LocalTime.of(8, 0),
                "UTC", null));
        assertThat(saved().getReminderDueAt()).isNull();
    }

    @Test
    void getReturnsTheOwnedEventOrThrowsNotFound() {
        Event e = new Event("Concert", Instant.parse("2026-12-24T19:30:00Z"), "Europe/Paris", null, "u1");
        when(repository.findByIdAndOwnerId("e1", "u1")).thenReturn(Optional.of(e));
        when(repository.findByIdAndOwnerId("e1", "u2")).thenReturn(Optional.empty());

        assertThat(service.get("u1", "e1").title()).isEqualTo("Concert");
        assertThatThrownBy(() -> service.get("u2", "e1")).isInstanceOf(EventNotFoundException.class);
    }

    // --- Modification -------------------------------------------------------------------------------------

    private static final Instant CONCERT = Instant.parse("2026-12-24T19:30:00Z");

    /** Événement « e1 » de u1 au 24/12/2026 20:30 (Paris), stocké en base. */
    private Event existing() {
        Event e = new Event("Concert", CONCERT, "Europe/Paris", "Olympia", "u1");
        e.scheduleReminder(CONCERT.minus(Duration.ofHours(1)));
        when(repository.findById("e1")).thenReturn(Optional.of(e));
        when(repository.applyChanges(eq("e1"), eq("u1"), any())).thenAnswer(inv -> {
            EventChanges c = inv.getArgument(2);
            return Optional.of(new Event(c.title(), c.startsAt(), c.timeZone(), c.location(), "u1"));
        });
        return e;
    }

    private EventChanges applied() {
        ArgumentCaptor<EventChanges> captor = ArgumentCaptor.forClass(EventChanges.class);
        verify(repository).applyChanges(eq("e1"), eq("u1"), captor.capture());
        return captor.getValue();
    }

    @Test
    void updateReplacesEveryEnteredFieldWithTheCreationRules() {
        existing();
        EventResponse res = service.update("u1", "e1", new CreateEventRequest("  Concert annulé ",
                LocalDate.of(2026, 12, 24), LocalTime.of(20, 30), " America/New_York ", "  "));

        EventChanges c = applied();
        assertThat(c.title()).isEqualTo("Concert annulé");
        assertThat(c.timeZone()).isEqualTo("America/New_York");
        assertThat(c.startsAt()).isEqualTo(Instant.parse("2026-12-25T01:30:00Z"));
        assertThat(c.location()).isNull();
        assertThat(res.title()).isEqualTo("Concert annulé");
        assertThat(res.localDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 24, 20, 30));
    }

    @Test
    void newDateRecomputesTheReminderOnTheNewDate() {
        existing();
        service.update("u1", "e1", new CreateEventRequest("Concert", LocalDate.of(2026, 12, 31), LocalTime.of(22, 0),
                "Europe/Paris", "Olympia"));

        EventChanges c = applied();
        assertThat(c.rescheduleReminder()).isTrue();
        assertThat(c.reminderDueAt()).isEqualTo(Instant.parse("2026-12-31T20:00:00Z"));
    }

    @Test
    void sameDateKeepsTheReminderUntouched() {
        existing();
        service.update("u1", "e1", new CreateEventRequest("Autre titre", LocalDate.of(2026, 12, 24),
                LocalTime.of(20, 30), "Europe/Paris", "Bercy"));

        EventChanges c = applied();
        assertThat(c.rescheduleReminder()).isFalse();
        assertThat(c.location()).isEqualTo("Bercy");
    }

    @Test
    void movingTheEventToThePastCancelsItsReminderAndWarns() {
        existing();
        EventResponse res = service.update("u1", "e1", new CreateEventRequest("Concert", LocalDate.of(2026, 11, 1),
                LocalTime.of(20, 30), "Europe/Paris", null));

        EventChanges c = applied();
        assertThat(c.rescheduleReminder()).isTrue();
        assertThat(c.reminderDueAt()).isNull();
        assertThat(res.warnings()).containsExactly(EventResponse.PAST_WARNING);
    }

    @Test
    void updateOfAnotherUsersEventIsForbidden() {
        existing();
        assertThatThrownBy(() -> service.update("u2", "e1", new CreateEventRequest("Pirate",
                LocalDate.of(2026, 12, 24), LocalTime.of(20, 30), "UTC", null)))
                .isInstanceOf(EventAccessDeniedException.class);
        verify(repository, never()).applyChanges(any(), any(), any());
    }

    @Test
    void updateOfAnUnknownOrConcurrentlyDeletedEventIsNotFound() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        CreateEventRequest request = new CreateEventRequest("X", LocalDate.of(2026, 12, 24), LocalTime.of(20, 30),
                "UTC", null);
        assertThatThrownBy(() -> service.update("u1", "nope", request)).isInstanceOf(EventNotFoundException.class);

        existing();
        when(repository.applyChanges(eq("e1"), eq("u1"), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update("u1", "e1", request)).isInstanceOf(EventNotFoundException.class);
    }

    // --- Suppression --------------------------------------------------------------------------------------

    @Test
    void deleteRemovesTheEventWithItsReminderAndAnnouncesIt() {
        existing();
        service.delete("u1", "e1");

        verify(repository).deleteById("e1");
        verify(publisher).publishEvent(new EventDeleted("e1", "u1"));
    }

    @Test
    void deleteOfAnotherUsersEventIsForbiddenAndDeletesNothing() {
        existing();
        assertThatThrownBy(() -> service.delete("u2", "e1")).isInstanceOf(EventAccessDeniedException.class);
        verify(repository, never()).deleteById(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void deleteOfAnUnknownEventIsNotFound() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete("u1", "nope")).isInstanceOf(EventNotFoundException.class);
        verify(repository, never()).deleteById(any());
    }
}
