import { expect, Page, test } from '@playwright/test';
import { mockApi, noOverflow, session } from './api-mock';

async function dashboard(page: Page, mode: 'populated' | 'empty' | 'error' = 'populated') {
  await session(page);
  await mockApi(page);
  const calls: string[] = [];
  await page.route('**/api/organizations/*/orders?*', (route) => {
    const query = new URL(route.request().url()).searchParams;
    calls.push(query.toString());
    if (mode === 'error')
      return route.fulfill({ status: 503, json: { code: 'SERVICE_UNAVAILABLE' } });
    const counts: Record<string, number> = { CONFIRMED: 7, PENDING_PAYMENT: 5, EXPIRED: 2 };
    return route.fulfill({
      json: {
        items:
          mode === 'empty' || query.has('status')
            ? []
            : [
                {
                  id: '10000000-0000-4000-8000-000000000001',
                  orderNumber: 'GRP-000000000025',
                  initialAmountDue: 18500,
                  currency: 'XOF',
                  status: 'CONFIRMED',
                  placedAt: '2026-10-20T10:30:00Z',
                },
                {
                  id: '10000000-0000-4000-8000-000000000002',
                  orderNumber: 'GRP-000000000024',
                  initialAmountDue: 45000,
                  currency: 'XOF',
                  status: 'PENDING_PAYMENT',
                  placedAt: '2026-10-20T09:15:00Z',
                },
                {
                  id: '10000000-0000-4000-8000-000000000003',
                  orderNumber: 'GRP-000000000023',
                  initialAmountDue: 12500,
                  currency: 'XOF',
                  status: 'EXPIRED',
                  placedAt: '2026-10-19T16:00:00Z',
                },
              ],
        totalElements: mode === 'empty' ? 0 : (counts[query.get('status') ?? ''] ?? 25),
        page: 0,
        size: Number(query.get('size')),
        totalPages: 1,
      },
    });
  });
  return calls;
}

for (const width of [360, 390, 430, 768, 1024, 1280]) {
  for (const theme of ['light', 'dark']) {
    test(`fondations UX ${width} ${theme} : données, cibles, clavier et capture`, async ({
      page,
    }, info) => {
      await page.setViewportSize({ width, height: 900 });
      await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
      const calls = await dashboard(page);
      await page.goto('/app/accueil');
      await expect(page.locator('g-stat-card .value')).toHaveText(['25', '7', '5', '2']);
      const label = page.locator('g-stat-card p').first();
      expect(
        await label.evaluate(
          (el) => el.getBoundingClientRect().height / parseFloat(getComputedStyle(el).lineHeight),
        ),
      ).toBeLessThanOrEqual(1.1);
      expect(calls).toHaveLength(4);
      await expect(page.locator('g-recent-orders li')).toHaveCount(3);
      await noOverflow(page);
      expect(
        await page.locator('body').evaluate((el) => parseFloat(getComputedStyle(el).fontSize)),
      ).toBeGreaterThanOrEqual(16);
      const create = page.getByRole('link', { name: 'Créer un groupage', exact: true });
      await expect(create).toHaveAttribute('href', '/app/groupages/nouveau');
      const box = await create.boundingBox();
      expect(box!.height).toBeGreaterThanOrEqual(44);
      await create.focus();
      await expect(create).toBeFocused();
      expect(await create.evaluate((el) => getComputedStyle(el).outlineStyle)).not.toBe('none');
      await page.screenshot({
        path: info.outputPath(`dashboard-${width}-${theme}.png`),
        fullPage: true,
      });
      if (width < 1024) {
        for (const link of await page.locator('g-mobile-bottom-nav .bar a').all()) {
          const target = (await link.boundingBox())!;
          const text = (await link.locator('span').boundingBox())!;
          expect(text.x).toBeGreaterThanOrEqual(target.x);
          expect(text.x + text.width).toBeLessThanOrEqual(target.x + target.width);
        }
        const more = page.getByRole('button', { name: 'Plus', exact: true });
        await more.focus();
        await page.keyboard.press('Enter');
        const dialog = page.getByRole('dialog', { name: 'Plus de pages' });
        await expect(dialog.getByRole('button', { name: 'Fermer le menu Plus' })).toBeFocused();
        await noOverflow(page);
        await page.screenshot({ path: info.outputPath(`plus-${width}-${theme}.png`) });
        await page.keyboard.press('Escape');
        await expect(more).toBeFocused();
      }
      await create.focus();
      await page.keyboard.press('Enter');
      await expect(page).toHaveURL(/\/app\/groupages\/nouveau$/);
      await noOverflow(page);
    });
  }
}

test('état vide réel et erreur ne sont pas confondus avec des statistiques inventées', async ({
  page,
}) => {
  await dashboard(page, 'empty');
  await page.goto('/app/accueil');
  await expect(page.locator('g-stat-card .value')).toHaveText(['0', '0', '0', '0']);
  await expect(
    page.getByRole('heading', { name: 'Aucune commande pour le moment.' }),
  ).toBeVisible();
  await page.route('**/api/organizations/*/orders?*', (route) =>
    route.fulfill({ status: 503, json: { code: 'SERVICE_UNAVAILABLE' } }),
  );
  await page.getByRole('button', { name: 'Actualiser le tableau de bord' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.locator('g-stat-card .value')).toHaveText(['—', '—', '—', '—']);
  await expect(page.getByRole('heading', { name: 'Aucune commande pour le moment.' })).toHaveCount(
    0,
  );
  await expect(page.getByRole('button', { name: 'Réessayer', exact: true })).toBeEnabled();
});

test('composants : cibles tactiles, reduced motion, reflow à 200 % et focus', async ({ page }) => {
  await session(page);
  await mockApi(page);
  await page.setViewportSize({ width: 360, height: 900 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/app/atelier');
  const toggle = page.getByRole('switch');
  expect((await toggle.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  await toggle.focus();
  await page.keyboard.press('Space');
  await expect(toggle).toHaveAttribute('aria-checked', 'true');
  expect(
    await page
      .locator('g-skeleton span[role=status]')
      .evaluate((el) => getComputedStyle(el).animationName),
  ).toBe('none');
  await noOverflow(page);
  await page.goto('/app/accueil');
  await page.addStyleTag({ content: 'html { font-size: 200%; }' });
  await noOverflow(page);
});

test('champs natifs : sélection de fichier sans débordement à 360 px', async ({ page }) => {
  await session(page);
  await mockApi(page);
  await page.setViewportSize({ width: 360, height: 900 });
  await page.route('**/api/organizations/*/products/*/media', (route) =>
    route.fulfill({
      json: { enabled: true, canManage: true, items: [], reservedCount: 0, version: 0 },
    }),
  );
  await page.goto('/app/products');
  await page.getByRole('button', { name: 'Voir les images' }).click();
  const picker = page.getByLabel('Ajouter des images');
  await expect(picker).toBeVisible();
  await noOverflow(page);
});

for (const theme of ['light', 'dark']) {
  test(`contrastes des tokens texte et contrôles ${theme}`, async ({ page }) => {
    await session(page);
    await mockApi(page);
    await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
    await page.goto('/app/accueil');
    const contrasts = await page.evaluate(() => {
      const style = getComputedStyle(document.documentElement);
      const lum = (token: string) => {
        const hex = style.getPropertyValue(token).trim();
        const rgb = [1, 3, 5]
          .map((start) => parseInt(hex.slice(start, start + 2), 16) / 255)
          .map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
        return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
      };
      const pairs = [
        ['--text', '--surface', 4.5],
        ['--muted', '--background', 4.5],
        ['--muted', '--surface-alt', 4.5],
        ['--on-primary', '--primary', 4.5],
        ['--on-primary', '--primary-hover', 4.5],
        ['--on-accent', '--accent', 4.5],
        ['--hero-text', '--hero', 4.5],
        ['--hero-muted', '--hero', 4.5],
        ['--primary', '--surface', 3],
        ['--warning-text', '--surface', 3],
        ['--sidebar-muted', '--sidebar', 4.5],
        ['--sidebar-active-text', '--sidebar-active', 4.5],
        ['--positive-text', '--positive-soft', 4.5],
        ['--warning-text', '--warning-soft', 4.5],
        ['--danger-text', '--danger-soft', 4.5],
        ['--info-text', '--info-soft', 4.5],
        ['--control-border', '--surface', 3],
        ['--focus', '--surface', 3],
      ] as const;
      return pairs.map(([foreground, background, minimum]) => {
        const a = lum(foreground),
          b = lum(background);
        return {
          foreground,
          background,
          minimum,
          ratio: (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05),
        };
      });
    });
    for (const c of contrasts)
      expect(c.ratio, `${c.foreground} on ${c.background}`).toBeGreaterThanOrEqual(c.minimum);
  });
}
