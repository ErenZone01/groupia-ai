import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Button } from '../../shared/ui/button';
import { Badge } from '../../shared/ui/badge';
import { PaymentPagination } from './payment-pagination';
import { PaymentsStore } from './payments.store';
import { money, paymentDate } from './payment-display';
@Component({
  selector: 'g-payment-reviews-panel',
  imports: [FormsModule, RouterLink, Button, Badge, PaymentPagination],
  styleUrl: './payments.scss',
  template: `<section class="panel" aria-labelledby="reviews-title">
    <div class="section-heading">
      <div>
        <p class="eyebrow">Anomalies</p>
        <h2 id="reviews-title">Paiements à examiner</h2>
      </div>
      @if (store.vm().requests?.summary; as counts) {
        <g-badge tone="warning">{{ counts.openReviews ?? 0 }} paiements à examiner</g-badge>
      }
    </div>
    <p class="muted">Les incidents de la boutique sont comptés séparément des obligations.</p>
    <label class="field"
      >Afficher les incidents<select
        [ngModel]="store.vm().reviewStatus"
        (ngModelChange)="store.filterReviews($event)"
      >
        <option value="OPEN">À examiner</option>
        <option value="RESOLVED_REFUNDED">Régularisés</option>
      </select></label
    >
    @if (store.vm().loadingReviews) {
      <p role="status">Chargement des incidents…</p>
    } @else if (store.vm().reviewsError) {
      <p role="alert">{{ store.vm().reviewsError }}</p>
      <button gButton variant="secondary" (click)="store.loadReviews()">
        Réessayer les incidents
      </button>
    } @else if (store.vm().reviews; as page) {
      @if (!page.items?.length) {
        <p class="empty">Aucun incident dans cette sélection.</p>
      } @else {
        <ul class="review-list">
          @for (review of page.items; track review.id) {
            <li>
              <div>
                <strong>{{ money(review.observedAmount, review.observedCurrency) }}</strong>
                <p>{{ review.anomalousProvider }} · {{ date(review.detectedAt) }}</p>
                <small>{{ review.status === 'OPEN' ? 'À examiner' : 'Régularisé' }}</small>
              </div>
              <a [routerLink]="['/app/paiements/reviews', review.id]"
                >Examiner<span class="sr-only">
                  la transaction {{ review.anomalousProviderTransactionId }}</span
                ></a
              >
            </li>
          }
        </ul>
      }
      <g-payment-pagination
        label="Pagination des incidents"
        [page]="store.vm().reviewPage"
        [pages]="page.totalPages ?? 0"
        (changed)="store.reviewPage($event)"
      />
    }
  </section>`,
})
export class PaymentReviewsPanel {
  readonly store = inject(PaymentsStore);
  readonly money = money;
  readonly date = paymentDate;
}
