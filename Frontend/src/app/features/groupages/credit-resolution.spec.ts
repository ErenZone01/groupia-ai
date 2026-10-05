import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { apiTestProviders } from '../../testing/api-test';
import { CreditResolution } from './credit-resolution';
import { RefundSummary, refundMethod } from '../../shared/ui/refund-summary';
import { ShippingSettlement } from '../../shared/ui/shipping-settlement';
import type { CreditResolution as CreditData, FulfillmentRow } from '../../api/generated/models';
const path = '/api/organizations/shop/orders/order/shipping-credit';
const row: FulfillmentRow = {
  orderId: 'order',
  version: 4,
  fulfillmentStatus: 'PICKED_UP',
  shippingSettlement: { status: 'CREDIT_DUE', creditAmount: 500, currency: 'XOF' },
};
const credit: CreditData = {
  canManage: true,
  canResolve: true,
  payoutsEnabled: false,
  settlement: row.shippingSettlement,
  refunds: [],
};
describe('Régularisation du crédit : serveur et confirmation', () => {
  let fixture: ComponentFixture<CreditResolution>, http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CreditResolution);
    fixture.componentRef.setInput('organizationId', 'shop');
    fixture.componentRef.setInput('row', row);
    fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    dialog.showModal = vi.fn(() => {
      dialog.open = true;
    });
    dialog.close = vi.fn(() => {
      dialog.open = false;
    });
  });
  afterEach(() => {
    http.verify();
    fixture.destroy();
  });
  async function open(value = credit) {
    const loading = fixture.componentInstance.open();
    http.expectOne(path).flush(value);
    await loading;
    fixture.detectChanges();
  }
  it('affiche une régularisation indisponible avant retrait', () => {
    fixture.componentRef.setInput('row', { ...row, fulfillmentStatus: 'READY_FOR_PICKUP' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('disponible après retrait');
    expect(fixture.nativeElement.querySelector('button:not(dialog button)')).toBeNull();
  });
  it('désactive Bictorys selon la capacité serveur', async () => {
    await open();
    expect(fixture.componentInstance.method).toBe('MANUAL_EXTERNAL');
    expect(fixture.nativeElement.querySelector('input[value="BICTORYS_PAYOUT"]').disabled).toBe(
      true,
    );
  });
  it.each(['WAVE', 'ORANGE_MONEY', 'BANK_TRANSFER', 'OTHER'])(
    'exige une référence et une confirmation pour %s',
    async (method) => {
      await open();
      const c = fixture.componentInstance;
      c.externalMethod = method;
      c.confirmed = true;
      expect(c.valid()).toBe(false);
      c.reference = 'REF-123';
      c.confirmed = false;
      expect(c.valid()).toBe(false);
      c.confirmed = true;
      expect(c.valid()).toBe(true);
    },
  );
  it('exige une note pour les espèces et accepte une référence vide', async () => {
    await open();
    const c = fixture.componentInstance;
    c.externalMethod = 'CASH';
    c.confirmed = true;
    expect(c.valid()).toBe(false);
    c.note = 'Remise en main propre';
    expect(c.valid()).toBe(true);
  });
  it('envoie uniquement les choix, sans montant ni destinataire, et bloque le double clic', async () => {
    await open({ ...credit, payoutsEnabled: true });
    const c = fixture.componentInstance;
    c.confirmed = true;
    const emit = vi.spyOn(c.changed, 'emit');
    const sending = c.submit();
    await c.submit();
    const request = http.expectOne(path);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      version: 4,
      method: 'BICTORYS_PAYOUT',
      externalMethod: 'WAVE',
      confirmed: true,
    });
    request.flush({
      ...credit,
      canResolve: false,
      refunds: [{ status: 'PENDING', method: 'BICTORYS_PAYOUT', amount: 500 }],
    });
    await sending;
    expect(emit).toHaveBeenCalledOnce();
  });
  it('recharge après une réponse réseau incertaine avant toute nouvelle action', async () => {
    await open({ ...credit, payoutsEnabled: true });
    const c = fixture.componentInstance;
    c.confirmed = true;
    const sending = c.submit();
    http.expectOne(path).error(new ProgressEvent('error'));
    await vi.waitFor(() => {
      expect(c.confirmed).toBe(false);
    });
    http
      .expectOne(path)
      .flush({
        ...credit,
        canResolve: false,
        refunds: [{ status: 'REVIEW_REQUIRED', reconciliationRequired: true, amount: 500 }],
      });
    await sending;
    fixture.detectChanges();
    expect(c.valid()).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('à vérifier');
  });
  it('respecte la capacité de lecture seule renvoyée par le serveur', async () => {
    await open({ ...credit, canManage: false, canResolve: false });
    fixture.componentInstance.confirmed = true;
    fixture.componentInstance.reference = 'X';
    expect(fixture.componentInstance.valid()).toBe(false);
    await fixture.componentInstance.submit();
    http.expectNone(path);
  });
  it('ne ferme pas la modale avec Échap pendant un envoi', async () => {
    await open();
    fixture.componentInstance.busy.set(true);
    const event = new Event('cancel', { cancelable: true });
    fixture.componentInstance.cancel(event);
    expect(event.defaultPrevented).toBe(true);
  });
  it('réconcilie sans réinitier le payout', async () => {
    await open({
      ...credit,
      canResolve: false,
      refunds: [{ id: 'refund', method: 'BICTORYS_PAYOUT', status: 'PENDING' }],
    });
    const checking = fixture.componentInstance.reconcile('refund');
    http.expectOne('/api/organizations/shop/refunds/refund/reconcile').flush({ status: 'PENDING' });
    await vi.waitFor(() =>
      http
        .expectOne(path)
        .flush({ ...credit, canResolve: false, refunds: [{ status: 'PENDING' }] }),
    );
    await checking;
    http.expectNone((r) => r.url === path && r.method === 'POST');
  });
});
describe('Messages cliente de régularisation', () => {
  it.each([
    ['MANUAL_EXTERNAL', 'SUCCEEDED', 'régularisés par la boutique'],
    ['BICTORYS_PAYOUT', 'SUCCEEDED', 'remboursés'],
    ['BICTORYS_PAYOUT', 'PENDING', 'en cours'],
    ['BICTORYS_PAYOUT', 'REVIEW_REQUIRED', 'à vérifier'],
    ['BICTORYS_PAYOUT', 'FAILED', 'non abouti'],
  ])('explique %s %s sans enums techniques', (method, status, expected) => {
    const fixture = TestBed.createComponent(RefundSummary);
    fixture.componentRef.setInput('refunds', [
      {
        method,
        status,
        amount: 500,
        currency: 'XOF',
        externalMethod: 'WAVE',
        completedAt: '2026-10-05T12:00:00Z',
      },
    ]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(expected);
    expect(fixture.nativeElement.textContent).not.toContain(method);
    fixture.destroy();
  });
  it('conserve le crédit visible avant retrait et explique la disponibilité', () => {
    const fixture = TestBed.createComponent(ShippingSettlement);
    fixture.componentRef.setInput('value', {
      status: 'CREDIT_DUE',
      creditAmount: 500,
      currency: 'XOF',
      creditStableForRefund: false,
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('après la remise');
    expect(fixture.nativeElement.textContent).toContain('Crédit en votre faveur');
    fixture.destroy();
  });
  it('présente les moyens de restitution dans le vocabulaire client', () => {
    expect(refundMethod('ORANGE_MONEY')).toBe('Orange Money');
    expect(refundMethod('CASH')).toBe('Espèces');
  });
});
