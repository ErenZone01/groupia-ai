import { expect, Page } from '@playwright/test';
import type { CampaignView, OrganizationView, ProductView } from '../src/app/api/generated/models';
export const shopId = '11111111-1111-4111-8111-111111111111';
export const productId = '22222222-2222-4222-8222-222222222222';
export const variantId = '33333333-3333-4333-8333-333333333333';
export const groupId = '44444444-4444-4444-8444-444444444444';
export const publicId = '55555555-5555-4555-8555-555555555555';
export const offerId = '66666666-6666-4666-8666-666666666666';
export const shop: OrganizationView = {
  id: shopId,
  name: 'Boutique du Soleil',
  status: 'ACTIVE',
  defaultCurrency: 'XOF',
};
export const product: ProductView = {
  id: productId,
  organizationId: shopId,
  name: 'Chemise en lin',
  description: 'Une coupe légère et confortable.',
  status: 'ACTIVE',
  version: 3,
  variants: [{ id: variantId, attributes: {}, canonicalVariantKey: 'DEFAULT', active: true }],
};
export const group: CampaignView = {
  id: groupId,
  organizationId: shopId,
  title: 'Les essentiels de la rentrée',
  description: 'Une sélection à partager ensemble.',
  version: 7,
  status: 'DRAFT',
  currency: 'XOF',
  publicIdentifier: publicId,
  shippingTerms: {
    transportMode: 'SEA',
    shippingPricingMethod: 'FIXED',
    shippingCollectionMode: 'AFTER_FINAL_ASSESSMENT',
    shippingRate: 2000,
    shippingCurrency: 'XOF',
    roundingRule: 'HALF_UP',
  },
  offers: [
    {
      id: offerId,
      productVariantId: variantId,
      unitPrice: 5000,
      currency: 'XOF',
      active: true,
      version: 3,
      priceRevision: 1,
    },
  ],
};
export async function session(page: Page) {
  await page.addInitScript(() =>
    sessionStorage.setItem(
      'groupia.session',
      JSON.stringify({ accessToken: 'e2e-only-token', expiresAt: Date.now() + 900000 }),
    ),
  );
}
export async function mockApi(
  page: Page,
  options: {
    empty?: boolean;
    orderable?: boolean;
    status?: string;
    loginFailure?: boolean;
    conflict?: boolean;
    multiple?: boolean;
  } = {},
) {
  const state = {
    shops: options.empty
      ? []
      : structuredClone([
          shop,
          ...(options.multiple
            ? [{ ...shop, id: '77777777-7777-4777-8777-777777777777', name: 'Deuxième boutique' }]
            : []),
        ]),
    products: options.empty ? [] : structuredClone([product]),
    groups: options.empty ? [] : structuredClone([{ ...group, status: options.status ?? 'DRAFT' }]),
    calls: [] as { path: string; method: string; body: any }[],
    publicAuth: [] as (string | undefined)[],
    loginFailure: options.loginFailure ?? false,
    conflict: options.conflict ?? false,
  };
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname,
      method = req.method(),
      body = req.postDataJSON();
    state.calls.push({ path, method, body });
    const reply = (json: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(json) });
    if (path.endsWith('/completion') && method === 'GET')
      return reply({
        eligible: false,
        canComplete: false,
        campaignStatus: state.groups[0]?.status,
        campaignVersion: state.groups[0]?.version,
        totalOrders: 0,
        blockers: [],
      });
    if (path.endsWith('/shipping-assessments') && method === 'GET') return reply([]);
    if (path === '/api/auth/login') {
      expect(req.headers()['authorization']).toBeUndefined();
      if (state.loginFailure)
        return reply({ code: 'INVALID_CREDENTIALS', detail: 'private internal data' }, 401);
      return reply({ accessToken: 'e2e-only-token', tokenType: 'Bearer', expiresIn: 900 });
    }
    if (path === '/api/auth/register') {
      expect(req.headers()['authorization']).toBeUndefined();
      return route.fulfill({ status: 202, body: '' });
    }
    if (path.startsWith('/api/public/groupings/')) {
      state.publicAuth.push(req.headers()['authorization']);
      expect(req.headers()['authorization']).toBeUndefined();
      const c = state.groups[0] ?? group;
      return reply({
        ...c,
        status: c.status === 'DRAFT' ? 'OPEN' : c.status,
        orderable: options.orderable ?? true,
        offers: (c.offers ?? []).map((o) => ({
          ...o,
          offerId: o.id,
          catalog: {
            productName: state.products[0]?.name ?? product.name,
            productDescription: product.description,
            attributes: state.products[0]?.variants?.[0]?.attributes ?? {},
            usable: true,
          },
        })),
      });
    }
    expect(req.headers()['authorization']).toBe('Bearer e2e-only-token');
    if (path === '/api/organizations') {
      if (method === 'GET') return reply(state.shops);
      if (method === 'POST') {
        const created = { ...shop, name: body.name };
        state.shops.push(created);
        return reply(created, 201);
      }
    }
    if (path.endsWith('/subscription/invoices')) return reply([]);
    if (path.endsWith('/subscription/invoice-preview'))
      return reply({
        amount: 10000,
        currency: 'XOF',
        periodStart: '2099-10-01T00:00:00Z',
        periodEnd: '2099-11-01T00:00:00Z',
      });
    if (path.endsWith('/subscription/history')) return reply([]);
    if (path.endsWith('/subscription/billing-contact')) {
      const contact = { name: 'Boutique du Soleil', phoneE164: '+221771234567' };
      if (method === 'PUT') return reply({ ...contact, ...body });
      return reply(contact);
    }
    if (path.endsWith('/subscription/start') || path.endsWith('/subscription/cancel')) {
      return reply({
        access: { status: 'ACTIVE', canStartNewBusiness: true },
        canManageSubscription: true,
        billingProviderAvailable: false,
        billingProviderSetupPending: false,
        planConfigured: false,
        billingContact: { name: 'Boutique du Soleil', phoneE164: '+221771234567' },
      });
    }
    if (path.endsWith('/subscription')) {
      return reply({
        access: {
          status: 'ACTIVE',
          canStartNewBusiness: true,
          currentPeriodEndsAt: '2099-10-01T00:00:00Z',
          reason: 'SUBSCRIPTION_ACTIVE',
        },
        canManageSubscription: true,
        billingProviderAvailable: false,
        billingProviderSetupPending: false,
        planConfigured: false,
        billingContact: { name: 'Boutique du Soleil', phoneE164: '+221771234567' },
      });
    }
    if (/\/orders$/.test(path))
      return reply({
        items: [],
        page: Number(url.searchParams.get('page') ?? 0),
        size: Number(url.searchParams.get('size') ?? 20),
        totalElements: 0,
        totalPages: 0,
      });
    if (/\/products$/.test(path)) {
      if (method === 'GET')
        return reply({
          items: state.products.map(({ id, name, status, version }) => ({
            id,
            name,
            status,
            version,
          })),
          page: 0,
          size: 20,
          totalPages: 1,
          totalElements: state.products.length,
        });
      if (method === 'POST') {
        const created = {
          ...structuredClone(product),
          ...body,
          variants: body.variants?.length
            ? body.variants.map((v: any) => ({ ...v, id: variantId, active: true }))
            : product.variants,
        };
        state.products.push(created);
        return reply(created, 201);
      }
    }
    if (/\/products\/[^/]+$/.test(path))
      return reply(state.products.find((p) => p.id === path.split('/').pop()) ?? product);
    if (/\/grouping-campaigns$/.test(path)) {
      if (method === 'GET')
        return reply({
          items: state.groups,
          page: 0,
          size: 20,
          totalPages: 1,
          totalElements: state.groups.length,
        });
      if (method === 'POST') {
        const created = {
          ...structuredClone(group),
          ...body,
          version: 7,
          status: 'DRAFT',
          offers: [],
        };
        state.groups.push(created);
        return reply(created, 201);
      }
    }
    const current = state.groups.find((c) => c.id === groupId);
    if (current && path.includes('/grouping-campaigns/' + groupId)) {
      if (method === 'GET') return reply(current);
      if (state.conflict) {
        state.conflict = false;
        current.version = 91;
        return reply({ code: 'GROUPING_CAMPAIGN_VERSION_CONFLICT' }, 409);
      }
      expect(body.campaignVersion ?? body.version).toBe(current.version);
      if (path.endsWith('/offers')) {
        current.offers?.push({
          id: offerId,
          productVariantId: body.productVariantId,
          commercialLabel: body.commercialLabel,
          unitPrice: body.unitPrice,
          active: true,
          version: 4,
          priceRevision: 1,
          currency: 'XOF',
        });
        current.version = 42;
      } else if (path.endsWith('/open')) {
        current.status = 'OPEN';
        current.version = (current.version ?? 0) + 57;
      } else if (path.endsWith('/close')) {
        current.status = 'CLOSED';
        current.version = (current.version ?? 0) + 12;
      } else if (path.endsWith('/offers/' + offerId)) {
        expect(body.version).toBe(current.offers?.[0]?.version);
        Object.assign(current.offers![0], {
          unitPrice: body.unitPrice,
          commercialLabel: body.commercialLabel,
          version: 21,
        });
        current.version = 103;
      } else if (method === 'PUT') {
        Object.assign(current, body, { version: (current.version ?? 0) + 13 });
      } else throw new Error('Uncovered mock route ' + method + ' ' + path);
      return reply(current);
    }
    throw new Error('Unexpected API ' + method + ' ' + path);
  });
  return state;
}
export async function noOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
}
