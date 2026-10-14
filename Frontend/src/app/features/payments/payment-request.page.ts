import { Component, effect, inject, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { PaymentsStore } from './payments.store';
import { PaymentReviewsPanel } from './payment-reviews-panel';
import { money, paymentDate, paymentLabel, paymentTone } from './payment-display';
@Component({
  selector: 'g-payment-request-page',
  providers: [PaymentsStore],
  imports: [RouterLink, Badge, Button, PaymentReviewsPanel],
  templateUrl: './payment-request.page.html',
  styleUrl: './payments.scss',
})
export class PaymentRequestPage {
  readonly store = inject(PaymentsStore);
  private readonly params = toSignal(inject(ActivatedRoute).paramMap);
  readonly money = money;
  readonly date = paymentDate;
  readonly label = paymentLabel;
  readonly tone = paymentTone;
  constructor() {
    effect(() => {
      const id = this.params()?.get('requestId');
      if (id) untracked(() => this.store.showRequest(id));
    });
  }
}
