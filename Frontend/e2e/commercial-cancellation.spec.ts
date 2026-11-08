import { test, expect } from '@playwright/test';
import { mockApi, session, shopId, groupId, noOverflow } from './api-mock';

for (const direct of [false, true]) {
  test(`annulation ${direct ? 'directe' : 'groupage'} : déclaration externe explicite sans transfert`, async ({
    page,
  }) => {
    await session(page);
    await mockApi(page);
    await page.setViewportSize({ width: 390, height: 844 });
    let status = direct ? 'READY_FOR_PICKUP' : 'CONFIRMED',
      version = 3,
      cancelled = false,
      declared = false;
    let cancellationKey = '',
      declarationKey = '';
    const order = () => ({
      id: 'order-1',
      orderNumber: direct ? 'DIR-000000000001' : 'GRP-000000000001',
      status,
      fulfillmentStatus: cancelled ? 'CANCELLED' : 'NOT_READY',
      fulfillmentMethod: 'PICKUP',
      currency: 'XOF',
      goodsSubtotal: 2000,
      goodsSubtotalXof: 2000,
      platformFeeAmount: 0,
      platformFeeXof: 0,
      initialShippingChargeAmount: 0,
      initialAmountDue: 2000,
      amountBeforeShippingXof: 2000,
      shippingFeeXof: 0,
      acceptedTotalXof: 2000,
      shippingQuoted: true,
      placedAt: '2026-11-08T00:00:00Z',
      itemCount: 1,
      items: [],
    });
    const refund = () => ({
      refund: {
        id: 'case-1',
        status: declared ? 'EXTERNALLY_RECORDED' : 'REFUND_DUE',
        version: declared ? 1 : 0,
        currency: 'XOF',
        collectedPrincipal: 2000,
        previouslyRefunded: 0,
        amountDue: 2000,
        legacyPlatformFee: 0,
        reason: 'Demande cliente',
        maskedReference: declared ? '…1234' : null,
      },
      canDeclare: !declared,
      providerRefundAvailable: false,
      historicalFeeCompensationDue: 0,
    });
    await page.route(`**/api/organizations/*/${direct ? 'direct-orders' : 'orders'}**`, (route) => {
      const req = route.request(),
        path = new URL(req.url()).pathname;
      if (path.endsWith(direct ? '/paid-cancellation' : '/cancellation')) {
        if (req.method() === 'POST') {
          expect(req.postDataJSON()).toEqual({
            version,
            reason: 'Demande cliente',
            confirmed: true,
          });
          cancellationKey = req.headers()['idempotency-key'];
          expect(cancellationKey).toMatch(/^[0-9a-f-]{36}$/);
          cancelled = true;
          status = 'REFUND_PENDING';
          version++;
        }
        return route.fulfill({
          json: {
            caseId: cancelled ? 'case-1' : null,
            status: cancelled ? 'REFUND_DUE' : null,
            orderStatus: status,
            orderVersion: version,
            canCancel: !cancelled,
            blocker: null,
          },
        });
      }
      if (path.endsWith('/payment-status'))
        return route.fulfill({
          json: { financialStatus: 'SETTLEMENT_VERIFIED', fulfillmentAllowed: false },
        });
      if (path.endsWith('/order-1'))
        return route.fulfill({
          json: {
            id: 'order-1',
            organizationId: shopId,
            campaignId: groupId,
            version,
            order: order(),
            customerContactSnapshot: { firstName: 'Awa', lastName: 'Diop' },
            contact: { firstName: 'Awa', lastName: 'Diop', phoneE164: '+221771234567' },
            deliveryAddress: null,
            platformFeeRule: { amount: '0' },
            items: [],
          },
        });
      return route.fulfill({
        json: { items: [order()], total: 1, page: 0, size: 20, totalElements: 1, totalPages: 1 },
      });
    });
    await page.route('**/api/organizations/*/commercial-refunds/**', (route) => {
      const req = route.request();
      if (req.method() === 'POST') {
        expect(req.postDataJSON()).toEqual({
          version: 0,
          externalMethod: 'WAVE',
          reference: 'fictitious-1234',
          note: null,
          confirmed: true,
        });
        declarationKey = req.headers()['idempotency-key'];
        expect(declarationKey).toMatch(/^[0-9a-f-]{36}$/);
        declared = true;
        status = 'REFUNDED';
        version++;
      }
      return route.fulfill({ json: refund() });
    });
    await page.goto(direct ? '/app/vente-directe/commandes' : '/app/orders/order-1');
    if (direct) await page.getByRole('button', { name: 'Voir le détail' }).click();
    const open = page.getByRole('button', { name: 'Ouvrir le suivi d’annulation' });
    await open.click();
    await page.getByLabel('Motif d’annulation').fill('Demande cliente');
    const cancel = page.getByRole('button', { name: 'Confirmer l’annulation', exact: true });
    await expect(cancel).toBeDisabled();
    await page
      .getByRole('checkbox', { name: 'Je confirme l’annulation de cette commande.' })
      .check();
    await cancel.click();
    await expect(open).toBeVisible();
    await open.click();
    const panel = page.getByRole('region', { name: 'Annulation et remboursement' });
    await expect(panel.getByText('Aucun transfert automatique', { exact: false })).toBeVisible();
    await page.getByLabel('Référence externe', { exact: true }).fill('fictitious-1234');
    const record = page.getByRole('button', { name: 'Enregistrer le remboursement externe' });
    await expect(record).toBeDisabled();
    await page.getByRole('checkbox', { name: /Je confirme avoir déjà restitué/ }).check();
    await record.click();
    await expect(open).toBeVisible();
    await open.click();
    await expect(
      panel.getByText('Cette déclaration ne constitue pas une vérification du prestataire.', {
        exact: false,
      }),
    ).toBeVisible();
    expect(cancellationKey).not.toBe(declarationKey);
    await expect(record).toHaveCount(0);
    await noOverflow(page);
  });
}
