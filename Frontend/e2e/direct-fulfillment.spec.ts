import { test, expect } from '@playwright/test';
import { mockApi, session, noOverflow } from './api-mock';

for (const method of ['PICKUP', 'DELIVERY']) {
  test(`vente directe : ${method}, remise confirmée et clôture avec historique`, async ({
    page,
  }) => {
    await session(page);
    await mockApi(page);
    await page.setViewportSize({ width: 390, height: 844 });
    let status = method === 'PICKUP' ? 'READY_FOR_PICKUP' : 'READY_FOR_DISPATCH';
    let version = 3;
    const history: { id: string; fromStatus: string; toStatus: string; occurredAt: string }[] = [];
    const summary = () => ({
      id: 'order-1',
      orderNumber: 'DIR-000000000001',
      status,
      fulfillmentMethod: method,
      currency: 'XOF',
      goodsSubtotalXof: 20000,
      platformFeeXof: 0,
      amountBeforeShippingXof: 20000,
      shippingFeeXof: 0,
      acceptedTotalXof: 20000,
      shippingQuoted: true,
      placedAt: '2026-11-08T00:00:00Z',
      itemCount: 1,
    });
    await page.route('**/api/organizations/*/direct-orders**', (route) => {
      const request = route.request(),
        path = new URL(request.url()).pathname;
      if (path.endsWith('/fulfillment-history')) return route.fulfill({ json: history });
      if (path.endsWith('/payment-status'))
        return route.fulfill({
          json: {
            orderNumber: 'DIR-000000000001',
            acceptedTotalXof: 20000,
            financialStatus: 'SETTLEMENT_VERIFIED',
            fulfillmentAllowed: false,
          },
        });
      if (path.endsWith('/fulfillment')) {
        const body = request.postDataJSON();
        expect(body.confirmed).toBe(true);
        expect(body.version).toBe(version);
        expect(request.headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
        const next = {
          PICKUP: 'PICKED_UP',
          DISPATCH: 'DISPATCHED',
          DELIVER: 'DELIVERED',
          COMPLETE: 'COMPLETED',
        }[body.action as string];
        expect(next).toBeTruthy();
        const event = {
          id: String(version),
          fromStatus: status,
          toStatus: next!,
          occurredAt: '2026-11-08T00:01:00Z',
        };
        history.push(event);
        status = next!;
        version++;
        return route.fulfill({ json: event });
      }
      if (path.endsWith('/order-1'))
        return route.fulfill({
          json: {
            order: summary(),
            version,
            contact: { firstName: 'Awa', lastName: 'Diop', phoneE164: '+221771234567' },
            deliveryAddress: null,
            platformFeeRule: { amount: '0' },
            items: [],
          },
        });
      return route.fulfill({ json: { items: [summary()], total: 1, page: 0, size: 20 } });
    });
    await page.goto('/app/vente-directe/commandes');
    await page.getByRole('button', { name: 'Voir le détail' }).click();
    const steps =
      method === 'PICKUP'
        ? ['Confirmer le retrait par la cliente', 'Clôturer la commande']
        : ['Confirmer l’expédition', 'Confirmer la livraison à la cliente', 'Clôturer la commande'];
    for (const label of steps) {
      const button = page.getByRole('button', { name: label, exact: true });
      await expect(button).toBeDisabled();
      await page
        .getByRole('checkbox', { name: 'Je confirme que cette étape est réellement effectuée.' })
        .check();
      await button.click();
      await expect(button).toHaveCount(0);
    }
    expect(status).toBe('COMPLETED');
    await page.getByRole('button', { name: 'Voir l’historique des remises' }).click();
    await expect(page.getByText(/Clôturée ·/)).toBeVisible();
    expect(history).toHaveLength(steps.length);
    await noOverflow(page);
  });
}
