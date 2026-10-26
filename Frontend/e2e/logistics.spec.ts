import { test, expect, Page } from '@playwright/test';
import { mockApi, session, shopId, groupId, publicId, noOverflow } from './api-mock';
import type { ShipmentView } from '../src/app/api/generated/models';

async function logistics(page: Page) {
  await session(page);
  const api = await mockApi(page, { status: 'SUPPLIER_ORDERED' });
  await page.addInitScript((publicIdentifier) => {
    sessionStorage.setItem(
      'groupia.public-order',
      JSON.stringify({
        orderNumber: 'GRP-LOGISTICS',
        trackingToken: 'private-logistics-token',
        publicIdentifier,
        shippingLater: true,
      }),
    );
  }, publicId);
  const state = {
    shipment: null as ShipmentView | null,
    posts: [] as { action: string; body: any }[],
    failRead: false,
    conflict: false,
    lostResponse: false,
    pause: false,
    release: () => {},
  };
  const response = () => ({
    campaign: {
      id: groupId,
      organizationId: shopId,
      status: api.groups[0].status,
      version: api.groups[0].version,
    },
    shipment: state.shipment,
  });
  await page.route(
    /\/grouping-campaigns\/[^/]+\/(shipment(?:\/(?:eta|facts))?|ship|arrive)$/,
    async (route) => {
      const req = route.request(),
        action = new URL(req.url()).pathname.split('/').pop()!;
      expect(req.headers()['authorization']).toBe('Bearer e2e-only-token');
      if (action === 'shipment' && req.method() === 'GET')
        return route.fulfill({
          status: state.failRead ? 500 : 200,
          json: state.failRead ? { detail: 'SQL privé' } : response(),
        });
      const body = req.postDataJSON();
      state.posts.push({ action, body });
      if (state.pause) await new Promise<void>((resolve) => (state.release = resolve));
      if (state.conflict) {
        state.conflict = false;
        api.groups[0].version = 21;
        return route.fulfill({ status: 409, json: { code: 'GROUPING_CAMPAIGN_VERSION_CONFLICT' } });
      }
      expect(body.version).toBe(api.groups[0].version);
      if (action === 'ship') {
        expect(body.transportMode).toBeUndefined();
        api.groups[0].status = 'IN_TRANSIT';
        state.shipment = {
          ...state.shipment,
          ...body,
          id: 'shipment',
          transportMode: 'SEA',
          departureConfirmedAt: '2026-10-02T09:00:00Z',
        };
      } else if (action === 'arrive') {
        expect(body.source).toBe('UNKNOWN');
        api.groups[0].status = 'ARRIVED';
        state.shipment = {
          ...state.shipment,
          arrivedAt: body.arrivedAt,
          arrivalConfirmedAt: '2026-10-02T10:00:00Z',
        };
      }
      if (!['ship', 'arrive'].includes(action)) {
        const old = state.shipment;
        const type =
          action === 'shipment' ? 'DOSSIER_UPDATED' : action === 'eta' ? 'ETA_UPDATED' : body.type;
        const fact = {
          id: 'fact-' + state.posts.length,
          type,
          source: body.source ?? 'UNKNOWN',
          recordedAt: '2026-10-02T08:00:00Z',
          note: body.note,
          providerName: body.providerName,
          previousExpectedArrivalAt: old?.expectedArrivalAt,
          expectedArrivalAt: body.expectedArrivalAt,
        };
        state.shipment = {
          ...old,
          id: 'shipment',
          transportMode: 'SEA',
          ...(action === 'facts' ? {} : body),
          timeline: [...(old?.timeline ?? []), fact],
        };
      }
      api.groups[0].version = body.version + 5; // UI must use the returned value, not increment its own.
      return route.fulfill({
        status: state.lostResponse ? 500 : 200,
        json: state.lostResponse ? {} : response(),
      });
    },
  );
  await page.route('**/api/public/orders/track', async (route) => {
    expect(route.request().headers()['authorization']).toBeUndefined();
    expect(route.request().headers()['x-order-tracking-token']).toBe('private-logistics-token');
    await route.fulfill({
      json: {
        orderNumber: 'GRP-LOGISTICS',
        status: 'CONFIRMED',
        fulfillmentStatus: 'NOT_READY',
        goodsSubtotal: 5000,
        platformFeeAmount: 300,
        initialAmountDue: 5300,
        initialShippingChargeAmount: 0,
        currency: 'XOF',
        items: [],
        groupingProgress: {
          status: api.groups[0].status,
          departedAt: state.shipment?.departedAt,
          expectedArrivalAt: state.shipment?.expectedArrivalAt,
          arrivedAt: state.shipment?.arrivedAt,
        },
      },
    });
  });
  return state;
}
async function open(page: Page) {
  await page.goto('/app/groupages/' + groupId + '?tab=transport');
  await expect(page.getByRole('heading', { name: 'Expédition', exact: true })).toBeVisible();
}
async function departure(page: Page) {
  await page.getByRole('button', { name: 'Marquer comme en transport' }).click();
  await page
    .getByLabel('Date de départ communiquée (facultatif)', { exact: true })
    .fill('2026-09-26T09:00');
  await page.getByLabel('Arrivée estimée (facultatif)').fill('2026-10-01T09:00');
  await page
    .getByLabel('Référence de suivi (facultatif)', { exact: true })
    .fill('REF-FOURNISSEUR-PRIVEE');
  await page
    .getByLabel('Note privée (facultatif)', { exact: true })
    .fill('Note à conserver uniquement côté boutique');
}
for (const [width, theme] of [
  [360, 'light'],
  [360, 'dark'],
  [1440, 'light'],
  [1440, 'dark'],
] as const) {
  test('logistique gérante et cliente ' + width + 'px ' + theme, async ({ page }) => {
    const state = await logistics(page);
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript((theme) => localStorage.setItem('groupia.theme', theme), theme);
    await open(page);
    await departure(page);
    await expect(page.getByText('Transport : Bateau').first()).toBeVisible();
    await noOverflow(page);
    await page.getByRole('button', { name: 'Confirmer le départ' }).click();
    await expect(page.getByRole('button', { name: 'Marquer comme arrivée' })).toBeVisible();
    expect(state.posts[0].body.version).toBe(7);
    await page.reload();
    await page.getByText('Informations et références').click();
    await expect(page.getByText('REF-FOURNISSEUR-PRIVEE', { exact: true })).toBeVisible();
    await page.goto('/public/order');
    await expect(page.locator('g-order-timeline [aria-current="step"]')).toContainText(
      'En transport',
    );
    await expect(page.locator('body')).not.toContainText('REF-FOURNISSEUR-PRIVEE');
    await expect(page.locator('body')).not.toContainText('Note à conserver');
    await expect(page.locator('body')).not.toContainText('IN_TRANSIT');
    await noOverflow(page);
    await page.evaluate(() => {
      window.scrollTo(0, 0);
      (document.activeElement as HTMLElement)?.blur();
    });
    await page.screenshot({
      path: 'artifacts/logistics-tracking-transit-' + width + '-' + theme + '.png',
      fullPage: true,
    });
    await open(page);
    await page.getByRole('button', { name: 'Marquer comme arrivée' }).click();
    await expect(
      page.getByRole('heading', { name: 'Marchandises arrivées à destination' }),
    ).toBeVisible();
    await page.getByLabel('Date d’arrivée communiquée (facultatif)').fill('2026-09-27T10:00');
    await page.getByRole('button', { name: 'Confirmer l’arrivée' }).click();
    await expect(page.getByText('Marchandise arrivée', { exact: true }).first()).toBeVisible();
    expect(state.posts[1].body.version).toBe(12);
    await page.getByText('Informations et références').click();
    await expect(page.getByText('Date d’arrivée communiquée', { exact: true })).toBeVisible();
    await noOverflow(page);
    await page.evaluate(() => {
      window.scrollTo(0, 0);
      (document.activeElement as HTMLElement)?.blur();
    });
    await page.screenshot({
      path: 'artifacts/logistics-manager-' + width + '-' + theme + '.png',
      fullPage: true,
    });
    await page.goto('/public/order');
    await expect(page.locator('g-order-timeline [aria-current="step"]')).toContainText('Arrivée');
    await expect(page.locator('g-order-timeline li:last-child')).toContainText('En attente');
    await expect(page.locator('body')).not.toContainText('ARRIVED');
    await noOverflow(page);
    await page.evaluate(() => {
      window.scrollTo(0, 0);
      (document.activeElement as HTMLElement)?.blur();
    });
    await page.screenshot({
      path: 'artifacts/logistics-tracking-arrived-' + width + '-' + theme + '.png',
      fullPage: true,
    });
    expect(state.posts).toHaveLength(2);
  });
}
test('départ : double clic protégé pendant la mutation', async ({ page }) => {
  const state = await logistics(page);
  await open(page);
  await departure(page);
  state.pause = true;
  const button = page.getByRole('button', { name: 'Confirmer le départ' });
  await button.click();
  await expect(button).toBeDisabled();
  await button.evaluate((b: HTMLButtonElement) => b.click());
  expect(state.posts).toHaveLength(1);
  state.release();
  await expect(page.getByRole('button', { name: 'Marquer comme arrivée' })).toBeVisible();
});
test('dates invalides ne déclenchent aucune mutation', async ({ page }) => {
  const state = await logistics(page);
  await open(page);
  await departure(page);
  await page.getByLabel('Arrivée estimée (facultatif)').fill('2026-09-25T09:00');
  await page.getByRole('button', { name: 'Confirmer le départ' }).click();
  await expect(page.getByRole('alert')).toContainText('Vérifiez les dates');
  expect(state.posts).toHaveLength(0);
});
test('conflit : actualisation puis nouvelle action avec version serveur', async ({ page }) => {
  const state = await logistics(page);
  await open(page);
  await departure(page);
  state.conflict = true;
  await page.getByRole('button', { name: 'Confirmer le départ' }).click();
  await expect(page.getByRole('alert')).toContainText('Ce groupage a changé');
  await expect(page.getByRole('button', { name: 'Confirmer le départ' })).toBeDisabled();
  await page.getByRole('button', { name: 'Actualiser la logistique' }).click();
  await page.getByRole('button', { name: 'Confirmer le départ' }).click();
  await expect(page.getByRole('button', { name: 'Marquer comme arrivée' })).toBeVisible();
  expect(state.posts.map((p) => p.body.version)).toEqual([7, 21]);
});
test('réponse perdue : récupération par lecture sans second POST', async ({ page }) => {
  const state = await logistics(page);
  await open(page);
  await departure(page);
  state.lostResponse = true;
  await page.getByRole('button', { name: 'Confirmer le départ' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByRole('button', { name: 'Actualiser la logistique' }).click();
  await expect(page.getByRole('button', { name: 'Marquer comme arrivée' })).toBeVisible();
  expect(state.posts).toHaveLength(1);
});
test('lecture en erreur : message sûr et réessai', async ({ page }) => {
  const state = await logistics(page);
  state.failRead = true;
  await open(page);
  await expect(page.getByRole('alert')).toContainText('La logistique n’a pas pu être actualisée');
  await expect(page.locator('body')).not.toContainText('SQL privé');
  state.failRead = false;
  await page.getByRole('button', { name: 'Actualiser la logistique' }).click();
  await expect(page.getByRole('button', { name: 'Marquer comme en transport' })).toBeEnabled();
});

test('dossier facultatif, ETA révisée, information et transitions sans dates à 360px', async ({
  page,
}) => {
  const state = await logistics(page);
  await page.setViewportSize({ width: 360, height: 900 });
  await open(page);
  await page.getByText('Compléter le suivi').click();
  await page.getByRole('button', { name: 'Renseigner le prestataire' }).click();
  await page.getByRole('button', { name: 'Enregistrer', exact: true }).click();
  await page.locator('g-logistics-panel details').filter({has:page.locator('summary').filter({hasText:'Voir l’historique'})}).locator('summary').click();
  await expect(page.getByRole('heading', { name: 'Historique logistique' })).toBeVisible();
  await page.getByRole('button', { name: 'Modifier l’arrivée estimée' }).click();
  await page.getByLabel('Arrivée estimée (facultatif)', { exact: true }).fill('2026-11-01T09:00');
  await page.getByRole('button', { name: 'Enregistrer', exact: true }).click();
  await page.getByRole('button', { name: 'Modifier l’arrivée estimée' }).click();
  await page.getByLabel('Arrivée estimée (facultatif)', { exact: true }).fill('2026-11-03T09:00');
  await page.getByRole('button', { name: 'Enregistrer', exact: true }).click();
  await expect(page.locator('g-logistics-timeline')).toContainText('Estimation précédente');
  await page.getByRole('button', { name: 'Ajouter une information', exact: true }).click();
  await page
    .getByRole('combobox', { name: 'Information', exact: true })
    .selectOption('FORWARDER_RECEIVED');
  await page.getByRole('button', { name: 'Enregistrer', exact: true }).click();
  await expect(page.locator('g-logistics-timeline')).toContainText(
    'Marchandises reçues par le prestataire',
  );
  await page.getByRole('button', { name: 'Marquer comme en transport' }).click();
  await expect(page.getByLabel('Date de départ communiquée (facultatif)')).toHaveValue('');
  await page.getByRole('button', { name: 'Confirmer le départ' }).click();
  await expect(page.getByRole('button', { name: 'Marquer comme arrivée' })).toBeVisible();
  expect(state.posts.find((p) => p.action === 'ship')!.body.departedAt).toBeUndefined();
  await page.getByRole('button', { name: 'Marquer comme arrivée' }).click();
  await page.getByRole('button', { name: 'Confirmer l’arrivée' }).click();
  await expect(page.getByText('Arrivée confirmée. Préparez les retraits dans l’onglet Retraits.')).toBeVisible();
  await expect(page.getByText('Date d’arrivée communiquée', { exact: true })).toHaveCount(0);
  await noOverflow(page);
  await page.screenshot({ path: 'artifacts/logistics-dossier-360.png', fullPage: true });
  await page.goto('/public/order');
  await expect(page.locator('g-order-timeline [aria-current="step"]')).toContainText('Arrivée');
  await expect(page.locator('g-order-timeline li:last-child')).toContainText('En attente');
  await noOverflow(page);
});
