import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { inject } from '@angular/core';
export const ORDER_TRACKING_TOKEN = new HttpContextToken<string>(() => '');
/** Explicit per-request credential; never inferred from the current session. */
export const orderTrackingInterceptor: HttpInterceptorFn = (req, next) => {
  const token = req.context.get(ORDER_TRACKING_TOKEN);
  const origin = inject(DOCUMENT).location.origin;
  const url = new URL(req.url, origin);
  const allowed =
    url.origin === origin &&
    (url.pathname === '/api/public/orders/track' ||
      /^\/api\/public\/orders\/[^/]+\/payments$/.test(url.pathname));
  return next(
    token && allowed ? req.clone({ setHeaders: { 'X-Order-Tracking-Token': token } }) : req,
  );
};
