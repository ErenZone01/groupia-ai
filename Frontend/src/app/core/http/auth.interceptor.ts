import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
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
  const authPath =
    owned &&
    ['login', 'register', 'refresh', 'logout'].some(
      (action) => target.pathname === path + '/auth/' + action,
    );
  if (authPath)
    return next(
      request.clone({
        withCredentials: true,
        setHeaders: { 'X-Groupia-Auth': '1', 'Content-Type': 'application/json' },
        headers: request.headers.delete('Authorization'),
      }),
    );
  const anonymous =
    target.pathname.startsWith(path + '/auth/') || target.pathname.startsWith(path + '/public/');
  const token = owned && !anonymous ? auth.token() : null,
    generation = auth.generation(),
    knownSession = owned && !anonymous && auth.hasSession();
  const authenticated = token
    ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : request;
  return next(authenticated).pipe(
    catchError((error: unknown) => {
      const rejected =
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        (error.error?.code === 'AUTHENTICATION_REQUIRED' ||
          /^Bearer(?:\s|$)/i.test(error.headers.get('WWW-Authenticate') ?? ''));
      if ((!token && !knownSession) || !rejected || generation !== auth.generation())
        return throwError(() => error);
      return from(auth.refresh(token ?? undefined)).pipe(
        switchMap((ok) => {
          const renewed = auth.token();
          if (!ok || !renewed || renewed === token || generation !== auth.generation())
            return throwError(() => error);
          // Calling next directly retries once; body, query and idempotency key remain unchanged.
          return next(request.clone({ setHeaders: { Authorization: `Bearer ${renewed}` } }));
        }),
      );
    }),
  );
};
