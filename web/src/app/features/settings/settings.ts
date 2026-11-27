import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AccountService, INCORRECT_PASSWORD_MESSAGE } from '../../core/account/account.service';
import { AuthService } from '../../core/auth/auth.service';
import { WebPushService } from '../../core/notifications/web-push.service';

/**
 * Paramètres du compte : notifications des rappels dans le navigateur (story 14) et suppression définitive
 * du compte et de toutes ses données (RGPD).
 */
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './settings.html',
  styleUrl: './settings.css',
})
export class Settings implements OnInit {
  protected readonly auth = inject(AuthService);
  protected readonly webPush = inject(WebPushService);
  private readonly account = inject(AccountService);
  private readonly router = inject(Router);

  /** Formulaire de confirmation affiché. */
  protected readonly confirming = signal(false);
  protected readonly deleting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    password: ['', Validators.required],
  });

  ngOnInit(): void {
    void this.webPush.refresh();
  }

  protected enableNotifications(): void {
    void this.webPush.enable();
  }

  protected disableNotifications(): void {
    void this.webPush.disable();
  }

  protected startDeletion(): void {
    this.confirming.set(true);
    this.error.set(null);
  }

  protected cancel(): void {
    this.confirming.set(false);
    this.error.set(null);
    this.form.reset();
  }

  protected confirm(): void {
    if (this.deleting()) {
      return;
    }
    const { password } = this.form.getRawValue();
    if (!password) {
      this.error.set('Saisissez votre mot de passe pour confirmer.');
      return;
    }
    this.deleting.set(true);
    this.error.set(null);
    this.account.deleteAccount(password).subscribe({
      next: () => {
        void this.webPush.forgetBrowser();
        this.router.navigateByUrl('/connexion?compte=supprime');
      },
      error: (err: HttpErrorResponse) => {
        this.deleting.set(false);
        this.form.reset();
        this.error.set(
          err.status === 403 || err.status === 400
            ? INCORRECT_PASSWORD_MESSAGE
            : 'La suppression a échoué. Réessayez dans un instant.',
        );
      },
    });
  }
}
