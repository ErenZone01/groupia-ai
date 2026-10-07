import { test, expect } from '@playwright/test';
import { mockApi, session } from './api-mock';

test('essai, banner et paiement désactivé restent explicites', async ({ page }) => {
  const starts: string[] = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.endsWith('/subscription/start')) starts.push(request.url());
  });
  await session(page);
  await mockApi(page);
  await page.route('**/api/organizations/*/subscription', (route) =>
    route.fulfill({
      json: {
        access: {
          status: 'TRIALING',
          canStartNewBusiness: true,
          trialEndsAt: '2026-11-01T00:00:00Z',
          reason: 'TRIAL_ACTIVE',
        },
        canManageSubscription: true,
        billingProviderAvailable: false,
        billingProviderSetupPending: false,
        planConfigured: false,
        billingContact: { name: 'Boutique du Soleil', phoneE164: '+221771234567' },
      },
    }),
  );

  await page.goto('/app/abonnement');
  await expect(page.getByRole('heading', { name: 'Abonnement' })).toBeVisible();
  await expect(
    page.getByText(
      "Le paiement d'abonnement n'est pas disponible pour le moment. Aucun nouveau paiement ne sera envoyé.",
    ),
  ).toBeVisible();
  await expect(page.getByRole('link', { name: "Voir l'abonnement" })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Configurer le paiement' })).toBeDisabled();
  expect(starts).toEqual([]);
});
