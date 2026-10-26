import { test, expect, Page } from '@playwright/test';
import { mockApi, session, shopId, groupId, publicId, noOverflow } from './api-mock';
import type { OrderShippingRow, ShippingExplanation } from '../src/app/api/generated/models';
const cases = [
  {
    name: 'poids réel',
    method: 'PER_KG',
    fields: { actualWeightKg: 2.8 },
    rate: 3500,
    amount: 9800,
    quantity: 2.8,
    unit: 'KG',
    calc: 'ACTUAL_WEIGHT',
  },
  {
    name: 'poids facturable',
    method: 'PER_KG',
    fields: { chargeableWeightKg: 8.4 },
    rate: 3500,
    amount: 29400,
    quantity: 8.4,
    unit: 'KG',
    calc: 'COMMUNICATED_CHARGEABLE_WEIGHT',
  },
  {
    name: 'volumétrique',
    method: 'PER_KG',
    fields: { actualWeightKg: 2.4, lengthCm: 60, widthCm: 35, heightCm: 20 },
    divisor: 5000,
    rate: 3500,
    amount: 29400,
    quantity: 8.4,
    unit: 'KG',
    calc: 'MAX_ACTUAL_VOLUMETRIC',
  },
  {
    name: 'CBM direct',
    method: 'PER_CBM',
    fields: { volumeCbm: 0.165 },
    rate: 100000,
    amount: 16500,
    quantity: 0.165,
    unit: 'CBM',
    calc: 'COMMUNICATED_VOLUME',
  },
  {
    name: 'CBM dimensions',
    method: 'PER_CBM',
    fields: { lengthCm: 100, widthCm: 55, heightCm: 30 },
    rate: 100000,
    amount: 16500,
    quantity: 0.165,
    unit: 'CBM',
    calc: 'DIMENSION_VOLUME',
  },
  {
    name: 'articles',
    method: 'PER_ITEM',
    fields: {},
    rate: 1000,
    amount: 4000,
    quantity: 4,
    unit: 'ITEM',
    calc: 'ITEM_COUNT',
  },
  {
    name: 'forfait',
    method: 'FIXED',
    fields: {},
    rate: 2500,
    amount: 2500,
    quantity: 1,
    unit: 'ORDER',
    calc: 'FIXED',
  },
  {
    name: 'montant communiqué',
    method: 'PER_KG',
    fields: {},
    rate: 3500,
    amount: 12500,
    quoted: true,
  },
] as const;
async function setup(page: Page, c: (typeof cases)[number]) {
  await session(page);
  await mockApi(page, { status: 'SUPPLIER_ORDERED' });
  const row: OrderShippingRow = {
    orderId: 'order-1',
    orderNumber: 'GRP-SHIPPING',
    customerName: 'Awa Diop',
    assessment: {
      version: 0,
      status: 'PENDING',
      snapshot: {
        organizationId: shopId,
        campaignId: groupId,
        orderId: 'order-1',
        totalItems: 4,
        estimatedWeightKg: 1.2,
        estimatedVolumeCbm: 0.15,
        estimatedShippingAmount: 4200,
        terms: {
          transportMode: 'AIR',
          shippingPricingMethod: c.method,
          shippingRate: c.rate,
          shippingCurrency: 'XOF',
          volumetricDivisor: 'divisor' in c ? c.divisor : undefined,
        },
      },
      measurements: [],
      calculations: [],
    },
  };
  const calls: { action: string; body: any }[] = [];
  let current: ShippingExplanation | undefined;
  await page.addInitScript(
    (p) =>
      sessionStorage.setItem(
        'groupia.public-order',
        JSON.stringify({
          orderNumber: 'GRP-SHIPPING',
          trackingToken: 'private-shipping-token',
          publicIdentifier: p,
          shippingLater: true,
        }),
      ),
    publicId,
  );
  await page.route('**/grouping-campaigns/*/shipment', (r) =>
    r.fulfill({
      json: {
        campaign: { id: groupId, organizationId: shopId, status: 'SUPPLIER_ORDERED', version: 7 },
        shipment: null,
      },
    }),
  );
  await page.route('**/shipping-assessments', (r) => r.fulfill({ json: [row] }));
  await page.route(
    /\/orders\/order-1\/shipping-(measurements|preview|finalizations)$/,
    async (r) => {
      const action = new URL(r.request().url()).pathname.split('/').pop()!,
        body = r.request().postDataJSON();
      calls.push({ action, body });
      expect(r.request().headers()['authorization']).toBe('Bearer e2e-only-token');
      expect(body.actorId).toBeUndefined();
      expect(body.organizationId).toBeUndefined();
      expect(body.version).toBe(row.assessment!.version);
      if (action === 'shipping-measurements') {
        const id = 'm-' + (row.assessment!.version! + 1);
        row.assessment!.measurements!.push({
          id,
          information: body.information,
          actorId: 'PRIVATE-ACTOR',
        });
        row.assessment!.currentMeasurementId = id;
        row.assessment!.version!++;
        return r.fulfill({ json: row.assessment });
      }
      const quoted = body.origin === 'PROVIDER_QUOTED';
      const explanation: ShippingExplanation = quoted
        ? {
            origin: 'PROVIDER_QUOTED',
            amount: body.quotedAmount,
            currency: 'XOF',
            source: body.source,
            transportMode: 'AIR',
            method: 'PROVIDER_QUOTED',
          }
        : {
            origin: 'CALCULATED',
            amount: c.amount,
            currency: 'XOF',
            source: 'FORWARDER',
            transportMode: 'AIR',
            method: 'calc' in c ? c.calc : undefined,
            rate: c.rate,
            billableQuantity: 'quantity' in c ? c.quantity : undefined,
            billableUnit: 'unit' in c ? c.unit : undefined,
            ...c.fields,
            ...('divisor' in c ? { volumetricDivisor: c.divisor, volumetricWeightKg: 8.4 } : {}),
          };
      if (action === 'shipping-preview') return r.fulfill({ json: explanation });
      const id = 'c-' + (row.assessment!.calculations!.length + 1);
      row.assessment!.calculations!.push({
        id,
        revision: row.assessment!.calculations!.length + 1,
        measurementId: body.measurementId,
        explanation,
        actorId: 'PRIVATE-ACTOR',
        note: body.note,
        correctionReason: body.correctionReason,
        recordedAt: '2026-10-01T08:00:00Z',
      });
      row.assessment!.currentFinalCalculationId = id;
      row.assessment!.version!++;
      row.assessment!.status = 'FINALIZED';
      current = explanation;
      return r.fulfill({ json: row.assessment });
    },
  );
  await page.route('**/api/public/orders/track', (r) =>
    r.fulfill({
      json: {
        orderNumber: 'GRP-SHIPPING',
        status: 'CONFIRMED',
        fulfillmentStatus: 'NOT_READY',
        goodsSubtotal: 5000,
        initialAmountDue: 5300,
        initialShippingChargeAmount: 0,
        currency: 'XOF',
        items: [],
        groupingProgress: { status: 'SUPPLIER_ORDERED' },
        shippingFinal: current,
      },
    }),
  );
  return { calls, row };
}
for (const [i, c] of cases.entries()) {
  test('transport ' + c.name + ' / 360px ' + (i % 2 ? 'dark' : 'light'), async ({ page }) => {
    const state = await setup(page, c);
    const theme = i % 2 ? 'dark' : 'light';
    await page.setViewportSize({ width: 360, height: 900 });
    await page.addInitScript((t) => localStorage.setItem('groupia.theme', t), theme);
    await page.goto('/app/groupages/' + groupId + '?tab=transport');
    await page.getByRole('button', { name: 'Gérer les frais par commande' }).click();
    const batch = page.locator('g-shipping-batch'),
      editor = batch.locator('g-shipping-order-editor');
    await expect(editor).toContainText('GRP-SHIPPING');
    await expect(editor).toContainText('Awa Diop');
    await editor.getByRole('button', { name: 'Renseigner le transport' }).click();
    if ('quoted' in c) {
      await editor.locator('select[name="origin"]').selectOption('PROVIDER_QUOTED');
      await editor.locator('input[name="amount"]').fill('12500');
    } else if (Object.keys(c.fields).length) {
      for (const [key, value] of Object.entries(c.fields))
        await editor
          .getByRole('spinbutton', {
            name: (
              {
                actualWeightKg: 'Poids réel (kg)',
                chargeableWeightKg: 'Poids facturable communiqué (kg)',
                volumeCbm: 'Volume communiqué (m³)',
                lengthCm: 'Longueur (cm)',
                widthCm: 'Largeur (cm)',
                heightCm: 'Hauteur (cm)',
              } as Record<string, string>
            )[key],
            exact: true,
          })
          .fill(String(value));
      await expect(editor.getByRole('button', { name: 'Voir l’aperçu' })).toBeDisabled();
    }
    await editor.locator('g-logistics-source select').selectOption('FORWARDER');
    await editor.locator('textarea[name="note"]').fill('PRIVATE-NOTE');
    if (Object.keys(c.fields).length)
      await editor.getByRole('button', { name: 'Enregistrer la mesure' }).click();
    await editor.getByRole('button', { name: 'Voir l’aperçu' }).click();
    await expect(editor.getByRole('heading', { name: 'Aperçu du transport' })).toBeVisible();
    expect(state.calls.filter((x) => x.action === 'shipping-finalizations')).toHaveLength(0);
    await noOverflow(page);
    await editor.getByRole('button', { name: 'Finaliser le transport' }).click();
    await expect(editor.getByRole('button', { name: 'Corriger le transport' })).toBeVisible();
    await expect(batch).toContainText('1 finalisées');
    await expect(batch).toContainText('0 restantes');
    if (i === 0) {
      await expect(editor).toContainText('Écart important');
    }
    if (i === 7) {
      await editor.getByRole('button', { name: 'Corriger le transport' }).click();
      await editor.locator('select[name="origin"]').selectOption('PROVIDER_QUOTED');
      await editor.locator('input[name="amount"]').fill('12000');
      await editor.locator('input[name="reason"]').fill('Facture corrigée');
      await editor.getByRole('button', { name: 'Voir l’aperçu' }).click();
      await editor.getByRole('button', { name: 'Finaliser le transport' }).click();
      await expect(editor).toContainText('Corrigé');
      expect(state.row.assessment!.calculations).toHaveLength(2);
    }
    await page.screenshot({
      path: 'artifacts/shipping-manager-' + i + '-' + theme + '.png',
      fullPage: true,
    });
    await page.goto('/public/order');
    const final = page.getByRole('region', { name: 'Transport final' });
    await expect(final).toBeVisible();
    await expect(
      page.getByText('Les frais de transport définitifs seront calculés après réception/pesée.'),
    ).toHaveCount(0);
    await expect(final).toContainText('Prestataire logistique');
    await expect(final).not.toContainText('PRIVATE');
    if ('quoted' in c) {
      await expect(final).toContainText('Montant communiqué');
      await expect(final).not.toContainText('Calcul :');
    } else await expect(final).toContainText('Calculé par Groupia');
    await noOverflow(page);
    await page.screenshot({
      path: 'artifacts/shipping-client-' + i + '-' + theme + '.png',
      fullPage: true,
    });
  });
}
