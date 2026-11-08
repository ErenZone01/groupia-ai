import { test, expect } from '@playwright/test';
import { mockApi, session, noOverflow } from './api-mock';
for (const kind of ['PAYOUT', 'REFUND']) {
  test(`registre fournisseurs : ${kind} simulé, frais séparés et reprise`, async ({ page }) => {
    await session(page);
    await mockApi(page);
    await page.setViewportSize({ width: 390, height: 844 });
    let started = 0,
      reconciled = 0;
    const records: Record<string, unknown>[] = [];
    const row = {
      id: 'collection',
      paymentRequestId: 'request',
      targetType: 'DIRECT_ORDER',
      targetId: 'order',
      purpose: 'DIRECT_ORDER_INITIAL',
      provider: 'SASPAY',
      accountReference: 'a'.repeat(64),
      currency: 'XOF',
      sellerPrincipal: 20000,
      platformFee: 0,
      collectionFee: 500,
      customerCharged: 20500,
      confirmedAt: '2026-11-08T00:00:00Z',
      financialStatus: 'VERIFIED',
      payoutStatus: 'PROVIDER_APPROVAL_REQUIRED',
    };
    await page.route('**/api/organizations/*/provider-collections**', (route) => {
      const req = route.request(),
        path = new URL(req.url()).pathname;
      if (path.endsWith('/refresh'))
        return route.fulfill({ json: { recorded: 0, nextCursor: null } });
      if (path.endsWith('/simulation-quote'))
        return route.fulfill({
          json: {
            mode: 'SIMULATION',
            enabled: true,
            kind,
            collectionId: row.id,
            currency: 'XOF',
            recipientNet: 20000,
            fee: 200,
            simulatedCompanyFunding: 200,
            reference: 'quote',
            realOperationsEnabled: false,
          },
        });
      if (path.endsWith('/simulations')) {
        if (req.method() === 'GET') return route.fulfill({ json: records });
        started++;
        expect(req.headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
        expect(req.postDataJSON()).toEqual({ kind, quoteReference: 'quote', confirmed: true });
        const operation = {
          id: 'sim',
          collectionId: row.id,
          mode: 'SIMULATION',
          kind,
          status: 'REVIEW_REQUIRED',
          sourceProvider: 'SASPAY',
          accountReference: row.accountReference,
          currency: 'XOF',
          recipientNet: 20000,
          fee: 200,
          simulatedCompanyFunding: 200,
          reference: null,
          createdAt: '2026-11-08T00:00:00Z',
          completedAt: null,
        };
        records.push(operation);
        return route.fulfill({ json: operation });
      }
      return route.fulfill({
        json: { items: [row], nextCursor: null, canSimulatePayout: true, canSimulateRefund: true },
      });
    });
    await page.route('**/api/organizations/*/marketplace-simulations/*/reconcile', (route) => {
      reconciled++;
      records[0] = {
        ...records[0],
        status: 'SUCCEEDED',
        reference: 'SIM-sim',
        completedAt: '2026-11-08T00:01:00Z',
      };
      return route.fulfill({ json: records[0] });
    });
    await page.goto('/app/encaissements-fournisseurs');
    await expect(
      page.getByRole('heading', { name: 'Encaissements par fournisseur' }),
    ).toBeVisible();
    await page
      .getByRole('button', {
        name:
          kind === 'PAYOUT' ? 'Préparer un versement simulé' : 'Préparer un remboursement simulé',
      })
      .click();
    await expect(page.getByText('Budget fictif de Groupia pour les frais')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Exécuter la simulation' })).toBeDisabled();
    await page.getByRole('checkbox').check();
    await page.getByRole('button', { name: 'Exécuter la simulation' }).click();
    await expect(page.getByRole('status')).toContainText('Simulation à vérifier');
    await page.reload();
    await page.getByRole('button', { name: 'Historique des simulations' }).click();
    await page.getByRole('button', { name: 'Revérifier la simulation' }).click();
    await expect(page.getByRole('status')).toContainText('Simulation réussie');
    expect(started).toBe(1);
    expect(reconciled).toBe(1);
    await noOverflow(page);
  });
}
