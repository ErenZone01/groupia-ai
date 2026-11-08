import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { firstValueFrom, Observable, switchMap, throwError, timeout } from 'rxjs';
import { CustomerAccessControllerService } from '../../api/generated/services/customer-access-controller.service';
import type { PaymentFeeQuoteView, PaymentAttemptRecoveryView } from '../../api/generated/models';
import { CUSTOMER_CSRF } from '../../core/http/customer-access.interceptor';
export interface DirectRecoveredOrder {
  orderNumber: string;
  publicIdentifier: string;
  status: string;
  fulfillmentMethod: 'PICKUP' | 'DELIVERY';
  currency: 'XOF';
  acceptedTotalXof: number | null;
  placedAt: string;
}
export interface DirectRecoveredOrders {
  items: DirectRecoveredOrder[];
  total: number;
  page: number;
  size: number;
}
export interface RotatedDirectOrderLink {
  orderNumber: string;
  publicIdentifier: string;
  token: string;
}

@Injectable({ providedIn: 'root' })
export class CustomerAccessService {
  private readonly api = inject(CustomerAccessControllerService);
  private readonly http = inject(HttpClient);
  // The opaque session exists only in its HttpOnly cookie. No browser storage or bearer cache.
  private mutate<T>(operation: (context: HttpContext) => Observable<T>): Promise<T> {
    return firstValueFrom(
      this.api.customerCsrf().pipe(
        switchMap((value) =>
          value.token
            ? operation(new HttpContext().set(CUSTOMER_CSRF, value.token))
            : throwError(() => new Error('CSRF unavailable')),
        ),
        timeout(20000),
      ),
    );
  }
  request(phone: string) {
    return this.mutate((c) => this.api.requestCustomerOtp({ body: { phone } }, c));
  }
  verify(challengeId: string, code: string) {
    return this.mutate((c) => this.api.verifyCustomerOtp({ body: { challengeId, code } }, c));
  }
  exchange(token: string) {
    return this.mutate((c) => this.api.exchangeCustomerLink({ body: { token } }, c));
  }
  session() {
    return firstValueFrom(this.api.customerSession().pipe(timeout(20000)));
  }
  orders(page = 0) {
    return firstValueFrom(this.api.customerOrders({ page, size: 20 }).pipe(timeout(20000)));
  }
  directOrders(page = 0) {
    return firstValueFrom(
      this.http
        .get<DirectRecoveredOrders>('/api/public/customer-access/direct-orders', {
          params: { page, size: 20 },
        })
        .pipe(timeout(15000)),
    );
  }
  rotateDirectLink(orderNumber: string) {
    return this.mutate((context) =>
      this.http.post<RotatedDirectOrderLink>(
        '/api/public/customer-access/direct-orders/' +
          encodeURIComponent(orderNumber) +
          '/rotate-link',
        {},
        { context },
      ),
    );
  }

  order(orderNumber: string) {
    return firstValueFrom(this.api.customerOrder({ orderNumber }).pipe(timeout(20000)));
  }
  capabilities(orderNumber: string) {
    return firstValueFrom(this.api.customerOrderCapabilities({ orderNumber }).pipe(timeout(20000)));
  }
  quoteShipping(orderNumber: string) {
    return this.mutate((context) =>
      this.http.post<PaymentFeeQuoteView[]>(
        '/api/public/customer-access/orders/' +
          encodeURIComponent(orderNumber) +
          '/shipping-balance/payment-quotes',
        {},
        { context },
      ),
    );
  }
  shippingPaymentOptions(orderNumber: string) {
    return firstValueFrom(
      this.http
        .get<{ availableProviders?: string[]; latestAttempt?: PaymentAttemptRecoveryView }>(
          '/api/public/customer-access/orders/' +
            encodeURIComponent(orderNumber) +
            '/shipping-balance/payment-options',
        )
        .pipe(timeout(15000)),
    );
  }
  payShipping(orderNumber: string, key: string, feeQuoteId?: string) {
    return this.mutate((c) =>
      this.api.initiateCustomerShippingPayment(
        {
          orderNumber,
          'Idempotency-Key': key,
          body: feeQuoteId ? { provider: 'SASPAY', feeQuoteId } : { provider: 'BICTORYS' },
        },
        c,
      ),
    );
  }
  resend(orderNumber: string) {
    return this.mutate((c) => this.api.resendCustomerLink({ orderNumber }, c));
  }
  logout() {
    return this.mutate((c) => this.api.logoutCustomer(undefined, c));
  }
}
