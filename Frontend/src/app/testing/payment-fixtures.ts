import type {
  PaymentRequestsPageView,
  PaymentRequestDetailView,
  PaymentReviewView,
} from '../api/generated/models';
// Synthetic fixtures imported only by tests, never by the application.
export const paymentPage: PaymentRequestsPageView = {
  items: [
    {
      id: 'request',
      orderId: 'order',
      purpose: 'INITIAL_ORDER',
      expectedAmount: 25300,
      currency: 'XOF',
      goodsAmount: 20000,
      feeAmount: 300,
      shippingAmount: 5000,
      acceptanceStatus: 'CLOSED',
      operationalStatus: 'REVIEW_REQUIRED',
      operationalReasons: ['PAYMENT_REVIEW_OPEN'],
      settledPaymentId: 'principal',
      openReviewCount: 1,
      expiresAt: '2026-10-12T12:00:00Z',
      createdAt: '2026-10-12T11:00:00Z',
    },
  ],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
  summary: { pending: 0, settled: 0, reviewRequired: 1, terminalUnpaid: 0, openReviews: 1 },
  evaluationAt: '2026-10-12T12:01:00Z',
};
export const paymentDetail: PaymentRequestDetailView = {
  request: paymentPage.items![0],
  attempts: [
    {
      id: 'principal',
      provider: 'BICTORYS',
      providerTransactionId: 'TX-PRINCIPAL',
      expectedAmount: 25300,
      currency: 'XOF',
      confirmedAmount: 25300,
      confirmedCurrency: 'XOF',
      status: 'SUCCEEDED',
    },
    {
      id: 'secondary',
      provider: 'BICTORYS',
      providerTransactionId: 'TX-SECONDARY',
      expectedAmount: 25300,
      currency: 'XOF',
      status: 'FAILED',
    },
  ],
};
export const paymentReview: PaymentReviewView = {
  id: 'review',
  organizationId: 'shop',
  paymentRequestId: 'request',
  orderId: 'order',
  status: 'OPEN',
  reason: 'DUPLICATE_PROVIDER_SUCCESS',
  canResolve: true,
  principalPaymentId: 'principal',
  anomalousPaymentId: 'principal',
  anomalousProvider: 'BICTORYS',
  anomalousProviderTransactionId: 'TX-SNAPSHOT',
  observedAmount: 25300,
  observedCurrency: 'XOF',
  detectedAt: '2026-10-12T12:00:00Z',
};
