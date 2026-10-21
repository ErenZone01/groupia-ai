import { test, expect } from '@playwright/test';
import { mockApi, session, noOverflow } from './api-mock';
const origin = 'http://127.0.0.1:4300';
test('production: security headers, fresh nonce, no HSTS on plain HTTP', async ({ request }) => {
  const first = await request.get(origin + '/connexion');
  const second = await request.get(origin + '/connexion');
  const headers = first.headers(),
    csp = headers['content-security-policy'];
  expect(csp).toContain("frame-ancestors 'none'");
  expect(csp).toContain("object-src 'none'");
  expect(csp).toContain("require-trusted-types-for 'script'");
  expect(csp).not.toContain('unsafe-eval');
  expect(csp).toContain("script-src 'self';");
  expect(headers['x-content-type-options']).toBe('nosniff');
  expect(headers['referrer-policy']).toBe('no-referrer');
  expect(headers['permissions-policy']).toContain('camera=()');
  expect(headers['strict-transport-security']).toBeUndefined();
  expect(headers['cache-control']).toBe('no-store');
  expect(second.headers()['content-security-policy']).not.toBe(csp);
  const nonce = /'nonce-([^']+)'/.exec(csp)?.[1];
  expect(nonce).toBeTruthy();
  expect(await first.text()).toContain(`ngCspNonce="${nonce}"`);
  expect((await request.post(origin + '/connexion')).status()).toBe(405);
});
for (const width of [360, 1440])
  test(`production: Angular runs under CSP and Trusted Types at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await session(page);
    await mockApi(page);
    const violations: string[] = [],
      errors: string[] = [];
    page.on('console', (message) => {
      if (message.type() === 'error') errors.push(message.text());
    });
    page.on('pageerror', (error) => errors.push(error.message));
    await page.exposeFunction('reportCspViolation', (directive: string) =>
      violations.push(directive),
    );
    await page.addInitScript(() =>
      document.addEventListener('securitypolicyviolation', (event) => {
        const report = Reflect.get(window, 'reportCspViolation');
        report(event.violatedDirective);
      }),
    );
    await page.goto(origin + '/accueil');
    await expect(page.getByRole('heading', { name: 'Vue d’ensemble' })).toBeVisible();
    await expect(page.locator('g-stat-card')).toHaveCount(4);
    await noOverflow(page);
    await page
      .getByRole('link', { name: 'Groupages', exact: true })
      .filter({ visible: true })
      .first()
      .click();
    await expect(page).toHaveURL(/groupages/);
    expect(violations).toEqual([]);
    expect(errors).toEqual([]);
  });
