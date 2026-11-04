import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DirectSalesApi } from './direct-sales.api';

describe('Vente directe : consultation financière par lien client', () => {
  let api: DirectSalesApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(DirectSalesApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('initie uniquement par POST avec jeton dans le corps et clé idempotente', async () => {
    const token = 'A'.repeat(43);
    const key = '12345678-1234-4234-8234-123456789abc';
    const result = api.initiateDirectPayment(token, key);
    const req = http.expectOne('/api/public/direct-orders/payments');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ token });
    expect(req.request.headers.get('Idempotency-Key')).toBe(key);
    expect(req.request.urlWithParams).not.toContain(token);
    expect(JSON.stringify(req.request.body)).not.toContain('9300');
    req.flush({
      id: '22222222-2222-4222-8222-222222222222', status: 'PENDING',
      expectedAmount: 9300, currency: 'XOF', reconciliationRequired: false,
      initiatedAt: '2026-11-04T00:00:00Z',
      checkoutUrl: 'https://pay.test.bictorys.com/checkout/example',
    });
    const attempt = await result;
    expect(attempt.status).toBe('PENDING');
    expect(attempt.checkoutUrl).toContain('pay.test.bictorys.com');
  });

  it('garde le secret en POST et ne permet pas de lancer un paiement', async () => {
    const token = 'A'.repeat(43);
    const waiting = api.directPaymentStatus(token);
    const pending = http.expectOne('/api/public/direct-orders/payment-status');
    expect(pending.request.method).toBe('POST');
    expect(pending.request.body).toEqual({ token });
    expect(pending.request.urlWithParams).not.toContain(token);
    pending.flush({
      orderNumber: 'DIR-000000000001',
      currency: 'XOF',
      acceptedTotalXof: 9300,
      preparationStatus: 'UNAVAILABLE',
      financialStatus: 'REVIEW_REQUIRED',
      paymentAvailable: false,
    });
    const result = await waiting;
    expect(result.financialStatus).toBe('REVIEW_REQUIRED');
    expect(result.paymentAvailable).toBe(false);
  });
});
