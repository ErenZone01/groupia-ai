import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { DirectSalesApi } from './direct-sales.api';
import { DirectOrderTrackingPage } from './direct-order-tracking.page';

describe('Vente directe : suivi client avec jeton secret', () => {
  const shop = '11111111-1111-4111-8111-111111111111';
  const token = 'A'.repeat(43);
  let fragment: BehaviorSubject<string | null>;
  let request: ReturnType<typeof vi.fn>;
  let paymentStatus: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fragment = new BehaviorSubject<string | null>(null);
    request = vi.fn(async () => ({
      orderNumber: 'DIR-000000000001', status: 'PENDING_PAYMENT_INTEGRATION',
      fulfillmentMethod: 'PICKUP', currency: 'XOF',
      goodsSubtotalXof: 9000, platformFeeXof: 300,
      amountBeforeShippingXof: 9300, shippingFeeXof: 0,
      acceptedTotalXof: 9300, paymentAvailable: false,
      placedAt: '2026-11-02T12:00:00Z',
    }));
    paymentStatus = vi.fn(async () => ({
      orderNumber: 'DIR-000000000001',
      currency: 'XOF',
      acceptedTotalXof: 9300,
      preparationStatus: 'PREPARED',
      financialStatus: 'PREPARED',
      paymentAvailable: false,
    }));
    TestBed.configureTestingModule({
      providers: [
        { provide: DirectSalesApi, useValue: { trackDirectOrder: request, directPaymentStatus: paymentStatus } },
        { provide: ActivatedRoute, useValue: {
          paramMap: new BehaviorSubject(convertToParamMap({ publicIdentifier: shop })),
          fragment,
        } },
      ],
    });
  });

  it('ne révèle aucune commande sans jeton de 256 bits valide', async () => {
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    expect(page.message()).toContain('jeton');
    expect(request).not.toHaveBeenCalled();
    fragment.next('access=DIR-000000000001');
    expect(request).not.toHaveBeenCalled();
  });

  it('récupère le statut sans paiement via le jeton en fragment', async () => {
    fragment.next('access=' + token);
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    await vi.waitFor(() => expect(page.order()?.orderNumber).toBe('DIR-000000000001'));
    expect(page.order()?.paymentAvailable).toBe(false);
    expect(request).toHaveBeenCalledWith(token);
    expect(paymentStatus).toHaveBeenCalledWith(token);
    expect(page.paymentLabel()).toBe('Paiement non encore confirmé');
  });

  it('affiche le règlement seulement si le paiement canonique concorde', async () => {
    paymentStatus.mockResolvedValue({
      orderNumber: 'DIR-000000000001', currency: 'XOF',
      acceptedTotalXof: 9300, preparationStatus: 'UNAVAILABLE',
      financialStatus: 'SETTLEMENT_VERIFIED', paymentAvailable: false,
    });
    request.mockResolvedValue({
      orderNumber: 'DIR-000000000001', status: 'READY_FOR_PICKUP',
      fulfillmentMethod: 'PICKUP', currency: 'XOF',
      goodsSubtotalXof: 9000, platformFeeXof: 300,
      amountBeforeShippingXof: 9300, shippingFeeXof: 0,
      acceptedTotalXof: 9300, paymentAvailable: false,
      placedAt: '2026-11-02T12:00:00Z',
    });
    fragment.next('access=' + token);
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    await vi.waitFor(() => expect(page.financial()?.financialStatus).toBe('SETTLEMENT_VERIFIED'));
    expect(page.paymentLabel()).toBe('Règlement vérifié');
    expect(page.orderLabel('READY_FOR_PICKUP')).toContain('Prête pour le retrait');
    expect(page.financial()?.paymentAvailable).toBe(false);
  });

  it('refuse un règlement avec un total incohérent ou un fournisseur indisponible', async () => {
    paymentStatus.mockResolvedValue({
      orderNumber: 'DIR-000000000001', currency: 'XOF',
      acceptedTotalXof: 9999, preparationStatus: 'UNAVAILABLE',
      financialStatus: 'SETTLEMENT_VERIFIED', paymentAvailable: false,
    });
    fragment.next('access=' + token);
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    await vi.waitFor(() => expect(page.order()).not.toBeNull());
    expect(page.financial()).toBeNull();
    expect(page.paymentProblem()).toBe(true);
    expect(page.paymentLabel()).toContain('indisponible');
    expect(page.orderLabel('READY_FOR_PICKUP')).toContain('suspendu');
  });

  it('ignore une réponse ancienne après changement de jeton', async () => {
    let finish!: (value: {
      orderNumber: string; status: string; fulfillmentMethod: string; currency: string;
      goodsSubtotalXof: number; platformFeeXof: number; amountBeforeShippingXof: number;
      shippingFeeXof: number; acceptedTotalXof: number; paymentAvailable: boolean; placedAt: string;
    }) => void;
    request.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    fragment.next('access=' + token);
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    fragment.next(null);
    finish({
      orderNumber: 'DIR-000000000001', status: 'PENDING_PAYMENT_INTEGRATION',
      fulfillmentMethod: 'PICKUP', currency: 'XOF',
      goodsSubtotalXof: 9000, platformFeeXof: 300, amountBeforeShippingXof: 9300,
      shippingFeeXof: 0, acceptedTotalXof: 9300, paymentAvailable: false,
      placedAt: '2026-11-02T12:00:00Z',
    });
    await Promise.resolve();
    expect(page.order()).toBeNull();
    expect(page.message()).toContain('jeton');
  });
});
