import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Button } from '../../shared/ui/button';
import { PaymentsStore } from './payments.store';
import { PaymentRequestList } from './payment-request-list';
import { PaymentReviewsPanel } from './payment-reviews-panel';
import { PaymentPagination } from './payment-pagination';
@Component({
  selector: 'g-payments-page',
  providers: [PaymentsStore],
  imports: [FormsModule, Button, PaymentRequestList, PaymentReviewsPanel, PaymentPagination],
  templateUrl: './payments.page.html',
  styleUrl: './payments.scss',
})
export class PaymentsPage {
  readonly store = inject(PaymentsStore);
  constructor() {
    this.store.showDashboard();
  }
  readonly filters = [
    { id: '', label: 'Tous' },
    { id: 'PENDING', label: 'En attente' },
    { id: 'SETTLED', label: 'Réglés' },
    { id: 'REVIEW_REQUIRED', label: 'À vérifier' },
    { id: 'TERMINAL_UNPAID', label: 'Terminés sans règlement' },
  ] as const;
}
