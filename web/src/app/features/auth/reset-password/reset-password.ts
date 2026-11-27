import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  ApiError,
  INVALID_RESET_LINK_MESSAGE,
  PASSWORD_DIGIT_PATTERN,
  PASSWORD_MIN_LENGTH,
} from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { PasswordResetService } from '../../../core/auth/password-reset.service';

/** Saisie du nouveau mot de passe depuis le lien reçu par e-mail (`?token=...`). */
@Component({
  selector: 'app-reset-password',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './reset-password.html',
  styleUrl: '../register/register.css',
})
export class ResetPassword {
  private readonly resets = inject(PasswordResetService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Jeton lu une fois puis retiré de la barre d'adresse (historique, captures d'écran). */
  private readonly token = inject(ActivatedRoute).snapshot.queryParamMap.get('token')?.trim() ?? '';

  protected readonly minLength = PASSWORD_MIN_LENGTH;
  protected readonly submitting = signal(false);
  /** Lien absent, expiré ou déjà utilisé : nouvelle demande proposée. */
  protected readonly linkInvalid = signal(this.token === '');
  protected readonly serverError = signal<string | null>(null);
  protected readonly invalidLinkMessage = INVALID_RESET_LINK_MESSAGE;

  protected readonly form = inject(NonNullableFormBuilder).group({
    password: [
      '',
      [Validators.required, Validators.minLength(PASSWORD_MIN_LENGTH), Validators.pattern(PASSWORD_DIGIT_PATTERN)],
    ],
    confirm: ['', Validators.required],
  });

  constructor() {
    if (this.token) {
      void this.router.navigate([], { queryParams: {}, replaceUrl: true });
    }
  }

  protected get confirmError(): string | null {
    const { password, confirm } = this.form.getRawValue();
    if (!this.form.controls.confirm.touched) return null;
    if (!confirm) return 'Confirmez le nouveau mot de passe.';
    return password !== confirm ? 'Les deux mots de passe ne correspondent pas.' : null;
  }

  protected submit(): void {
    this.form.markAllAsTouched();
    const { password, confirm } = this.form.getRawValue();
    if (this.form.invalid || password !== confirm || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.serverError.set(null);
    this.resets.confirmReset(this.token, password).subscribe({
      next: () => {
        // Toutes les sessions ont été révoquées côté serveur : la session locale éventuelle est effacée.
        this.auth.clear();
        void this.router.navigateByUrl('/connexion?motdepasse=reinitialise');
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        const body = err.error as ApiError | null;
        const fields = body?.errors ?? [];
        if (err.status === 400 && fields.some((e) => e.field === 'token')) {
          this.linkInvalid.set(true);
        } else if (err.status === 400 && fields.length) {
          this.serverError.set(fields.map((e) => e.message).join(' · '));
        } else {
          this.serverError.set('La réinitialisation a échoué. Réessayez dans un instant.');
        }
      },
    });
  }
}
