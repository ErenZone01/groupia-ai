import { expect, test } from '@playwright/test';
import { mockApi, noOverflow } from './api-mock';

for (const [width, theme] of [
  [320, 'light'],
  [360, 'dark'],
  [390, 'light'],
  [768, 'dark'],
  [1440, 'light'],
] as const) {
  test(`auth UX V2 — connexion responsive ${width}px ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 850 });
    await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
    await mockApi(page);
    await page.goto('/connexion');
    await expect(page.getByRole('heading', { name: 'Bon retour' })).toBeVisible();
    await expect(page.getByLabel('Numéro de téléphone')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Se connecter' })).toBeDisabled();
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
    await expect(page.getByRole('link', { name: 'Créer un compte' })).toBeVisible();
    await noOverflow(page);

    await page.getByLabel('Numéro de téléphone').fill('+221771234567');
    await page.getByLabel('Mot de passe', { exact: true }).fill('un-secret-de-test');
    await expect(page.getByRole('button', { name: 'Se connecter' })).toBeEnabled();
    await page.getByRole('button', { name: 'Afficher le mot de passe' }).click();
    await expect(page.getByLabel('Mot de passe', { exact: true })).toHaveAttribute('type', 'text');
    await page.getByRole('button', { name: 'Masquer le mot de passe' }).click();
    await expect(page.getByLabel('Mot de passe', { exact: true })).toHaveAttribute('type', 'password');
    await noOverflow(page);
  });
}

test('auth UX V2 — connexion conserve la session et les redirections autorisées', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/connexion?returnUrl=%2Fapp%2Forders');
  await page.getByLabel('Numéro de téléphone').fill('+221771234567');
  await page.getByLabel('Mot de passe', { exact: true }).fill('un-secret-de-test');
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await expect(page).toHaveURL(/\/app\/orders$/);
  const login = state.calls.find((call) => call.path === '/api/auth/login');
  expect(login?.body).toEqual({ phoneNumber: '+221771234567', password: 'un-secret-de-test' });
});

test('auth UX V2 — inscription : confirmation du mot de passe et contrat existant', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/inscription');
  await expect(page.getByRole('heading', { name: 'Créer votre compte' })).toBeVisible();
  await expect(page.getByText("Le nom et les informations de votre boutique se configurent après l\u0027inscription.")).toBeVisible();
  await expect(page.getByRole('button', { name: 'Créer mon compte' })).toBeDisabled();

  await page.getByLabel('Prénom', { exact: true }).fill('Awa');
  await page.getByLabel('Nom', { exact: true }).fill('Diop');
  await page.getByLabel('Date de naissance').fill('1995-04-12');
  await page.getByLabel('Numéro de téléphone').fill('+221771234567');
  await page.getByLabel('Adresse email').fill('awa@example.test');
  await page.getByLabel('Mot de passe', { exact: true }).fill('une-phrase-secrete-longue');
  await page.getByLabel('Confirmer le mot de passe').fill('une-phrase-secrete-inexacte');
  await expect(page.getByText('Les mots de passe ne correspondent pas.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Créer mon compte' })).toBeDisabled();

  await page.getByLabel('Confirmer le mot de passe').fill('une-phrase-secrete-longue');
  await expect(page.getByRole('button', { name: 'Créer mon compte' })).toBeEnabled();
  await page.getByRole('button', { name: 'Créer mon compte' }).click();
  await expect(page).toHaveURL(/\/connexion$/);
  const register = state.calls.find((call) => call.path === '/api/auth/register');
  expect(register?.body).toEqual({
    phoneNumber: '+221771234567',
    firstName: 'Awa',
    lastName: 'Diop',
    birthDate: '1995-04-12',
    email: 'awa@example.test',
    password: 'une-phrase-secrete-longue',
  });
});

test('auth UX V2 — inscription : email facultatif et longueur UTF-8', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/inscription');
  await page.getByLabel('Prénom', { exact: true }).fill('Awa');
  await page.getByLabel('Nom', { exact: true }).fill('Diop');
  await page.getByLabel('Date de naissance').fill('1995-04-12');
  await page.getByLabel('Numéro de téléphone').fill('+221771234567');
  await page.getByLabel('Mot de passe', { exact: true }).fill('phrase-trop-courte');
  await page.getByLabel('Confirmer le mot de passe').fill('phrase-trop-courte');
  await expect(page.getByRole('button', { name: 'Créer mon compte' })).toBeEnabled();
  await page.getByLabel('Mot de passe', { exact: true }).fill('petit');
  await page.getByLabel('Confirmer le mot de passe').fill('petit');
  await expect(page.getByRole('button', { name: 'Créer mon compte' })).toBeDisabled();
  await page.getByLabel('Mot de passe', { exact: true }).fill('é'.repeat(40));
  await page.getByLabel('Confirmer le mot de passe').fill('é'.repeat(40));
  await expect(page.getByRole('button', { name: 'Créer mon compte' })).toBeDisabled();
  await page.getByLabel('Mot de passe', { exact: true }).fill('mot-de-passe-assez-long');
  await page.getByLabel('Confirmer le mot de passe').fill('mot-de-passe-assez-long');
  await page.getByRole('button', { name: 'Créer mon compte' }).click();
  await expect(page).toHaveURL(/\/connexion$/);
  const register = state.calls.find((call) => call.path === '/api/auth/register');
  expect(register?.body).toEqual({
    phoneNumber: '+221771234567',
    firstName: 'Awa',
    lastName: 'Diop',
    birthDate: '1995-04-12',
    password: 'mot-de-passe-assez-long',
  });
});

test('auth UX V2 — liens entre les écrans, sans fonctions de connexion non disponibles', async ({ page }) => {
  await mockApi(page);
  await page.goto('/connexion');
  await expect(page.getByRole('button', { name: /Google|Apple|Mot de passe oublié/ })).toHaveCount(0);
  await page.getByRole('link', { name: 'Créer un compte' }).click();
  await expect(page).toHaveURL(/\/inscription$/);
  await page.getByRole('link', { name: 'Se connecter' }).click();
  await expect(page).toHaveURL(/\/connexion$/);
  await noOverflow(page);
});
