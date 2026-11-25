import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from '../../../core/auth/auth.models';
import {
  LOCATION_MAX_LENGTH,
  PAST_DATE_WARNING,
  ReveilEvent,
  TITLE_MAX_LENGTH,
  isPast,
  userTimeZone,
} from '../../../core/events/event.models';
import { EventService } from '../../../core/events/event.service';

/** Formulaire de création d'un événement (titre, date, heure obligatoires ; lieu facultatif). */
@Component({
  selector: 'app-event-form',
  imports: [ReactiveFormsModule],
  templateUrl: './event-form.html',
  styleUrl: './event-form.css',
})
export class EventForm {
  private readonly events = inject(EventService);

  readonly created = output<ReveilEvent>();
  readonly cancelled = output<void>();

  protected readonly submitting = signal(false);
  protected readonly serverError = signal<string | null>(null);
  protected readonly timeZone = userTimeZone();
  protected readonly titleMax = TITLE_MAX_LENGTH;
  protected readonly pastWarning = PAST_DATE_WARNING;

  protected readonly form = inject(NonNullableFormBuilder).group({
    title: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(TITLE_MAX_LENGTH)]],
    date: ['', Validators.required],
    time: ['', Validators.required],
    location: ['', Validators.maxLength(LOCATION_MAX_LENGTH)],
  });

  private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

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
    this.events
      .create({ title: title.trim(), date, time, timeZone: this.timeZone, location: location.trim() || null })
      .subscribe({
        next: (event) => {
          this.submitting.set(false);
          this.form.reset();
          this.created.emit(event);
        },
        error: (err: HttpErrorResponse) => {
          this.submitting.set(false);
          const body = err.error as ApiError | null;
          this.serverError.set(
            err.status === 400 && body?.errors?.length
              ? body.errors.map((e) => e.message).join(' · ')
              : "La création de l'événement a échoué. Réessayez dans un instant.",
          );
        },
      });
  }
}
