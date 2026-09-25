import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { environment } from '../config/environment';
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService),
    origin = inject(DOCUMENT).location.origin,
    api = new URL(environment.apiBaseUrl, origin),
    target = new URL(request.url, origin),
    path = api.pathname.replace(/\/$/, '');
  const owned =
    target.origin === api.origin &&
    (target.pathname === path || target.pathname.startsWith(path + '/'));
  const anonymous =
    target.pathname.startsWith(path + '/auth/') || target.pathname.startsWith(path + '/public/');
  const token = owned && !anonymous ? auth.token() : null;
  return next(
    token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request,
  ).pipe(
    catchError((error: unknown) => {
      if (
        token &&
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        auth.token() === token
      )
        auth.clear();
      return throwError(() => error);
    }),
  );
};
