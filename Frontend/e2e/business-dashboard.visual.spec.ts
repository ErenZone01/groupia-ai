import { expect, test } from '@playwright/test';
import { mockApi, noOverflow, session, group } from './api-mock';
import { analyticsFixture } from './business-dashboard-fixture';

const phase = process.env['DASHBOARD_CAPTURE_PHASE'] === 'before' ? 'before' : 'after';
for (const view of ['accueil', 'statistiques'])
  for (const width of [390, 1280])
    for (const theme of ['light', 'dark']) {
      for (const state of ['populated', 'empty', 'loading', 'error']) {
        test(`capture ${view} ${width} ${theme} ${state}`, async ({ page }) => {
          await page.setViewportSize({ width, height: 960 });
          await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
          await page.emulateMedia({ reducedMotion: 'reduce' });
          await session(page);
          const stateApi = await mockApi(page);
          if (state === 'empty') {
            stateApi.groups = [];
            stateApi.products = [];
          } else
            stateApi.groups = [
              { ...group, title: 'Les essentiels de novembre', status: 'OPEN' },
              {
                ...group,
                id: '88888888-8888-4888-8888-888888888888',
                title: 'Sélection de fin d’année',
                status: 'DRAFT',
              },
              {
                ...group,
                id: '99999999-9999-4999-8999-999999999999',
                title: 'Accessoires du quotidien',
                status: 'IN_TRANSIT',
              },
            ];
          await page.route('**/grouping-campaigns?*', (route) => {
            if (state === 'loading') return new Promise<void>(() => {});
            if (state === 'error') return route.fulfill({ status: 503, json: {} });
            return route.fallback();
          });
          await page.route('**/analytics/business?*', (route) => {
            if (state === 'loading')
              return new Promise<void>(() => {
                /* Deliberately pending until page closes. */
              });
            return route.fulfill(
              state === 'error'
                ? { status: 503, json: {} }
                : {
                    json: analyticsFixture(
                      Number(new URL(route.request().url()).searchParams.get('months')),
                      state === 'empty',
                    ),
                  },
            );
          });
          await page.route('**/organizations/*/orders?*', (route) => {
            if (state === 'loading')
              return new Promise<void>(() => {
                /* Capture the loading skeleton. */
              });
            if (state === 'error') return route.fulfill({ status: 503, json: {} });
            const filtered = new URL(route.request().url()).searchParams.has('status');
            return route.fulfill({
              json: {
                totalElements: state === 'empty' ? 0 : filtered ? 7 : 25,
                items:
                  state === 'empty' || filtered
                    ? []
                    : [
                        {
                          id: '10000000-0000-4000-8000-000000000001',
                          orderNumber: 'GRP-000000000025',
                          initialAmountDue: 18500,
                          currency: 'XOF',
                          status: 'CONFIRMED',
                          placedAt: '2026-12-21T08:00:00Z',
                        },
                        {
                          id: '10000000-0000-4000-8000-000000000002',
                          orderNumber: 'GRP-000000000024',
                          initialAmountDue: 45000,
                          currency: 'XOF',
                          status: 'PENDING_PAYMENT',
                          placedAt: '2026-12-20T11:00:00Z',
                        },
                      ],
                page: 0,
                size: 5,
                totalPages: 5,
              },
            });
          });
          await page.goto(phase === 'before' ? '/app/accueil' : `/app/${view}`);
          await expect(page.locator('h1')).toContainText(
            phase === 'before' ? 'Vue d’ensemble' : view === 'accueil' ? 'Accueil' : 'Statistiques',
          );
          if (state === 'populated' && (phase === 'before' || view === 'accueil'))
            await expect(page.locator('g-recent-orders li')).toHaveCount(2);
          if (state === 'empty' && (phase === 'before' || view === 'accueil'))
            await expect(
              page.getByRole('heading', { name: 'Aucune commande pour le moment.' }),
            ).toBeVisible();
          if (state === 'error') await expect(page.getByRole('alert').first()).toBeVisible();
          if (state === 'loading') await expect(page.getByRole('status').first()).toBeVisible();
          if (state === 'populated' || state === 'empty')
            await expect(
              page.locator(view === 'statistiques' ? '.metric-value' : '.preview-values'),
            ).not.toHaveCount(0);
          await page.evaluate(() => document.fonts.ready);
          await noOverflow(page);
          await page.screenshot({
            path: `docs/product-ux-v2/captures/${phase}-${view}-${width}-${theme}-${state}.png`,
            fullPage: true,
          });
          if (phase === 'after' && state === 'populated') {
            await page.screenshot({
              path: `docs/product-ux-v2/captures/after-${view}-${width}-${theme}-viewport.png`,
            });
            if (view === 'accueil' && width === 390) {
              await page.locator('.activity-preview').scrollIntoViewIfNeeded();
              await page.screenshot({
                path: `docs/product-ux-v2/captures/after-accueil-390-${theme}-activity.png`,
              });
            }
          }
        });
      }
    }
