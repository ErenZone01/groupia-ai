import { DOCUMENT } from '@angular/common';
import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
export const CUSTOMER_CSRF = new HttpContextToken<string>(() => '');
export const customerAccessInterceptor: HttpInterceptorFn = (request, next) => {
  const origin = inject(DOCUMENT).location.origin;
  const target = new URL(request.url, origin);
  if (target.origin !== origin || !target.pathname.startsWith('/api/public/customer-access/'))
    return next(request);
  const token = request.context.get(CUSTOMER_CSRF);
  return next(
    request.clone({
      withCredentials: true,
      ...(token ? { setHeaders: { 'X-XSRF-TOKEN': token } } : {}),
    }),
  );
};
