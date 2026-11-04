import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { DirectOrdersApi, DirectOrderSummary } from './direct-orders.api';
import { DirectOrdersPage } from './direct-orders.page';

const order = (status: string): DirectOrderSummary => ({
  id: 'order-1',
  orderNumber: 'DIR-000000000001',
  status,
  fulfillmentMethod: 'PICKUP',
  currency: 'XOF',
  goodsSubtotalXof: 9000,
  platformFeeXof: 300,
  amountBeforeShippingXof: 9300,
  shippingFeeXof: 0,
  acceptedTotalXof: 9300,
  shippingRateVersion: null,
  shippingQuoted: true,
  placedAt: '2026-11-03T12:00:00Z',
  itemCount: 1,
});

describe('Vente directe : actions de la gérante', () => {
  const organization = signal<string | null>('org-a');
  const generation = signal(1);
  let status: string;
  let financial: string;
  let api: {
    list: ReturnType<typeof vi.fn>;
    get: ReturnType<typeof vi.fn>;
    paymentStatus: ReturnType<typeof vi.fn>;
    cancel: ReturnType<typeof vi.fn>;
    startPreparation: ReturnType<typeof vi.fn>;
    markReady: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    organization.set('org-a');
    generation.set(1);
    status = 'PENDING_PAYMENT_INTEGRATION';
    financial = 'NOT_PREPARED';
    api = {
      list: vi.fn(async () => ({
        items: [order(status)], total: 1, page: 0, size: 20,
      })),
      get: vi.fn(async () => ({
        order: order(status),
        contact: { firstName: 'Awa', lastName: 'Diop', phoneE164: '+221771234567', email: null },
        deliveryAddress: null,
        platformFeeRule: { amount: '300' },
        items: [],
      })),
      paymentStatus: vi.fn(async () => ({
        orderNumber: 'DIR-000000000001',
        acceptedTotalXof: 9300,
        financialStatus: financial,
        fulfillmentAllowed: false,
      })),
      cancel: vi.fn(async () => { status = 'CANCELLED'; return { status }; }),
      startPreparation: vi.fn(async () => { status = 'PREPARING'; return { status }; }),
      markReady: vi.fn(async () => { status = 'READY_FOR_PICKUP'; return { status }; }),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { generation } },
        { provide: BoutiqueContextService, useValue: { selectedId: organization } },
        { provide: DirectOrdersApi, useValue: api },
      ],
    });
  });

  async function pageForCurrentOrder() {
    const page = TestBed.runInInjectionContext(() => new DirectOrdersPage());
    await vi.waitFor(() => expect(page.data()?.items).toHaveLength(1));
    await page.open(order(status));
    return page;
  }

  it('bloque toute préparation si le règlement est en revue', async () => {
    status = 'PAYMENT_CONFIRMED';
    financial = 'REVIEW_REQUIRED';
    const page = await pageForCurrentOrder();
    expect(page.reviewRequired()).toBe(true);
    expect(page.canPrepare()).toBe(false);
    await page.act('prepare');
    await page.act('ready');
    expect(api.startPreparation).not.toHaveBeenCalled();
    expect(api.markReady).not.toHaveBeenCalled();
  });

  it('bloque annulation et préparation pendant une tentative Bictorys non confirmée', async () => {
    financial = 'PAYMENT_IN_PROGRESS';
    const page = await pageForCurrentOrder();
    expect(page.financialLabel(financial)).toContain('Paiement en cours');
    expect(page.reviewRequired()).toBe(false);
    expect(page.canCancel()).toBe(false);
    expect(page.canPrepare()).toBe(false);
    await page.act('cancel');
    await page.act('prepare');
    expect(api.cancel).not.toHaveBeenCalled();
    expect(api.startPreparation).not.toHaveBeenCalled();
  });

  it('exige une double confirmation avant une annulation non payée', async () => {
    const page = await pageForCurrentOrder();
    expect(page.canCancel()).toBe(true);
    await page.act('cancel');
    expect(api.cancel).not.toHaveBeenCalled();
    page.confirmCancellation.set(true);
    await page.act('cancel');
    expect(api.cancel).toHaveBeenCalledWith('org-a', 'order-1');
    expect(page.detail()?.order.status).toBe('CANCELLED');
    expect(page.canCancel()).toBe(false);
    expect(api.startPreparation).not.toHaveBeenCalled();
  });

  it('ne présente le passage à prête que si le règlement est vérifié', async () => {
    status = 'PREPARING';
    financial = 'SETTLEMENT_VERIFIED';
    const page = await pageForCurrentOrder();
    expect(page.canReady()).toBe(true);
    await page.act('ready');
    expect(api.markReady).toHaveBeenCalledWith('org-a', 'order-1');
    expect(page.detail()?.order.status).toBe('READY_FOR_PICKUP');
    expect(page.canReady()).toBe(false);
  });

  it('montre le point GPS sauvegardé à la gérante et génère un lien d’itinéraire sûr', async () => {
    const address = {
      country: 'SN', region: 'Dakar', city: null, commune: null,
      neighborhood: null, details: 'Devant la pharmacie',
      locationMethod: 'MAP' as const, coverageStatus: 'REGION_MATCHED' as const,
      latitude: 14.6937, longitude: -17.4474,
    };
    api.get.mockResolvedValue({
      order: order('PENDING_PAYMENT_INTEGRATION'),
      contact: { firstName: 'Awa', lastName: 'Diop', phoneE164: '+221771234567', email: null },
      deliveryAddress: address, platformFeeRule: { amount: '300' }, items: [],
    });
    const page = await pageForCurrentOrder();
    expect(page.deliveryPoint(page.detail()?.deliveryAddress ?? null)).toEqual({
      latitude: 14.6937, longitude: -17.4474,
    });
    expect(page.deliveryMapUrl(address)).toContain('mlat=14.693700&mlon=-17.447400');
    expect(page.deliveryDirectionsUrl(address)).toContain('destination=14.693700%2C-17.447400');
  });

  it('n’invente aucun point exact pour une adresse manuelle ou des coordonnées invalides', async () => {
    const page = await pageForCurrentOrder();
    const manual = {
      country: 'SN', region: 'Dakar', city: 'Dakar', commune: 'Yoff',
      neighborhood: null, details: 'Derrière le marché',
      locationMethod: 'MANUAL' as const, coverageStatus: 'UNVERIFIED_MANUAL' as const,
      latitude: null, longitude: null,
    };
    expect(page.deliveryPoint(manual)).toBeNull();
    expect(page.deliveryMapUrl(manual)).toBeNull();
    expect(page.deliveryDirectionsUrl(manual)).toBeNull();
    expect(page.deliveryPoint({ ...manual, locationMethod: 'MAP' as const,
      coverageStatus: 'REGION_MATCHED' as const, latitude: 200, longitude: -17.5 })).toBeNull();
  });

  it('efface les informations confidentielles au changement de boutique', async () => {
    const page = await pageForCurrentOrder();
    expect(page.detail()?.contact.phoneE164).toBe('+221771234567');
    organization.set('org-b');
    await vi.waitFor(() => expect(api.list).toHaveBeenCalledWith('org-b', 0));
    expect(page.detail()).toBeNull();
    expect(page.financial()).toBeNull();
    expect(page.confirmCancellation()).toBe(false);
  });
});
