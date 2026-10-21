import { expect, test } from '@playwright/test';
import { mockApi, noOverflow, session } from './api-mock';
import { analyticsFixture } from './business-dashboard-fixture';

const phase = process.env['DASHBOARD_CAPTURE_PHASE'] === 'before' ? 'before' : 'after';
for (const width of [390, 1280])
  for (const theme of ['light', 'dark']) {
    for (const state of ['populated', 'empty', 'loading', 'error']) {
      test(`capture ${width} ${theme} ${state}`, async ({ page }) => {
        await page.setViewportSize({ width, height: 960 });
        await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
        await page.emulateMedia({ reducedMotion: 'reduce' });
        await session(page);
        await mockApi(page);
        await page.route('**/analytics/business?*', (route) => {
          if (state === 'loading')
            return new Promise<void>(() => {
              /* Deliberately pending until page closes. */
            });
          return route.fulfill(
            state === 'error'
              ? { status: 503, json: {} }
              : { json: analyticsFixture(12, state === 'empty') },
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
        await page.goto('/app/accueil');
        await expect(page.locator('h1')).toContainText(
          phase === 'before' ? 'Bonjour' : 'Vue d’ensemble',
        );
        if (state === 'populated') await expect(page.locator('g-recent-orders li')).toHaveCount(2);
        if (state === 'empty')
          await expect(
            page.getByRole('heading', { name: 'Aucune commande pour le moment.' }),
          ).toBeVisible();
        if (state === 'error') await expect(page.getByRole('alert').first()).toBeVisible();
        if (state === 'loading') await expect(page.getByRole('status').first()).toBeVisible();
        await page.evaluate(() => document.fonts.ready);
        await noOverflow(page);
        await page.screenshot({
          path: `docs/business-dashboard-ui-v2/captures/${phase}-${width}-${theme}-${state}.png`,
          fullPage: true,
        });
      });
    }
  }
