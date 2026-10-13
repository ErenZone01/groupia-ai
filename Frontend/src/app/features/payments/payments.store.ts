import { computed, DestroyRef, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subscription, timeout } from 'rxjs';
import {
  PaymentControllerService,
  PaymentReviewControllerService,
} from '../../api/generated/services';
import type {
  PaymentRequestsPageView,
  PaymentRequestDetailView,
  PaymentReviewPage,
  PaymentReviewView,
  PaymentRequestSummaryView,
  ResolvePaymentReviewRefunded,
} from '../../api/generated/models';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiCode, apiMessage } from '../../core/http/api-error';

export type OperationalFilter = NonNullable<PaymentRequestSummaryView['operationalStatus']> | '';
export type PurposeFilter = NonNullable<PaymentRequestSummaryView['purpose']> | '';
type Mode = { kind: 'dashboard' } | { kind: 'request' | 'review'; id: string };
interface DashboardState {
  requests: PaymentRequestsPageView | null;
  detail: PaymentRequestDetailView | null;
  reviews: PaymentReviewPage | null;
  review: PaymentReviewView | null;
  loadingRequests: boolean;
  loadingReviews: boolean;
  loadingDetail: boolean;
  loadingReview: boolean;
  requestsError: string;
  reviewsError: string;
  detailError: string;
  reviewError: string;
  page: number;
  reviewPage: number;
  status: OperationalFilter;
  purpose: PurposeFilter;
  reviewStatus: 'OPEN' | 'RESOLVED_REFUNDED';
  busy: boolean;
  message: string;
  resolutionError: string;
  resolutionState: 'idle' | 'uncertain' | 'retry' | 'done' | 'conflict';
}
function empty(): DashboardState {
  return {
    requests: null,
    detail: null,
    reviews: null,
    review: null,
    loadingRequests: false,
    loadingReviews: false,
    loadingDetail: false,
    loadingReview: false,
    requestsError: '',
    reviewsError: '',
    detailError: '',
    reviewError: '',
    page: 0,
    reviewPage: 0,
    status: '',
    purpose: '',
    reviewStatus: 'OPEN',
    busy: false,
    message: '',
    resolutionError: '',
    resolutionState: 'idle',
  };
}

@Injectable()
export class PaymentsStore {
  private readonly api = inject(PaymentControllerService);
  private readonly reviewApi = inject(PaymentReviewControllerService);
  private readonly context = inject(BoutiqueContextService);
  private readonly auth = inject(AuthService);
  private readonly state = signal(empty());
  private org = '';
  private session = -1;
  private generation = 0;
  private mode: Mode | null = null;
  private readonly subscriptions = new Map<string, Subscription>();
  private readonly sequences = new Map<string, number>();
  // Only memory, scoped to the organization/review/session that submitted it.
  private draft: {
    org: string;
    reviewId: string;
    session: number;
    body: ResolvePaymentReviewRefunded;
  } | null = null;
  readonly vm = computed(() => {
    const value = this.state();
    return this.active() ? value : empty();
  });

  constructor() {
    effect(() => {
      const org = this.context.selectedId() ?? '';
      const session = this.auth.generation();
      untracked(() => {
        if (org === this.org && session === this.session) return;
        this.cancel();
        this.generation++;
        this.org = org;
        this.session = session;
        this.draft = null;
        this.state.set(empty());
        if (org && this.auth.signedIn()) this.reload();
      });
    });
    inject(DestroyRef).onDestroy(() => {
      this.generation++;
      this.cancel();
      this.draft = null;
    });
  }
  private active() {
    return (
      !!this.org &&
      this.context.selectedId() === this.org &&
      this.auth.generation() === this.session &&
      this.auth.signedIn()
    );
  }
  private patch(value: Partial<DashboardState>) {
    this.state.update((s) => ({ ...s, ...value }));
  }
  private cancel() {
    this.subscriptions.forEach((s) => s.unsubscribe());
    this.subscriptions.clear();
  }
  private fetch<T>(
    slot: string,
    source: Observable<T>,
    success: (value: T) => void,
    failure: (error: unknown) => void,
  ) {
    this.subscriptions.get(slot)?.unsubscribe();
    const sequence = (this.sequences.get(slot) ?? 0) + 1;
    this.sequences.set(slot, sequence);
    const generation = this.generation;
    const valid = () =>
      this.active() && generation === this.generation && this.sequences.get(slot) === sequence;
    const sub = source.pipe(timeout(20000)).subscribe({
      next: (value) => {
        if (valid()) success(value);
      },
      error: (error) => {
        if (valid()) failure(error);
      },
    });
    this.subscriptions.set(slot, sub);
  }
  showDashboard() {
    this.show({ kind: 'dashboard' });
  }
  showRequest(id: string) {
    this.show({ kind: 'request', id });
  }
  showReview(id: string) {
    this.show({ kind: 'review', id });
  }
  private show(mode: Mode) {
    if (JSON.stringify(this.mode) === JSON.stringify(mode)) return;
    this.cancel();
    this.generation++;
    this.draft = null;
    this.mode = mode;
    this.state.set(empty());
    if (this.active()) this.reload();
  }
  reload() {
    if (!this.active()) return;
    if (this.mode?.kind === 'dashboard') {
      this.loadRequests();
      this.loadReviews();
    }
    if (this.mode?.kind === 'request') {
      this.loadDetail(this.mode.id);
      this.loadReviews();
    }
    if (this.mode?.kind === 'review') this.loadReview(this.mode.id);
  }
  filter(status: OperationalFilter, purpose: PurposeFilter) {
    this.patch({ status, purpose, page: 0 });
    this.loadRequests();
  }
  requestPage(page: number) {
    this.patch({ page });
    this.loadRequests();
  }
  reviewPage(page: number) {
    this.patch({ reviewPage: page });
    this.loadReviews();
  }
  filterReviews(reviewStatus: 'OPEN' | 'RESOLVED_REFUNDED') {
    this.patch({ reviewStatus, reviewPage: 0 });
    this.loadReviews();
  }
  loadRequests() {
    if (!this.active()) return;
    const { page, status, purpose } = this.state();
    this.patch({ loadingRequests: true, requestsError: '', requests: null });
    this.fetch(
      'requests',
      this.api.listPaymentRequests({
        organizationId: this.org,
        page,
        size: 20,
        operationalStatus: status || undefined,
        purpose: purpose || undefined,
      }),
      (requests) => this.patch({ requests, loadingRequests: false }),
      (error) => this.patch({ requestsError: apiMessage(error), loadingRequests: false }),
    );
  }
  loadReviews() {
    if (!this.active()) return;
    const { reviewPage, reviewStatus } = this.state();
    this.patch({ loadingReviews: true, reviewsError: '', reviews: null });
    this.fetch(
      'reviews',
      this.reviewApi.listPaymentReviews({
        organizationId: this.org,
        status: reviewStatus,
        paymentRequestId: this.mode?.kind === 'request' ? this.mode.id : undefined,
        page: reviewPage,
        size: 20,
      }),
      (reviews) => this.patch({ reviews, loadingReviews: false }),
      (error) => this.patch({ reviewsError: apiMessage(error), loadingReviews: false }),
    );
  }
  private loadDetail(id: string) {
    this.patch({ loadingDetail: true, detailError: '', detail: null });
    this.fetch(
      'detail',
      this.api.getPaymentRequest({ organizationId: this.org, id }),
      (detail) => this.patch({ detail, loadingDetail: false }),
      (error) => this.patch({ detailError: apiMessage(error), loadingDetail: false }),
    );
  }
  private loadReview(reviewId: string) {
    this.patch({ loadingReview: true, reviewError: '', review: null });
    this.fetch(
      'review',
      this.reviewApi.getPaymentReview({ organizationId: this.org, reviewId }),
      (review) => this.patch({ review, loadingReview: false }),
      (error) => this.patch({ reviewError: apiMessage(error), loadingReview: false }),
    );
  }
  resolve(reference: string, note: string) {
    const review = this.vm().review;
    if (this.vm().busy || !review?.id || review.canResolve !== true || this.draft) return;
    const body: ResolvePaymentReviewRefunded = {
      externalRefundReference: reference.trim(),
      resolutionNote: note.trim() || undefined,
    };
    if (
      !body.externalRefundReference ||
      body.externalRefundReference.length > 160 ||
      (body.resolutionNote?.length ?? 0) > 1000
    ) {
      this.patch({
        resolutionError:
          'Indiquez une référence de 1 à 160 caractères et une note de 1 000 caractères maximum.',
      });
      return;
    }
    this.draft = { org: this.org, reviewId: review.id, session: this.session, body };
    this.submitDraft();
  }
  retryResolution() {
    if (this.vm().resolutionState === 'retry' && this.vm().review?.canResolve === true)
      this.submitDraft();
  }
  private submitDraft() {
    const draft = this.draft;
    if (
      !draft ||
      !this.active() ||
      this.vm().busy ||
      draft.org !== this.org ||
      draft.session !== this.session
    )
      return;
    this.patch({ busy: true, resolutionError: '', message: '' });
    this.fetch(
      'resolve',
      this.reviewApi.resolvePaymentReviewRefunded({
        organizationId: draft.org,
        reviewId: draft.reviewId,
        body: draft.body,
      }),
      (review) => this.acceptResolution(review),
      (error) => {
        if (
          apiCode(error) === 'PAYMENT_REVIEW_RESOLUTION_CONFLICT' ||
          (error instanceof HttpErrorResponse && error.status === 409)
        ) {
          this.patch({
            resolutionState: 'conflict',
            message: 'Une autre preuve a été enregistrée. Vérifiez la résolution affichée.',
          });
          this.checkResolution();
        } else if (
          !(error instanceof HttpErrorResponse) ||
          error.status === 0 ||
          error.status >= 500
        ) {
          this.patch({
            resolutionState: 'uncertain',
            message:
              'L’enregistrement reste à vérifier. N’effectuez pas un second remboursement externe.',
          });
          this.checkResolution();
        } else {
          this.draft = null;
          this.patch({ busy: false, resolutionError: apiMessage(error), resolutionState: 'idle' });
        }
      },
    );
  }
  checkResolution() {
    const draft = this.draft;
    if (!draft || !this.active() || draft.org !== this.org || draft.session !== this.session)
      return;
    this.patch({ busy: true });
    this.fetch(
      'recovery',
      this.reviewApi.getPaymentReview({ organizationId: draft.org, reviewId: draft.reviewId }),
      (review) => this.acceptResolution(review),
      () =>
        this.patch({
          busy: false,
          resolutionState: 'uncertain',
          message:
            'Impossible de vérifier l’enregistrement. Relisez son état avant de réessayer. N’effectuez pas un second remboursement externe.',
        }),
    );
  }
  private acceptResolution(review: PaymentReviewView) {
    const draft = this.draft;
    if (!draft || review.id !== draft.reviewId) return;
    this.patch({ review, busy: false, resolutionError: '' });
    if (review.status === 'RESOLVED_REFUNDED') {
      const same =
        review.externalRefundReference?.trim() === draft.body.externalRefundReference?.trim() &&
        (review.resolutionNote?.trim() || '') === (draft.body.resolutionNote?.trim() || '');
      this.patch({
        resolutionState: same ? 'done' : 'conflict',
        message: same
          ? 'Le remboursement externe est enregistré.'
          : 'Conflit : une autre preuve a déjà été enregistrée. Vérifiez les informations affichées.',
      });
      this.draft = null;
      // Refresh related financial read models, never mutate their state locally.
      this.loadRequests();
      if (review.paymentRequestId) this.loadDetail(review.paymentRequestId);
      this.loadReviews();
    } else if (review.status === 'OPEN') {
      this.patch({
        resolutionState: 'retry',
        message:
          'Aucun enregistrement confirmé. Vous pouvez réessayer l’enregistrement avec la même preuve, sans refaire le remboursement externe.',
      });
    } else {
      this.patch({
        resolutionState: 'uncertain',
        message: 'État non reconnu. Actualisez avant toute autre action.',
      });
    }
  }
}
