import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from '../../../core/auth/auth.models';
import {
  DEFAULT_REMINDERS,
  EVENT_FORBIDDEN,
  MAX_REMINDERS,
  REMINDER_OPTIONS,
  LOCATION_MAX_LENGTH,
  PAST_DATE_WARNING,
  PAST_DATE_WARNING_EDIT,
  ReveilEvent,
  TITLE_MAX_LENGTH,
  configuredReminders,
  isPast,
  reminderDelay,
  toggleReminder,
  userTimeZone,
} from '../../../core/events/event.models';
import { EventService } from '../../../core/events/event.service';

/**
 * Formulaire de création — ou de modification si `event` est fourni — d'un événement
 * (titre, date, heure obligatoires ; lieu facultatif ; jusqu'à 3 rappels, aucun pour les désactiver).
 */
@Component({
  selector: 'app-event-form',
  imports: [ReactiveFormsModule],
  templateUrl: './event-form.html',
  styleUrl: './event-form.css',
})
export class EventForm implements OnInit {
  private readonly events = inject(EventService);

  /** Événement à modifier (absent : création). */
  readonly event = input<ReveilEvent | null>(null);

  readonly created = output<ReveilEvent>();
  readonly updated = output<ReveilEvent>();
  /** Modification impossible : l'événement n'existe plus (404). */
  readonly gone = output<string>();
  readonly cancelled = output<void>();

  protected readonly submitting = signal(false);
  protected readonly serverError = signal<string | null>(null);
  /** Création : fuseau du navigateur ; modification : fuseau de saisie de l'événement (date/heure affichées dans celui-ci). */
  protected timeZone = userTimeZone();
  protected readonly titleMax = TITLE_MAX_LENGTH;
  protected readonly reminderOptions = REMINDER_OPTIONS;
  protected readonly maxReminders = MAX_REMINDERS;
  protected readonly reminderDelay = reminderDelay;
  /** Délais de rappel choisis (minutes), du plus éloigné au plus proche. */
  protected readonly reminders = signal<number[]>([...DEFAULT_REMINDERS]);

  protected readonly form = inject(NonNullableFormBuilder).group({
    title: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(TITLE_MAX_LENGTH)]],
    date: ['', Validators.required],
    time: ['', Validators.required],
    location: ['', Validators.maxLength(LOCATION_MAX_LENGTH)],
  });

  private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

  ngOnInit(): void {
    const event = this.event();
    if (event) {
      this.timeZone = event.timeZone;
      this.form.setValue({
        title: event.title,
        date: event.localDateTime.slice(0, 10),
        time: event.localDateTime.slice(11, 16),
        location: event.location ?? '',
      });
      this.reminders.set(configuredReminders(event));
    }
  }

  protected isReminderChosen(minutes: number): boolean {
    return this.reminders().includes(minutes);
  }

  /** Case non cochée alors que le maximum est atteint : non sélectionnable. */
  protected isReminderLocked(minutes: number): boolean {
    return !this.isReminderChosen(minutes) && this.reminders().length >= MAX_REMINDERS;
  }

  protected toggleReminder(minutes: number): void {
    this.reminders.update((selected) => toggleReminder(selected, minutes));
  }

  protected disableReminders(): void {
    this.reminders.set([]);
  }

  protected pastWarning(): string {
    return this.editing() ? PAST_DATE_WARNING_EDIT : PAST_DATE_WARNING;
  }

  protected editing(): boolean {
    return this.event() !== null;
  }

  /** Avertissement non bloquant : une date passée reste acceptée. */
  protected isPast(): boolean {
    const { date, time } = this.value();
    return isPast(date ?? '', time ?? '');
  }

  protected submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.serverError.set(null);
    const { title, date, time, location } = this.form.getRawValue();
    const payload = {
      title: title.trim(),
      date,
      time,
      timeZone: this.timeZone,
      location: location.trim() || null,
      reminders: this.reminders(),
    };
    const editing = this.event();
    const request = editing ? this.events.update(editing.id, payload) : this.events.create(payload);
    request.subscribe({
      next: (event) => {
        this.submitting.set(false);
        if (editing) {
          this.updated.emit(event);
        } else {
          this.form.reset();
          this.reminders.set([...DEFAULT_REMINDERS]);
          this.created.emit(event);
        }
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        const body = err.error as ApiError | null;
        if (editing && err.status === 404) {
          this.events.forget(editing.id);
          this.gone.emit(editing.id);
          return;
        }
        this.serverError.set(
          err.status === 400 && body?.errors?.length
            ? body.errors.map((e) => e.message).join(' · ')
            : editing && err.status === 403
              ? EVENT_FORBIDDEN
              : editing
                ? "La modification de l'événement a échoué. Réessayez dans un instant."
                : "La création de l'événement a échoué. Réessayez dans un instant.",
        );
      },
    });
  }
}
