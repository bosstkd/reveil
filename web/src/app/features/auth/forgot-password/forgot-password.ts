import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EMAIL_PATTERN, RESET_REQUESTED_MESSAGE } from '../../../core/auth/auth.models';
import { PasswordResetService } from '../../../core/auth/password-reset.service';

/** Demande d'un lien de réinitialisation. La confirmation ne révèle pas si le compte existe. */
@Component({
  selector: 'app-forgot-password',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './forgot-password.html',
  styleUrl: '../register/register.css',
})
export class ForgotPassword {
  private readonly resets = inject(PasswordResetService);

  protected readonly submitting = signal(false);
  protected readonly sent = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly confirmation = RESET_REQUESTED_MESSAGE;

  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.pattern(EMAIL_PATTERN)]],
  });

  protected submit(): void {
    this.form.markAllAsTouched();
    const email = this.form.getRawValue().email.trim();
    if (!EMAIL_PATTERN.test(email) || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.resets.requestReset(email).subscribe({
      next: () => {
        this.submitting.set(false);
        this.sent.set(true);
      },
      error: () => {
        this.submitting.set(false);
        this.error.set("La demande n'a pas pu être envoyée. Réessayez dans un instant.");
      },
    });
  }
}
