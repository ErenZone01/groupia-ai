import { test, expect, Page } from '@playwright/test';
import { mockApi, session, noOverflow, shopId, shop } from './api-mock';

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
  await expect(page.getByRole('button', { name: 'Payer maintenant' })).toBeDisabled();
  expect(starts).toEqual([]);
});

async function billing(page: Page, status = 'TRIALING', manage = true) {
  await session(page);
  await mockApi(page);
  const state = {
    invoice: null as null | Record<string, unknown>,
    status,
    manage,
    posts: [] as { path: string; body: unknown; key: string }[],
    lose: false,
    providers: ['BICTORYS'],
    push: false,
  };
  const preview = {
    amount: 10000,
    currency: 'XOF',
    periodStart: status === 'PAST_DUE' ? new Date().toISOString() : '2099-11-01T00:00:00Z',
    periodEnd:
      status === 'PAST_DUE'
        ? new Date(Date.now() + 30 * 86400000).toISOString()
        : '2099-12-01T00:00:00Z',
  };
  await page.route('**/api/organizations/*/subscription**', async (route) => {
    const r = route.request(),
      path = new URL(r.url()).pathname;
    if (r.method() === 'POST')
      state.posts.push({ path, body: r.postDataJSON(), key: r.headers()['idempotency-key'] });
    if (path.endsWith('/invoice-preview')) return route.fulfill({ json: preview });
    if (path.endsWith('/history'))
      return route.fulfill({
        json:
          state.invoice?.['status'] === 'PAID'
            ? [{ ...preview, status: 'PAID', planCode: 'STANDARD_MONTHLY' }]
            : [],
      });
    if (path.endsWith('/invoices')) {
      if (r.method() === 'POST')
        state.invoice ??= {
          id: 'invoice',
          ...preview,
          status: 'OPEN',
          canPay: true,
          paymentStatus: 'PENDING',
        };
      return route.fulfill({
        json: r.method() === 'POST' ? state.invoice : state.invoice ? [state.invoice] : [],
      });
    }
    if (path.endsWith('/payment-quotes'))
      throw new Error('Hosted SasPay must not request obsolete operator fee quotes');
    if (path.endsWith('/payment')) {
      state.invoice = { ...state.invoice, canPay: false, attemptInProgress: true };
      if (r.postDataJSON()?.provider === 'SASPAY') {
        const attempt = {
          id: 'attempt',
          status: 'PENDING',
          checkoutUrl: state.push ? null : 'https://pay.saspay.me/fixture',
        };
        state.invoice = {
          ...state.invoice,
          latestAttempt: {
            provider: 'SASPAY',
            quote: null,
            attempt,
          },
        };
        if (state.lose) return route.abort('failed');
        return route.fulfill({ json: attempt });
      }
      if (state.lose) return route.abort('failed');
      return route.fulfill({
        json: {
          id: 'attempt',
          status: 'PENDING',
          checkoutUrl: 'https://pay.test.bictorys.com/fixture',
        },
      });
    }
    return route.fulfill({
      json: {
        access: {
          status: state.status,
          trialEndsAt: '2099-11-01T00:00:00Z',
          currentPeriodEndsAt: '2099-11-01T00:00:00Z',
          canStartNewBusiness: state.status !== 'PAST_DUE',
        },
        canManageSubscription: state.manage,
        billingProviderAvailable: true,
        availableProviders: state.providers,
        planConfigured: true,
        billingContact: { name: 'Titulaire', phoneE164: '+221771234567' },
      },
    });
  });
  return state;
}
test('OWNER trial crée puis paie une facture, confirmation serveur seule active la période', async ({
  page,
}) => {
  const state = await billing(page);
  await page.goto('/app/abonnement');
  await page.getByRole('button', { name: 'Payer maintenant' }).click();
  await expect(page.getByText('Paiement en attente de confirmation.')).toBeVisible();
  expect(state.posts).toHaveLength(2);
  expect(state.posts[0].body).toBeNull();
  expect(state.posts[1].body).toEqual({ provider: 'BICTORYS' });
  expect(state.posts.every((p) => /^[A-Za-z0-9._:-]{16,128}$/.test(p.key))).toBe(true);
  await expect(page.getByRole('link', { name: 'Ouvrir le paiement sécurisé' })).toBeVisible();
  await expect(page.getByText('Aucune période payée enregistrée.')).toBeVisible();
  state.invoice = { ...state.invoice, status: 'PAID', paymentStatus: 'SETTLED' };
  await page.getByRole('button', { name: 'Vérifier le paiement' }).click();
  await expect(page.getByText('Prochaine période déjà réglée', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Payer maintenant' })).toHaveCount(0);
  expect(state.posts).toHaveLength(2);
});
test('renouvellement prématuré bloqué mais vérification du paiement existant accessible', async ({
  page,
}) => {
  const state = await billing(page, 'ACTIVE');
  state.providers = ['BICTORYS', 'SASPAY'];
  const day = 86400000;
  const nextStart = new Date(Date.now() + 8 * day).toISOString();
  const nextEnd = new Date(Date.now() + 38 * day).toISOString();
  await page.route('**/api/organizations/*/subscription/history', (route) =>
    route.fulfill({
      json: [
        {
          periodStart: new Date(Date.now() - 22 * day).toISOString(),
          periodEnd: nextStart,
          amount: 10000,
          currency: 'XOF',
          planCode: 'STANDARD_MONTHLY',
          status: 'PAID',
        },
      ],
    }),
  );
  await page.route('**/api/organizations/*/subscription/invoice-preview', (route) =>
    route.fulfill({
      json: { periodStart: nextStart, periodEnd: nextEnd, amount: 10000, currency: 'XOF' },
    }),
  );
  await page.goto('/app/abonnement');
  await expect(page.getByText(/Votre abonnement est à jour/)).toBeVisible();
  await expect(page.getByRole('button', { name: /Payer/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Actualiser mon abonnement' })).toBeVisible();
  expect(state.posts).toEqual([]);

  // A historical attempt may still need verification outside the renewal window.
  state.invoice = {
    id: 'invoice',
    periodStart: nextStart,
    periodEnd: nextEnd,
    amount: 10000,
    currency: 'XOF',
    status: 'OPEN',
    canPay: false,
    paymentStatus: 'PENDING',
    attemptInProgress: true,
  };
  await page.getByRole('button', { name: 'Actualiser mon abonnement' }).click();
  await expect(page.getByText('Paiement en attente de confirmation.')).toBeVisible();
  await expect(page.getByRole('button', { name: /Payer/ })).toHaveCount(0);
  const verify = page.getByRole('button', { name: 'Vérifier le paiement' });
  await expect(verify).toBeEnabled();
  const refreshed = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname.endsWith('/subscription/invoices') &&
      response.ok(),
  );
  await verify.click();
  await refreshed;
  await expect(verify).toBeEnabled();
  expect(state.posts).toEqual([]);
});
test('reprise après expiration affiche un nouveau mois courant', async ({ page }) => {
  const state = await billing(page, 'PAST_DUE');
  await page.goto('/app/abonnement');
  await expect(page.getByText('Votre abonnement a expiré', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Payer avec Bictorys' }).click();
  await expect(page.getByText('Paiement en attente de confirmation.')).toBeVisible();
  expect(Math.abs(Date.parse(String(state.invoice?.['periodStart'])) - Date.now())).toBeLessThan(
    60000,
  );
});
test('une réponse initiation perdue ne crée pas une deuxième facture', async ({ page }) => {
  const state = await billing(page);
  state.lose = true;
  await page.goto('/app/abonnement');
  await page.getByRole('button', { name: 'Payer maintenant' }).click();
  await expect(page.getByText('Paiement en attente de confirmation.')).toBeVisible();
  await page.getByRole('button', { name: 'Vérifier le paiement' }).click();
  expect(state.posts).toHaveLength(2);
});
test('une anomalie de paiement affiche la review et interdit une nouvelle initiation', async ({
  page,
}) => {
  const state = await billing(page);
  state.invoice = {
    id: 'invoice',
    status: 'OPEN',
    periodEnd: '2099-12-01T00:00:00Z',
    paymentStatus: 'REVIEW_REQUIRED',
    canPay: false,
  };
  await page.goto('/app/abonnement');
  await expect(page.getByText('Paiement à vérifier.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Payer avec Bictorys' })).toHaveCount(0);
  expect(state.posts).toEqual([]);
});
test('MANAGER consulte sans action financière', async ({ page }) => {
  await billing(page, 'ACTIVE', false);
  await page.goto('/app/abonnement');
  await expect(page.getByText('Votre période payée se termine', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: /Payer|Enregistrer les coordonnées/ })).toHaveCount(
    0,
  );
});
for (const theme of ['light', 'dark'])
  test('abonnement mobile 360 clavier ' + theme, async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await billing(page);
    await page.addInitScript((theme) => localStorage.setItem('groupia.theme', theme), theme);
    await page.goto('/app/abonnement');
    const pay = page.getByRole('button', { name: 'Payer maintenant' });
    await expect(pay).toBeVisible();
    await noOverflow(page);
    await pay.focus();
    await expect(pay).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByText('Paiement en attente de confirmation.')).toBeVisible();
    await noOverflow(page);
  });
test('VIEWER ne reçoit aucune donnée abonnement', async ({ page }) => {
  await session(page);
  await mockApi(page);
  await page.route('**/api/organizations/*/subscription**', (route) =>
    route.fulfill({ status: 403, json: { code: 'FORBIDDEN' } }),
  );
  await page.goto('/app/abonnement');
  await expect(page.getByRole('heading', { name: 'Abonnement', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /Payer/ })).toHaveCount(0);
  await expect(page.getByText('Coordonnées de facturation')).toHaveCount(0);
});
test('changement de boutique efface les coordonnées précédentes', async ({ page }) => {
  await billing(page);
  const other = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  await page.route(/\/api\/organizations(?:\?.*)?$/, (route) =>
    route.fulfill({ json: [shop, { ...shop, id: other, name: 'Seconde boutique' }] }),
  );
  await page.goto('/app/abonnement');
  await expect(page.getByRole('textbox', { name: 'Nom du titulaire' })).toHaveValue('Titulaire');
  await page.route('**/api/organizations/' + other + '/subscription**', (route) =>
    route.fulfill({ status: 403, json: { code: 'FORBIDDEN' } }),
  );
  await page.getByRole('main').getByRole('link', { name: 'Ma boutique', exact: true }).click();
  await page.getByLabel('Changer de boutique').selectOption(other);
  await expect(page).toHaveURL(/app\/accueil/);
  await page.goto('/app/abonnement');
  await expect(page.getByRole('textbox', { name: 'Nom du titulaire' })).toHaveCount(0);
  expect(shopId).not.toBe(other);
});

test('SasPay abonnement hébergé : frais annoncés, reprise après perte de réponse et preuve serveur', async ({
  page,
}) => {
  const state = await billing(page, 'PAST_DUE');
  state.providers = ['SASPAY'];
  state.lose = true;
  await page.goto('/app/abonnement');
  await expect(page.getByText(/Des frais supplémentaires peuvent être appliqués/)).toBeVisible();
  const pay = page.getByRole('button', { name: "Payer l'abonnement avec SasPay" });
  await expect(pay).toBeEnabled();
  expect(state.posts.filter((p) => p.path.endsWith('/payment'))).toHaveLength(0);
  await pay.click();
  await expect(page.getByRole('link', { name: 'Ouvrir le paiement sécurisé' })).toHaveAttribute(
    'href',
    'https://pay.saspay.me/fixture',
  );
  await page.reload();
  await expect(page.getByRole('link', { name: 'Ouvrir le paiement sécurisé' })).toBeVisible();
  await expect(page.getByText('Montant accepté', { exact: false })).toHaveCount(0);
  const payments = state.posts.filter((p) => p.path.endsWith('/payment'));
  expect(payments).toHaveLength(1);
  expect(payments[0].body).toEqual({ provider: 'SASPAY' });
  await expect(page.getByText('À renouveler', { exact: true })).toBeVisible();
  state.invoice = {
    ...state.invoice,
    status: 'PAID',
    paymentStatus: 'SETTLED',
    attemptInProgress: false,
  };
  state.status = 'ACTIVE';
  await page.getByRole('button', { name: 'Vérifier le paiement' }).click();
  await expect(page.getByText('Actif', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Ouvrir le paiement sécurisé' })).toHaveCount(0);
  expect(state.posts.filter((p) => p.path.endsWith('/payment'))).toHaveLength(1);
  await noOverflow(page);
});

test('SasPay abonnement sans URL reste en attente et ne suppose pas une confirmation', async ({ page }) => {
  const state = await billing(page);
  state.providers = ['SASPAY'];
  state.push = true;
  await page.goto('/app/abonnement');
  await page.getByRole('button', { name: "Payer l'abonnement avec SasPay" }).click();
  await expect(page.getByText('Paiement en attente de confirmation.')).toBeVisible();
  await expect(page.getByText('Confirmez la demande sur votre téléphone', { exact: false }))
    .toHaveCount(0);
  expect(state.posts.filter((p) => p.path.endsWith('/payment'))).toHaveLength(1);
  await expect(page.getByRole('link', { name: 'Ouvrir le paiement sécurisé' })).toHaveCount(0);
  await expect(page.getByText('Essai gratuit', { exact: true })).toBeVisible();
});
