import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { apiTestProviders } from '../../testing/api-test';
import { FulfillmentBatch, FulfillmentRow } from '../../api/generated/models';
import { DistributionPanel } from './distribution-panel';
import { DistributionOrder } from './distribution-order';
import { filterDistribution } from './distribution-labels';
import { OrderTimeline } from '../tracking/order-timeline';
const row: FulfillmentRow = {
  orderId: 'one',
  orderNumber: 'GRP-1',
  customerName: 'Awa Diop',
  version: 2,
  status: 'CONFIRMED',
  fulfillmentStatus: 'NOT_READY',
  canMarkReady: true,
  shippingSettlement: {
    status: 'SETTLED',
    finalShippingAmount: 3000,
    totalShippingPaidAmount: 3000,
    currency: 'XOF',
  },
};
const batch: FulfillmentBatch = {
  canManageFulfillment: true,
  campaign: { id: 'campaign', organizationId: 'shop', status: 'ARRIVED' },
  counts: {
    total: 1,
    toPrepare: 1,
    eligible: 1,
    ready: 0,
    pickedUp: 0,
    paymentBlocked: 0,
    customerCredits: 0,
  },
  orders: [row],
};
const base = '/api/organizations/shop/grouping-campaigns/campaign';
describe('Distribution : filtres et séparation des états', () => {
  it('recherche les noms accentués et numéros', () => {
    expect(filterDistribution([{ ...row, customerName: 'Éva Ndiaye' }], 'eva', 'all')).toHaveLength(
      1,
    );
    expect(filterDistribution([row], 'grp-1', 'eligible')).toHaveLength(1);
    expect(filterDistribution([row], 'inconnue', 'all')).toHaveLength(0);
  });
  it('sépare dettes, paiement incertain et crédit', () => {
    const rows = [
      row,
      {
        ...row,
        orderId: 'debt',
        readinessBlocker: 'FULFILLMENT_SHIPPING_BALANCE_DUE',
        shippingSettlement: { status: 'BALANCE_DUE' as const },
      },
      { ...row, orderId: 'credit', shippingSettlement: { status: 'CREDIT_DUE' as const } },
      {
        ...row,
        orderId: 'pending',
        readinessBlocker: 'SHIPPING_BALANCE_PAYMENT_IN_PROGRESS',
        shippingSettlement: { status: 'SETTLED' as const, paymentInProgress: true },
      },
    ];
    expect(filterDistribution(rows, '', 'eligible')).toHaveLength(2);
    expect(filterDistribution(rows, '', 'payment')).toHaveLength(2);
    expect(filterDistribution(rows, '', 'credit')).toHaveLength(1);
  });
});
describe('Distribution : confirmation physique', () => {
  let fixture: ComponentFixture<DistributionOrder>;
  beforeEach(() => {
    fixture = TestBed.createComponent(DistributionOrder);
    fixture.componentRef.setInput('row', row);
    fixture.detectChanges();
  });
  afterEach(() => fixture.destroy());
  it('n’émet rien sans attestation explicite', () => {
    const c = fixture.componentInstance;
    const emit = vi.spyOn(c.action, 'emit');
    c.open('ready');
    c.submit();
    expect(emit).not.toHaveBeenCalled();
    c.confirmed = true;
    c.submit();
    expect(emit).toHaveBeenCalledWith({ row, type: 'ready', reason: undefined });
  });
  it('exige un motif de révocation et le nettoie', () => {
    const c = fixture.componentInstance;
    const emit = vi.spyOn(c.action, 'emit');
    c.open('revoke');
    c.reason = ' ';
    c.submit();
    expect(emit).not.toHaveBeenCalled();
    c.reason = '  Article manquant  ';
    c.submit();
    expect(emit).toHaveBeenCalledWith({ row, type: 'revoke', reason: 'Article manquant' });
  });
  it('désactive la seconde action pendant la requête', () => {
    const c = fixture.componentInstance;
    const emit = vi.spyOn(c.action, 'emit');
    c.open('pickup');
    c.confirmed = true;
    fixture.componentRef.setInput('disabled', true);
    c.submit();
    expect(emit).not.toHaveBeenCalled();
  });
  it('affiche le crédit sans bloquer les actions accordées par le serveur', () => {
    fixture.componentRef.setInput('row', {
      ...row,
      shippingSettlement: { status: 'CREDIT_DUE', creditAmount: 500, currency: 'XOF' },
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Ce crédit n’empêche pas le retrait');
    expect(fixture.nativeElement.textContent).toContain('Marquer prête');
  });
  it('conserve dates et historique sans proposer de nouveau retrait', () => {
    fixture.componentRef.setInput('row', {
      ...row,
      fulfillmentStatus: 'PICKED_UP',
      canMarkReady: false,
      pickedUpAt: '2026-10-04T12:00:00Z',
      readyForPickupAt: '2026-10-03T12:00:00Z',
      history: [
        {
          id: 'e',
          type: 'READINESS_REVOKED',
          note: 'Article manquant',
          recordedAt: '2026-10-02T12:00:00Z',
        },
      ],
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Retirée le');
    expect(fixture.nativeElement.textContent).toContain('Article manquant');
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });
});
describe('Distribution : API et concurrence', () => {
  let fixture: ComponentFixture<DistributionPanel>, http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DistributionPanel);
    fixture.componentRef.setInput('organizationId', 'shop');
    fixture.componentRef.setInput('campaignId', 'campaign');
    fixture.detectChanges();
    http.expectOne(base + '/fulfillment').flush(batch);
  });
  afterEach(() => {
    fixture.destroy();
    http.verify();
  });
  it('présente les compteurs backend et recharge sans calcul financier', async () => {
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('1 commande(s) éligible(s)');
    expect(fixture.componentInstance.rows()).toHaveLength(1);
  });
  it('envoie seulement la version et la confirmation, une fois', async () => {
    await fixture.whenStable();
    const c = fixture.componentInstance;
    const pending = c.perform({ row, type: 'ready' });
    void c.perform({ row, type: 'ready' });
    const req = http.expectOne(base + '/orders/one/ready-for-pickup');
    expect(req.request.body).toEqual({ version: 2, confirmed: true });
    req.flush({
      ...batch,
      campaign: { ...batch.campaign, status: 'DISTRIBUTING' },
      orders: [{ ...row, version: 3, fulfillmentStatus: 'READY_FOR_PICKUP' }],
    });
    await pending;
    expect(c.busy()).toBe(false);
    expect(c.message()).toContain('disponible');
  });
  it('recharge après 409 et exige une nouvelle confirmation', async () => {
    await fixture.whenStable();
    const c = fixture.componentInstance;
    const pending = c.perform({ row, type: 'pickup' });
    http
      .expectOne(base + '/orders/one/picked-up')
      .flush({ code: 'ORDER_VERSION_CONFLICT' }, { status: 409, statusText: 'Conflict' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne(base + '/fulfillment').flush(batch);
    await pending;
    expect(c.error()).toContain('autre personne');
    expect(c.busy()).toBe(false);
  });
  it('conserve un motif privé dans la seule mutation de révocation', async () => {
    await fixture.whenStable();
    const pending = fixture.componentInstance.perform({
      row,
      type: 'revoke',
      reason: 'Article manquant',
    });
    const req = http.expectOne(base + '/orders/one/revoke-readiness');
    expect(req.request.body).toEqual({ version: 2, reason: 'Article manquant' });
    req.flush(batch);
    await pending;
  });
  it('expose la lecture seule accordée au viewer', async () => {
    await fixture.whenStable();
    const pending = fixture.componentInstance.load();
    http
      .expectOne(base + '/fulfillment')
      .flush({ ...batch, canManageFulfillment: false, orders: [{ ...row, canMarkReady: false }] });
    await pending;
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('lecture seule');
    expect(fixture.nativeElement.textContent).not.toContain('Marquer prête');
  });
});
describe('Suivi cliente : préparation et remise sont distinctes', () => {
  it('montre une étape de remise et les timestamps serveur', () => {
    const fixture = TestBed.createComponent(OrderTimeline);
    fixture.componentRef.setInput('order', {
      status: 'COMPLETED',
      fulfillmentStatus: 'PICKED_UP',
      readyForPickupAt: '2026-10-03T12:00:00Z',
      pickedUpAt: '2026-10-04T12:00:00Z',
    });
    fixture.detectChanges();
    expect(fixture.componentInstance.stage()).toBe(6);
    expect(fixture.componentInstance.steps()[5].date).toBe('2026-10-04T12:00:00Z');
    expect(fixture.nativeElement.textContent).toContain('Commande récupérée');
    fixture.destroy();
  });
});
