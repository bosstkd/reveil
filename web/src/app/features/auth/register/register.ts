import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import {
  ApiError,
  EMAIL_ALREADY_USED_MESSAGE,
  EMAIL_PATTERN,
  PASSWORD_DIGIT_PATTERN,
  PASSWORD_MIN_LENGTH,
} from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule],
  templateUrl: './register.html',
  styleUrl: './register.css',
})
export class Register {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly submitting = signal(false);
  protected readonly serverError = signal<string | null>(null);
  protected readonly minLength = PASSWORD_MIN_LENGTH;

  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.pattern(EMAIL_PATTERN)]],
    password: [
      '',
      [Validators.required, Validators.minLength(PASSWORD_MIN_LENGTH), Validators.pattern(PASSWORD_DIGIT_PATTERN)],
    ],
  });

  protected submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.serverError.set(null);
    const { email, password } = this.form.getRawValue();
    this.auth.register(email.trim(), password).subscribe({
      next: () => this.router.navigateByUrl('/evenements'),
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.serverError.set(messageFor(err));
      },
    });
  }
}

function messageFor(err: HttpErrorResponse): string {
  if (err.status === 409) {
    return EMAIL_ALREADY_USED_MESSAGE;
  }
  const body = err.error as ApiError | null;
  if (err.status === 400 && body?.errors?.length) {
    return body.errors.map((e) => e.message).join(' · ');
  }
  return "L'inscription a échoué. Réessayez dans un instant.";
}
