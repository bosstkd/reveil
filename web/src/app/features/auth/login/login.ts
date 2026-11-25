import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { INVALID_CREDENTIALS_MESSAGE } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './login.html',
  styleUrl: '../register/register.css',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', Validators.required],
    password: ['', Validators.required],
  });

  protected submit(): void {
    if (this.submitting()) {
      return;
    }
    const { email, password } = this.form.getRawValue();
    // Champs vides : même message générique que des identifiants refusés par le serveur.
    if (this.form.invalid || !email.trim()) {
      this.error.set(INVALID_CREDENTIALS_MESSAGE);
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth.login(email.trim(), password).subscribe({
      next: () => this.router.navigateByUrl('/evenements'),
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.form.controls.password.reset();
        this.error.set(
          err.status === 401 || err.status === 400
            ? INVALID_CREDENTIALS_MESSAGE
            : 'La connexion a échoué. Réessayez dans un instant.',
        );
      },
    });
  }
}
