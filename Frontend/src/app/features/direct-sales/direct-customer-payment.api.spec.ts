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
