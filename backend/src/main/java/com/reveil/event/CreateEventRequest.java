package com.reveil.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Création ({@code POST}) ou modification complète ({@code PUT}) d'un événement : date et heure locales de l'utilisateur + son fuseau IANA.
 * Le serveur en déduit l'instant UTC.
 *
 * @param reminders délais des rappels en minutes (au plus 3 parmi 15, 60, 1440, 10080) ; liste vide : aucun rappel ;
 *                  absent : rappel par défaut (1 h) à la création, configuration inchangée en modification
 */
public record CreateEventRequest(
        @NotBlank(message = "Le titre est obligatoire")
        @Size(max = 200, message = "Le titre ne doit pas dépasser 200 caractères")
        String title,

        @NotNull(message = "La date est obligatoire")
        LocalDate date,

        @NotNull(message = "L'heure est obligatoire")
        LocalTime time,

        @NotBlank(message = "Le fuseau horaire est obligatoire")
        @ValidTimeZone
        String timeZone,

        @Size(max = 300, message = "Le lieu ne doit pas dépasser 300 caractères")
        String location,

        @ValidReminders
        List<Integer> reminders) {

    /** Sans choix de rappels (délai par défaut / configuration conservée). */
    public CreateEventRequest(String title, LocalDate date, LocalTime time, String timeZone, String location) {
        this(title, date, time, timeZone, location, null);
    }
}
