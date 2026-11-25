package com.reveil.event;

import com.reveil.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Événements de l'utilisateur connecté (routes protégées par SecurityConfig). */
@RestController
@RequestMapping("/api/events")
public class EventController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse create(@Valid @RequestBody CreateEventRequest request,
                                @AuthenticationPrincipal AuthenticatedUser current) {
        return eventService.create(current.id(), request);
    }

    /**
     * {@code GET /api/events?when=upcoming|past&page=0&size=20} : liste paginée de l'utilisateur connecté.
     */
    @GetMapping
    public EventPage list(@RequestParam(defaultValue = "upcoming") String when,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
                          @AuthenticationPrincipal AuthenticatedUser current) {
        EventPeriod period = EventPeriod.parse(when).orElseThrow(() ->
                new InvalidListQueryException("when", "Le filtre doit valoir « upcoming » ou « past »"));
        if (page < 0) {
            throw new InvalidListQueryException("page", "Le numéro de page doit être positif ou nul");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidListQueryException("size", "La taille de page doit être comprise entre 1 et " + MAX_PAGE_SIZE);
        }
        return eventService.list(current.id(), period, page, size);
    }
}
