import { test, expect, Page } from '@playwright/test';
import { mockApi, session, shopId } from './api-mock';
async function browserSession(
  page: Page,
  state: { refreshes: number; status: number; delay?: number; expiresIn?: number },
) {
  await mockApi(page);
  await page.route('**/api/auth/refresh', async (route) => {
    state.refreshes++;
    const request = route.request();
    expect(request.method()).toBe('POST');
    expect(request.headers()['x-groupia-auth']).toBe('1');
    expect(request.headers()['authorization']).toBeUndefined();
    expect(request.postData()).toBeNull();
    if (state.delay) await new Promise((resolve) => setTimeout(resolve, state.delay));
    if (state.status !== 200)
      return route.fulfill({
        status: state.status,
        json: { code: state.status === 401 ? 'AUTH_REFRESH_INVALID' : 'UNAVAILABLE' },
      });
    return route.fulfill({
      status: 200,
      headers: {
        'Set-Cookie': `groupia_refresh=synthetic-rotation-${state.refreshes}; HttpOnly; SameSite=Lax; Path=/api/auth; Max-Age=604800`,
        'Cache-Control': 'no-store',
      },
      json: {
        accessToken: 'e2e-only-token',
        expiresIn: state.expiresIn ?? 900,
        tokenType: 'Bearer',
        sessionId: shopId,
      },
    });
  });
}
test('reload restaure en cookie HttpOnly sans stockage de credentials', async ({
  page,
  context,
}) => {
  const state = { refreshes: 0, status: 200 };
  await session(page);
  await browserSession(page, state);
  await page.goto('/app/boutique');
  await expect(page.getByRole('heading', { name: 'Boutique du Soleil' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Boutique du Soleil' })).toBeVisible();
  expect(state.refreshes).toBe(2);
  const cookie = (await context.cookies()).find((c) => c.name === 'groupia_refresh')!;
  expect(cookie.httpOnly).toBe(true);
  expect(cookie.path).toBe('/api/auth');
  expect(cookie.sameSite).toBe('Lax');
  const storage = await page.evaluate(async () => ({
    local: { ...localStorage },
    session: { ...sessionStorage },
    databases: await indexedDB.databases(),
    cookies: document.cookie,
  }));
  expect(storage.databases).toEqual([]);
  expect(JSON.stringify(storage)).not.toMatch(
    /e2e-only-token|synthetic-rotation|groupia_refresh|groupia.session/,
  );
});
test('deux onglets coordonnent le refresh et propagent la déconnexion', async ({
  page,
  context,
}) => {
  const second = await context.newPage(),
    state = { refreshes: 0, status: 200, delay: 300 };
  await session(page);
  await browserSession(page, state);
  await browserSession(second, state);
  await Promise.all([page.goto('/app/boutique'), second.goto('/app/boutique')]);
  for (const tab of [page, second])
    await expect(tab.getByRole('heading', { name: 'Boutique du Soleil' })).toBeVisible();
  expect(state.refreshes).toBe(1);
  await page.getByRole('button', { name: 'Se déconnecter' }).last().click();
  await expect(page).toHaveURL(/connexion/);
  await expect(second).toHaveURL(/connexion/);
  await second.reload();
  await expect(second).toHaveURL(/connexion/);
  expect(state.refreshes).toBe(1);
});
test('503 affiche reprise sans redirection mensongère vers connexion', async ({ page }) => {
  const state = { refreshes: 0, status: 503 };
  await browserSession(page, state);
  await page.goto('/app/boutique');
  await expect(page).toHaveURL(/session-indisponible/);
  await expect(page.getByRole('button', { name: 'Réessayer' })).toBeVisible();
});
test('401 produit un écran de connexion', async ({ page }) => {
  await browserSession(page, { refreshes: 0, status: 401 });
  await page.goto('/app/boutique');
  await expect(page).toHaveURL(/connexion/);
});
test('un refresh lent ne bloque pas le parcours public', async ({ page }) => {
  await browserSession(page, { refreshes: 0, status: 503, delay: 15000 });
  await page.goto('/suivi');
  await expect(page.getByRole('heading').first()).toBeVisible();
  await expect(page).toHaveURL(/suivi/);
});
test('logout hors ligne persiste seulement une intention et interdit la restauration', async ({
  page,
}) => {
  const state = { refreshes: 0, status: 200 };
  await session(page);
  await browserSession(page, state);
  await page.goto('/app/boutique');
  await expect(page.getByRole('heading', { name: 'Boutique du Soleil' })).toBeVisible();
  await page.route('**/api/auth/logout', (route) => route.abort('failed'));
  await page.getByRole('button', { name: 'Se déconnecter' }).last().click();
  await expect(page).toHaveURL(/connexion/);
  await page.reload();
  await expect(page).toHaveURL(/connexion/);
  expect(state.refreshes).toBe(1);
  expect(await page.evaluate(() => localStorage.getItem('groupia.logout-pending'))).toBe('pending');
});

test('refresh anticipé après login, sans écran de reconnexion', async ({ page }) => {
  await page.clock.install();
  const state = { refreshes: 0, status: 200, expiresIn: 61 };
  await session(page);
  await browserSession(page, state);
  await page.goto('/app/boutique');
  await expect(page.getByRole('heading', { name: 'Boutique du Soleil' })).toBeVisible();
  state.expiresIn = 900;
  await page.clock.runFor(2000);
  await expect.poll(() => state.refreshes).toBe(2);
  await expect(page).toHaveURL(/app\/boutique/);
  await expect(page.getByRole('heading', { name: 'Boutique du Soleil' })).toBeVisible();
});
