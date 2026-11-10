import { inject, Injectable } from '@angular/core';
import { HttpContext } from '@angular/common/http';
import { map, timeout } from 'rxjs';
import { OrderingControllerService } from '../../api/generated/services';
import { CheckoutCommand, OrderReceipt, OrderReplay } from '../../api/generated/models';
import { ORDER_TRACKING_TOKEN } from '../../core/http/order-tracking.interceptor';
@Injectable({ providedIn: 'root' })
export class PublicOrderApi {
  private readonly api = inject(OrderingControllerService);
  checkout(id: string, key: string, body: CheckoutCommand) {
    // OpenAPI documents 201 OrderReceipt and 200 OrderReplay; the generator selects only 200.
    return this.api.checkout$Response({ publicIdentifier: id, 'Idempotency-Key': key, body }).pipe(
      timeout(20000),
      map((response) => ({
        created: response.status === 201,
        body: response.body as OrderReceipt & OrderReplay,
      })),
    );
  }
  quotes(orderNumber: string, token: string) {
    return this.api
      .quoteOrderPayment({ orderNumber }, new HttpContext().set(ORDER_TRACKING_TOKEN, token))
      .pipe(timeout(15000));
  }
  initiate(
    orderNumber: string,
    token: string,
    key: string,
    quoteId?: string,
    provider: 'SASPAY' | 'bictorys' = quoteId ? 'SASPAY' : 'bictorys',
    payerEmail?: string,
  ) {
    return this.api
      .initiate(
        {
          orderNumber,
          'Idempotency-Key': key,
          body: {
            provider,
            ...(quoteId ? { quoteId } : {}),
            ...(payerEmail ? { payerEmail } : {}),
          },
        },
        new HttpContext().set(ORDER_TRACKING_TOKEN, token),
      )
      .pipe(timeout(20000));
  }
  track(token: string) {
    return this.api
      .track({}, new HttpContext().set(ORDER_TRACKING_TOKEN, token))
      .pipe(timeout(15000));
  }
}
