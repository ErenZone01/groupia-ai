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
  let initiatePayment: ReturnType<typeof vi.fn>;

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
    initiatePayment = vi.fn(async () => ({
      id: '22222222-2222-4222-8222-222222222222', status: 'PENDING',
      expectedAmount: 9300, currency: 'XOF', reconciliationRequired: false,
      initiatedAt: '2026-11-04T00:00:00Z',
      checkoutUrl: 'https://pay.test.bictorys.com/checkout/example',
    }));
    TestBed.configureTestingModule({
      providers: [
        { provide: DirectSalesApi, useValue: { trackDirectOrder: request, directPaymentStatus: paymentStatus, initiateDirectPayment: initiatePayment } },
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

  it('affiche un paiement initié en attente sans ouvrir une nouvelle charge', async () => {
    paymentStatus.mockResolvedValue({
      orderNumber: 'DIR-000000000001', currency: 'XOF',
      acceptedTotalXof: 9300, preparationStatus: 'UNAVAILABLE',
      financialStatus: 'PAYMENT_IN_PROGRESS', paymentAvailable: false,
    });
    fragment.next('access=' + token);
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    await vi.waitFor(() => expect(page.financial()?.financialStatus).toBe('PAYMENT_IN_PROGRESS'));
    expect(page.paymentLabel()).toContain('Paiement en cours');
    expect(page.paymentLabel()).not.toContain('manuelle');
    expect(page.canPay()).toBe(false);
    await page.pay();
    expect(initiatePayment).not.toHaveBeenCalled();
  });

  it('n’envoie jamais une tentative quand le backend interdit le paiement', async () => {
    fragment.next('access=' + token);
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    await vi.waitFor(() => expect(page.financial()?.financialStatus).toBe('PREPARED'));
    expect(page.canPay()).toBe(false);
    await page.pay();
    expect(initiatePayment).not.toHaveBeenCalled();
  });

  it('autorise le paiement uniquement sur lecture concordante et conserve le statut non payé', async () => {
    paymentStatus.mockResolvedValue({
      orderNumber: 'DIR-000000000001', currency: 'XOF',
      acceptedTotalXof: 9300, preparationStatus: 'PREPARED',
      financialStatus: 'PREPARED', paymentAvailable: true,
    });
    fragment.next('access=' + token);
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    await vi.waitFor(() => expect(page.canPay()).toBe(true));
    await page.pay();
    expect(initiatePayment).toHaveBeenCalledTimes(1);
    expect(initiatePayment).toHaveBeenCalledWith(token, expect.stringMatching(/^[0-9a-f-]{36}$/));
    expect(page.paymentWaiting()).toBe(true);
    expect(page.canPay()).toBe(false);
    expect(page.checkoutUrl()).toBe('https://pay.test.bictorys.com/checkout/example');
    expect(page.paymentLabel()).toBe('Paiement non encore confirmé');
    await page.pay();
    expect(initiatePayment).toHaveBeenCalledTimes(1);
  });

  it('actualise automatiquement un paiement Bictorys en attente sans recharger la page', async () => {
    paymentStatus.mockResolvedValue({
      orderNumber: 'DIR-000000000001', currency: 'XOF',
      acceptedTotalXof: 9300, preparationStatus: 'PREPARED',
      financialStatus: 'PREPARED', paymentAvailable: true,
    });
    fragment.next('access=' + token);
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    await vi.waitFor(() => expect(page.canPay()).toBe(true));

    vi.useFakeTimers();
    try {
      await page.pay();
      expect(page.paymentWaiting()).toBe(true);
      const firstReads = paymentStatus.mock.calls.length;
      paymentStatus.mockResolvedValue({
        orderNumber: 'DIR-000000000001', currency: 'XOF',
        acceptedTotalXof: 9300, preparationStatus: 'UNAVAILABLE',
        financialStatus: 'SETTLEMENT_VERIFIED', paymentAvailable: false,
      });
      request.mockResolvedValue({
        orderNumber: 'DIR-000000000001', status: 'PAYMENT_CONFIRMED',
        fulfillmentMethod: 'PICKUP', currency: 'XOF',
        goodsSubtotalXof: 9000, platformFeeXof: 300,
        amountBeforeShippingXof: 9300, shippingFeeXof: 0,
        acceptedTotalXof: 9300, paymentAvailable: false,
        placedAt: '2026-11-02T12:00:00Z',
      });
      await vi.advanceTimersByTimeAsync(15000);
      expect(paymentStatus).toHaveBeenCalledTimes(firstReads + 1);
      expect(page.financial()?.financialStatus).toBe('SETTLEMENT_VERIFIED');
      expect(page.order()?.status).toBe('PAYMENT_CONFIRMED');
      expect(page.paymentWaiting()).toBe(false);
      expect(page.checkoutUrl()).toBeNull();
      expect(initiatePayment).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(30000);
      expect(paymentStatus).toHaveBeenCalledTimes(firstReads + 1);
      expect(initiatePayment).toHaveBeenCalledTimes(1);
    } finally {
      TestBed.resetTestingModule();
      vi.useRealTimers();
    }
  });

  it('arrête la surveillance quand le jeton de suivi change', async () => {
    paymentStatus.mockResolvedValue({
      orderNumber: 'DIR-000000000001', currency: 'XOF',
      acceptedTotalXof: 9300, preparationStatus: 'PREPARED',
      financialStatus: 'PREPARED', paymentAvailable: true,
    });
    fragment.next('access=' + token);
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    await vi.waitFor(() => expect(page.canPay()).toBe(true));

    vi.useFakeTimers();
    try {
      await page.pay();
      const previousReads = paymentStatus.mock.calls.length;
      fragment.next(null);
      await Promise.resolve();
      await vi.advanceTimersByTimeAsync(30000);
      expect(page.message()).toContain('jeton');
      expect(page.checkoutUrl()).toBeNull();
      expect(paymentStatus).toHaveBeenCalledTimes(previousReads);
      expect(initiatePayment).toHaveBeenCalledTimes(1);
    } finally {
      TestBed.resetTestingModule();
      vi.useRealTimers();
    }
  });

  it('rejette un faux lien prestataire sans annoncer un règlement confirmé', async () => {
    paymentStatus.mockResolvedValue({
      orderNumber: 'DIR-000000000001', currency: 'XOF',
      acceptedTotalXof: 9300, preparationStatus: 'PREPARED',
      financialStatus: 'PREPARED', paymentAvailable: true,
    });
    initiatePayment.mockResolvedValue({
      id: 'fake', status: 'PENDING', expectedAmount: 9300, currency: 'XOF',
      reconciliationRequired: false, initiatedAt: '2026-11-04T00:00:00Z',
      checkoutUrl: 'https://malicious.example/checkout',
    });
    fragment.next('access=' + token);
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    await vi.waitFor(() => expect(page.canPay()).toBe(true));
    await page.pay();
    expect(page.checkoutUrl()).toBeNull();
    expect(page.paymentWaiting()).toBe(true);
    expect(page.paymentLabel()).not.toContain('vérifié');
  });

  it('réutilise la clé d’idempotence après erreur réseau et ne fabrique aucune réussite', async () => {
    paymentStatus.mockResolvedValue({
      orderNumber: 'DIR-000000000001', currency: 'XOF',
      acceptedTotalXof: 9300, preparationStatus: 'PREPARED',
      financialStatus: 'PREPARED', paymentAvailable: true,
    });
    initiatePayment.mockRejectedValueOnce(new Error('network failure'));
    fragment.next('access=' + token);
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    await vi.waitFor(() => expect(page.canPay()).toBe(true));
    await page.pay();
    expect(page.paymentWaiting()).toBe(false);
    expect(page.paymentError()).toContain('non confirmée');
    expect(page.canPay()).toBe(true);
    await page.pay();
    expect(initiatePayment).toHaveBeenCalledTimes(2);
    expect(initiatePayment.mock.calls[0][1]).toBe(initiatePayment.mock.calls[1][1]);
    expect(page.paymentLabel()).toBe('Paiement non encore confirmé');
  });

  it('ignore la tentative initiée sous un ancien jeton révoqué', async () => {
    paymentStatus.mockResolvedValue({
      orderNumber: 'DIR-000000000001', currency: 'XOF',
      acceptedTotalXof: 9300, preparationStatus: 'PREPARED',
      financialStatus: 'PREPARED', paymentAvailable: true,
    });
    let finish!: (v: {
      id: string; status: string; expectedAmount: number; currency: string;
      reconciliationRequired: boolean; initiatedAt: string; checkoutUrl: string;
    }) => void;
    initiatePayment.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    fragment.next('access=' + token);
    const page = TestBed.runInInjectionContext(() => new DirectOrderTrackingPage());
    await vi.waitFor(() => expect(page.canPay()).toBe(true));
    const pending = page.pay();
    fragment.next('access=' + 'B'.repeat(43));
    finish({
      id: 'expired', status: 'PENDING', expectedAmount: 9300, currency: 'XOF',
      reconciliationRequired: false, initiatedAt: '2026-11-04T00:00:00Z',
      checkoutUrl: 'https://pay.test.bictorys.com/checkout/old',
    });
    await pending;
    expect(page.checkoutUrl()).toBeNull();
    expect(page.paymentWaiting()).toBe(false);
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
