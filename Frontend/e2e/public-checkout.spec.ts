import { test, expect, Page } from '@playwright/test';
import { publicId, offerId, variantId, noOverflow } from './api-mock';
const base = '/public/groupages/' + publicId;
async function publicApi(
  page: Page,
  options: {
    networkOnce?: boolean;
    replay?: boolean;
    reconciliation?: boolean;
    nullUrl?: boolean;
    status?: string;
  } = {},
) {
  const state = {
    price: 5000,
    revision: 3,
    orderable: true,
    confirmed: false,
    checkouts: [] as any[],
    payments: [] as string[],
    tracks: 0,
  };
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname;
    expect(req.headers()['authorization']).toBeUndefined();
    expect(req.url()).not.toContain('private-tracking-test');
    const reply = (json: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(json) });
    if (path === '/api/auth/refresh') return reply({ code: 'AUTH_REFRESH_INVALID' }, 401);
    if (path === `/api/public/groupings/${publicId}`) {
      return reply({
        publicIdentifier: publicId,
        title: 'La sélection du Soleil',
        description: 'Des essentiels à partager.',
        orderable: state.orderable,
        status: 'OPEN',
        currency: 'XOF',
        shippingTerms: { shippingCollectionMode: 'AFTER_FINAL_ASSESSMENT' },
        offers: [
          {
            offerId,
            productVariantId: variantId,
            unitPrice: state.price,
            priceRevision: state.revision,
            currency: 'XOF',
            active: true,
            catalog: {
              productName: 'Chemise en lin',
              attributes: { color: 'Noir', size: 'M' },
              usable: true,
              saleUnit: 'UNIT',
            },
          },
        ],
      });
    }
    const receipt = {
      orderNumber: 'GRP-000001',
      status: state.confirmed ? 'CONFIRMED' : (options.status ?? 'PENDING_PAYMENT'),
      fulfillmentStatus: 'NOT_READY',
      goodsSubtotal: state.price,
      platformFeeAmount: 300,
      initialShippingChargeAmount: 0,
      initialAmountDue: state.price + 300,
      currency: 'XOF',
      paymentDeadline: new Date(Date.now() + 900000).toISOString(),
      placedAt: new Date().toISOString(),
      items: [
        {
          lineNumber: 1,
          productNameSnapshot: 'Chemise en lin',
          variantAttributesSnapshot: { color: 'Noir', size: 'M' },
          quantity: 1,
          unitPriceSnapshot: state.price,
          lineTotal: state.price,
        },
      ],
    };
    if (path.endsWith('/checkout')) {
      const key = req.headers()['idempotency-key'];
      expect(key).toMatch(/^[0-9a-f-]{36}$/);
      state.checkouts.push({ key, body: req.postDataJSON() });
      if (options.networkOnce && state.checkouts.length === 1) return route.abort('timedout');
      if (options.replay) return reply({ orderNumber: 'GRP-000001', replayed: true });
      return reply({ ...receipt, trackingToken: 'private-tracking-test' }, 201);
    }
    expect(req.headers()['x-order-tracking-token']).toBe('private-tracking-test');
    if (path.endsWith('/payments')) {
      expect(req.postDataJSON()).toEqual({ provider: 'bictorys' });
      state.payments.push(req.headers()['idempotency-key']);
      return reply({
        id: 'internal-not-visible',
        status: 'PENDING',
        expectedAmount: 5300,
        currency: 'XOF',
        reconciliationRequired: !!options.reconciliation,
        checkoutUrl: options.nullUrl ? null : 'https://checkout.example.test/pay',
      });
    }
    if (path === '/api/public/orders/track') {
      state.tracks++;
      return reply(receipt);
    }
    throw new Error('Unexpected public API path');
  });
  // A click can open only a test stub; no real provider is contacted.
  await page.route('https://checkout.example.test/**', (r) =>
    r.fulfill({ body: 'Paiement de test' }),
  );
  return state;
}
async function select(page: Page) {
  await page.goto(base);
  await page.getByRole('button', { name: 'Ajouter à ma sélection' }).click();
  await page.getByRole('link', { name: 'Voir mon panier' }).click();
  await expect(page.getByRole('heading', { name: 'Mon panier' })).toBeVisible();
}
async function contact(page: Page) {
  await page.getByRole('link', { name: 'Continuer vers mes coordonnées' }).click();
  await page.getByLabel('Prénom *').fill('Awa');
  await page.getByLabel('Nom *', { exact: true }).fill('Diop');
  await page.getByLabel('Téléphone *').fill('+221771234567');
  await page.getByLabel('Région *').selectOption('Dakar');
  await page.getByLabel('Ville / département *').selectOption('Dakar');
  await page.getByLabel('Commune *').selectOption('Biscuiterie');
  await page.getByLabel('Adresse détaillée / point de repère *').fill('Rue 12, près de la mairie');
}
async function checkout(page: Page) {
  await page.getByRole('button', { name: 'Continuer vers le paiement' }).click();
  await expect(page).toHaveURL('/public/order');
}
for (const [width, theme] of [
  [360, 'light'],
  [360, 'dark'],
  [768, 'light'],
  [1440, 'dark'],
] as const) {
  test('parcours cliente complet ' + width + 'px ' + theme, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    await page.emulateMedia({ colorScheme: theme });
    const state = await publicApi(page);
    await select(page);
    await noOverflow(page);
    await contact(page);
    await noOverflow(page);
    await checkout(page);
    await expect(page.getByRole('heading', { name: 'Paiement en attente' })).toBeVisible();
    expect(state.checkouts[0].body.lines).toEqual([
      { offerId, quantity: 1, expectedUnitPrice: 5000, expectedPriceRevision: 3 },
    ]);
    await expect(page.getByText('5 300', { exact: false })).toBeVisible();
    expect(
      await page.evaluate(
        () => JSON.parse(sessionStorage.getItem('groupia.public-order')!).trackingToken,
      ),
    ).toBe('private-tracking-test');
    await page.getByRole('button', { name: 'Préparer mon paiement sécurisé' }).click();
    await expect(
      page.getByRole('link', { name: 'Payer avec Wave / Orange Money' }),
    ).toHaveAttribute('target', '_blank');
    await expect(page.getByText('Votre commande est confirmée.', { exact: false })).toHaveCount(0);
    await noOverflow(page);
    await page.screenshot({
      path: 'artifacts/public-payment-' + width + '-' + theme + '.png',
      fullPage: true,
    });
    state.confirmed = true;
    await page.getByRole('button', { name: 'Actualiser le suivi' }).click();
    await expect(page.getByRole('heading', { name: 'Paiement confirmé' })).toBeVisible();
    await expect(page.locator('body')).not.toContainText('private-tracking-test');
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Paiement confirmé' })).toBeVisible();
    await noOverflow(page);
    await page.getByRole('link', { name: 'Revenir au groupage' }).click();
    await expect(page.getByRole('heading', { name: 'Ma sélection (0)' })).toBeVisible();
  });
}

test('adresse Sénégal : les listes dépendantes sont requises et soumises avec la commande', async ({ page }) => {
  const state = await publicApi(page);
  await select(page);
  await page.getByRole('link', { name: 'Continuer vers mes coordonnées' }).click();
  await page.getByLabel('Prénom *').fill('Awa');
  await page.getByLabel('Nom *', { exact: true }).fill('Diop');
  await page.getByLabel('Téléphone *').fill('+221771234567');
  await expect(page.getByRole('button', { name: 'Continuer vers le paiement' })).toBeDisabled();

  await page.getByLabel('Région *').selectOption('Dakar');
  await page.getByLabel('Ville / département *').selectOption('Dakar');
  await page.getByLabel('Commune *').selectOption('Biscuiterie');
  await page.getByLabel('Adresse détaillée / point de repère *').fill('Rue 12, près de la mairie');
  await expect(page.getByRole('button', { name: 'Continuer vers le paiement' })).toBeEnabled();

  await checkout(page);
  expect(state.checkouts[0].body.contact.deliveryAddress).toEqual({
    country: 'SN',
    region: 'Dakar',
    city: 'Dakar',
    commune: 'Biscuiterie',
    details: 'Rue 12, près de la mairie',
  });
  await noOverflow(page);
});

test('adresse Sénégal : changer de région réinitialise la commune et autorise la saisie libre', async ({ page }) => {
  await publicApi(page);
  await select(page);
  await page.getByRole('link', { name: 'Continuer vers mes coordonnées' }).click();
  await page.getByLabel('Région *').selectOption('Dakar');
  await page.getByLabel('Ville / département *').selectOption('Dakar');
  await page.getByLabel('Commune *').selectOption('Biscuiterie');
  await page.getByLabel('Région *').selectOption('Thiès');
  await expect(page.getByLabel('Ville / département *')).toHaveValue('');
  await expect(page.getByLabel('Commune *')).toHaveValue('');

  await page.getByLabel('Ville / département *').selectOption('__OTHER__');
  await page.getByLabel('Préciser votre ville *').fill('Ma localité');
  await page.getByLabel('Commune *').selectOption('__OTHER__');
  await page.getByLabel('Préciser votre commune *').fill('Ma commune');
  await expect(page.getByLabel('Préciser votre ville *')).toHaveValue('Ma localité');
  await noOverflow(page);
});

test('double-clic checkout ne crée qu’une tentative', async ({ page }) => {
  const state = await publicApi(page);
  await select(page);
  await contact(page);
  await page
    .getByRole('button', { name: 'Continuer vers le paiement' })
    .evaluate((button: HTMLButtonElement) => {
      button.click();
      button.click();
    });
  await expect(page).toHaveURL('/public/order');
  expect(state.checkouts).toHaveLength(1);
});
test('réseau perdu puis retry conserve Idempotency-Key', async ({ page }) => {
  const state = await publicApi(page, { networkOnce: true });
  await select(page);
  await contact(page);
  await page.getByRole('button', { name: 'Continuer vers le paiement' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await checkout(page);
  expect(state.checkouts).toHaveLength(2);
  expect(state.checkouts[1].key).toBe(state.checkouts[0].key);
});
test('nouveau prix nécessite accord explicite', async ({ page }) => {
  const state = await publicApi(page);
  await page.goto(base);
  await page.getByRole('button', { name: 'Ajouter à ma sélection' }).click();
  state.price = 6500;
  state.revision = 4;
  await page.getByRole('link', { name: 'Voir mon panier' }).click();
  await expect(page.getByText('Le prix a changé.', { exact: false })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Continuer vers mes coordonnées' })).toHaveAttribute(
    'aria-disabled',
    'true',
  );
  await page.getByRole('button', { name: 'Accepter le nouveau prix' }).click();
  await contact(page);
  await checkout(page);
  expect(state.checkouts[0].body.lines[0].expectedUnitPrice).toBe(6500);
});
test('fermeture entre coordonnées et checkout interdit le POST', async ({ page }) => {
  const state = await publicApi(page);
  await select(page);
  await contact(page);
  state.orderable = false;
  await page.getByRole('button', { name: 'Continuer vers le paiement' }).click();
  await expect(page.getByRole('alert')).toContainText('fermé');
  expect(state.checkouts).toHaveLength(0);
});
for (const mode of ['reconciliation', 'nullUrl'] as const) {
  test('paiement ' + mode + ' ne relance pas de tentative', async ({ page }) => {
    const state = await publicApi(page, { [mode]: true });
    await select(page);
    await contact(page);
    await checkout(page);
    await page.getByRole('button', { name: 'Préparer mon paiement sécurisé' }).click();
    await expect(
      page.getByText(
        mode === 'reconciliation'
          ? 'Nous vérifions votre paiement. Ne recommencez pas le paiement.'
          : 'Le lien de paiement n’est pas disponible.',
        { exact: false },
      ),
    ).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Paiement en attente' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Préparer mon paiement sécurisé' })).toHaveCount(
      0,
    );
    expect(state.payments).toHaveLength(1);
  });
}
test('paiement expiré ne propose plus de paiement', async ({ page }) => {
  await publicApi(page, { status: 'EXPIRED' });
  await select(page);
  await contact(page);
  await checkout(page);
  await expect(page.getByRole('heading', { name: 'Délai de paiement expiré' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Préparer mon paiement sécurisé' })).toHaveCount(0);
});
test('rejeu sans token : explique la perte du suivi sans nouveau checkout', async ({ page }) => {
  const state = await publicApi(page, { replay: true });
  await select(page);
  await contact(page);
  await checkout(page);
  await expect(
    page.getByText('Le suivi de cette commande n’est plus disponible sur cet appareil.'),
  ).toBeVisible();
  expect(state.tracks).toBe(0);
  expect(state.payments).toHaveLength(0);
});
test('session absente : aucun appel privé', async ({ page }) => {
  const state = await publicApi(page);
  await page.goto('/public/order');
  await expect(
    page.getByText('Le suivi de cette commande n’est plus disponible sur cet appareil.'),
  ).toBeVisible();
  expect(state.tracks).toBe(0);
});

test('validation native du téléphone compatible avec le navigateur', async ({ page }) => {
  await publicApi(page);
  await select(page);
  await contact(page);
  const phone = page.getByLabel('Téléphone *');
  expect(
    await phone.evaluate((input: HTMLInputElement) => {
      const pattern = new RegExp(input.pattern, 'v');
      return pattern.test('+221 (77) 123-45-67');
    }),
  ).toBe(true);
  await phone.fill('+221 (77) 123-45-67');
  await expect(page.getByRole('button', { name: 'Continuer vers le paiement' })).toBeEnabled();
  await checkout(page);
});
