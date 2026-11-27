import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService, PUBLIC_AUTH_URLS } from './auth.service';

const withToken = (req: HttpRequest<unknown>, token: string) =>
  req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });

const isApi = (url: string) => url.startsWith('api/') || url.includes('/api/');
const isPublicAuth = (url: string) => PUBLIC_AUTH_URLS.some((u) => url.endsWith(u));

/**
 * Ajoute « Authorization: Bearer » aux appels de l'API. Sur 401, renouvelle le jeton une fois
 * puis rejoue la requête ; si le renouvellement échoue, ferme la session et renvoie vers la connexion.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isApi(req.url) || isPublicAuth(req.url)) {
    return next(req);
  }
  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.token;
  if (!token) {
    return next(req);
  }
  return next(withToken(req, token)).pipe(
    catchError((err: unknown) => {
      if (!(err instanceof HttpErrorResponse) || err.status !== 401 || req.url.endsWith('api/auth/logout')) {
        return throwError(() => err);
      }
      return auth.refresh().pipe(
        catchError((refreshErr: unknown) => {
          router.navigateByUrl('/connexion');
          return throwError(() => refreshErr);
        }),
        switchMap((fresh) => next(withToken(req, fresh))),
      );
    }),
  );
};
