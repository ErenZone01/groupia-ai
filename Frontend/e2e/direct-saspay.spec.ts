import { test, expect } from '@playwright/test';
import { mockApi, noOverflow } from './api-mock';

for (const push of [false, true]) {
  test(`vente directe SasPay : devis, reprise et confirmation serveur (push=${push})`, async ({
    page,
  }) => {
    await mockApi(page);
    await page.setViewportSize({ width: 390, height: 844 });
    const token = 'D'.repeat(43);
    const quote = {
      id: 'direct-fee',
      provider: 'SASPAY',
      currency: 'XOF',
      networkLabel: 'Wave',
      saleAmount: 20000,
      collectionFee: 500,
      totalCharged: 20500,
      merchantNet: 20000,
      groupiaCommission: 0,
      expiresAt: '2099-12-01T00:00:00Z',
    };
    let pending = false,
      paid = false;
    const charges: unknown[] = [];
    await page.route('**/api/public/direct-orders/*', (route) => {
      const request = route.request();
      expect(request.method()).toBe('POST');
      expect(request.postDataJSON().token).toBe(token);
      expect(request.url()).not.toContain(token);
      const path = new URL(request.url()).pathname;
      const amount = { orderNumber: 'DIR-000000000123', currency: 'XOF', acceptedTotalXof: 20000 };
      const attempt = {
        id: 'attempt',
        status: 'PENDING',
        expectedAmount: 20000,
        currency: 'XOF',
        reconciliationRequired: false,
        checkoutUrl: push ? null : 'https://pay.saspay.me/direct-fixture',
      };
      if (path.endsWith('/lookup'))
        return route.fulfill({
          json: {
            ...amount,
            status: paid ? 'PAYMENT_CONFIRMED' : 'PENDING_PAYMENT_INTEGRATION',
            fulfillmentMethod: 'PICKUP',
            goodsSubtotalXof: 20000,
            platformFeeXof: 0,
            shippingFeeXof: 0,
            amountBeforeShippingXof: 20000,
          },
        });
      if (path.endsWith('/payment-status'))
        return route.fulfill({
          json: {
            ...amount,
            financialStatus: paid
              ? 'SETTLEMENT_VERIFIED'
              : pending
                ? 'PAYMENT_IN_PROGRESS'
                : 'PREPARED',
            preparationStatus: pending ? 'UNAVAILABLE' : 'PREPARED',
            paymentAvailable: !pending && !paid,
            availableProviders: ['SASPAY'],
            latestAttempt: pending && !paid ? { provider: 'SASPAY', quote, attempt } : null,
          },
        });
      if (path.endsWith('/payment-quotes')) return route.fulfill({ json: [quote] });
      if (path.endsWith('/payments')) {
        expect(request.postDataJSON()).toEqual({ token, feeQuoteId: quote.id });
        expect(request.headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
        charges.push(request.postDataJSON());
        pending = true;
        return route.fulfill({ json: attempt });
      }
      return route.fulfill({ status: 404, json: {} });
    });
    await page.goto('/v/77777777-7777-4777-8777-777777777777/commande#access=' + token);
    await page.getByRole('button', { name: 'Voir les frais SasPay' }).click();
    await expect(page.getByText('Net conservé pour la vendeuse', { exact: false })).toContainText(
      /20.?000/,
    );
    expect(charges).toHaveLength(0);
    await page.getByRole('button', { name: 'Accepter les frais et payer', exact: false }).click();
    await page.reload();
    if (push)
      await expect(
        page.getByText('Confirmez la demande sur votre téléphone', { exact: false }),
      ).toBeVisible();
    else
      await expect(page.getByRole('link', { name: 'Ouvrir le paiement sécurisé' })).toHaveAttribute(
        'href',
        'https://pay.saspay.me/direct-fixture',
      );
    await expect(page.getByText('Montant accepté', { exact: false })).toContainText(/20.?500/);
    await expect(page.getByText('Règlement vérifié', { exact: true })).toHaveCount(0);
    expect(charges).toHaveLength(1);
    paid = true;
    await page.getByRole('button', { name: 'Actualiser le statut' }).click();
    await expect(page.getByText('Règlement vérifié', { exact: true })).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'Accepter les frais et payer', exact: false }),
    ).toHaveCount(0);
    expect(charges).toHaveLength(1);
    await noOverflow(page);
  });
}
