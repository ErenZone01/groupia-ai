import { test, expect } from '@playwright/test';
import { mockApi, noOverflow } from './api-mock';

/** All provider traffic is intercepted. Never trigger a real SasPay payment. */
for (const redirect of [false, true]) {
  test(`vente directe SasPay hébergé : retour et confirmation serveur (redirect=${redirect})`, async ({
    page,
  }) => {
    await mockApi(page);
    await page.setViewportSize({ width: 390, height: 844 });
    const token = 'D'.repeat(43);
    let pending = false;
    let paid = false;
    const created: { body: unknown; key: string }[] = [];
    let hostedNavigations = 0;

    // Groupia must never call the real PSP during E2E: intercept the hosted
    // boundary at browser-context level, including top-level navigations.
    await page.context().route('https://**/*', (route) => route.abort('blockedbyclient'));
    await page.context().route('https://checkout.saspay.me/**', (route) => {
      hostedNavigations++;
      return route.fulfill({
        status: 200,
        contentType: 'text/html',
        body: '<!doctype html><html><body><title>Simulated provider</title></body></html>',
      });
    });

    await page.route('**/api/public/direct-orders/*', (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (request.method() !== 'POST') return route.fulfill({ status: 405 });
      expect(request.postDataJSON().token).toBe(token);
      expect(request.url()).not.toContain(token);
      const amount = {
        orderNumber: 'DIR-000000000123',
        currency: 'XOF',
        acceptedTotalXof: 1000,
      };
      const attempt = {
        id: 'synthetic-attempt',
        status: 'PENDING',
        expectedAmount: 1000,
        currency: 'XOF',
        reconciliationRequired: false,
        checkoutUrl: redirect ? 'https://checkout.saspay.me/checkout/synthetic' : null,
      };
      if (path.endsWith('/lookup')) {
        return route.fulfill({
          json: {
            ...amount,
            status: paid ? 'PAYMENT_CONFIRMED' : 'PENDING_PAYMENT_INTEGRATION',
            fulfillmentMethod: 'PICKUP',
            goodsSubtotalXof: 1000,
            platformFeeXof: 0,
            shippingFeeXof: 0,
            amountBeforeShippingXof: 1000,
          },
        });
      }
      if (path.endsWith('/payment-status')) {
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
            latestAttempt: pending && !paid
              ? { provider: 'SASPAY', quote: null, attempt }
              : null,
          },
        });
      }
      if (path.endsWith('/payments')) {
        expect(request.postDataJSON()).toEqual({
          token,
          provider: 'SASPAY',
          payerEmail: 'client@example.com',
        });
        expect(request.headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
        created.push({
          body: request.postDataJSON(),
          key: request.headers()['idempotency-key']!,
        });
        pending = true;
        return route.fulfill({ json: attempt });
      }
      return route.fulfill({ status: 404, json: {} });
    });

    await page.goto('/v/77777777-7777-4777-8777-777777777777/commande#access=' + token);
    await expect(page.getByRole('button', { name: /Payer sur SasPay/ })).toBeVisible();
    await page.getByLabel(/Adresse e-mail du payeur/).fill('client@example.com');
    await expect(page.getByText(/Les frais des opérateurs/)).toHaveCount(0);
    expect(created).toHaveLength(0);

    await page.getByRole('button', { name: /Payer sur SasPay/ }).click();
    await expect.poll(() => created.length).toBe(1);
    if (redirect) {
      await expect.poll(() => hostedNavigations).toBe(1);
      await expect(page).toHaveURL('https://checkout.saspay.me/checkout/synthetic');
      // SasPay owns the hosted UI. E2E simulates its documented return
      // rather than asserting a page that Groupia doesn't control.
      await page.goto('/paiement/retour');
      await expect(page.getByRole('heading', { name: 'Suivi de ma commande' })).toBeVisible();
    } else {
      await expect(page.getByText('Règlement vérifié', { exact: true })).toHaveCount(0);
      await page.reload();
      await expect(page.getByText(/Paiement en cours chez le prestataire/)).toBeVisible();
    }
    expect(created).toHaveLength(1);
    paid = true;
    await page.getByRole('button', { name: 'Actualiser le statut' }).click();
    await expect(page.getByText('Règlement vérifié', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /Payer sur SasPay/ })).toHaveCount(0);
    expect(created).toHaveLength(1);
    await noOverflow(page);
  });
}
