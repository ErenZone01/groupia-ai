import { expect, test } from '@playwright/test';
import { mockApi, noOverflow, session } from './api-mock';

test('auth V3 : carrousel manuel et valeur métier expliquée sans promesse fictive', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await mockApi(page);
  await page.goto('/connexion');

  const carousel = page.getByRole('region', { name: 'Découvrir les possibilités de Groupia' });
  await expect(carousel.getByText('Groupages simplifiés')).toBeVisible();
  await carousel.getByRole('button', { name: 'Slide suivant' }).click();
  await expect(carousel.getByText('Vente directe · À venir')).toBeVisible();
  await expect(carousel.getByText('Fonctionnalité prévue prochainement')).toBeVisible();
  await carousel.getByRole('button', { name: 'Slide suivant' }).click();
  await expect(carousel.getByText('Un lien public', { exact: true })).toBeVisible();
  await expect(carousel.getByText(/sans créer de compte/i)).toBeVisible();
  await carousel.getByRole('button', { name: 'Slide précédent' }).click();
  await expect(carousel.getByText('Vente directe · À venir')).toBeVisible();
  await noOverflow(page);
});

test('auth V3 : carrousel sur mobile reste compact, formulaire accessible', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await mockApi(page);
  await page.goto('/inscription');
  await expect(page.getByRole('region', { name: 'Découvrir les possibilités de Groupia' })).toBeVisible();
  await expect(page.getByLabel('Prénom', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Date de naissance')).toBeVisible();
  await noOverflow(page);
});

test('auth V3 : inscription exige prénom, nom et naissance et ne publie pas la date', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/inscription');
  const submit = page.getByRole('button', { name: 'Créer mon compte' });
  await page.getByLabel('Numéro de téléphone').fill('+221771234567');
  await page.getByLabel('Mot de passe', { exact: true }).fill('mot-de-passe-assez-long');
  await page.getByLabel('Confirmer le mot de passe').fill('mot-de-passe-assez-long');
  await expect(submit).toBeDisabled();
  await page.getByLabel('Prénom', { exact: true }).fill('Awa');
  await page.getByLabel('Nom', { exact: true }).fill('Diop');
  await page.getByLabel('Date de naissance').fill('2999-01-01');
  await expect(submit).toBeDisabled();
  await page.getByLabel('Date de naissance').fill('1995-04-12');
  await expect(submit).toBeEnabled();
  await submit.click();
  await expect(page).toHaveURL(/\/connexion$/);
  const registered = state.calls.find((call) => call.path === '/api/auth/register');
  expect(registered?.body).toEqual({
    phoneNumber: '+221771234567',
    firstName: 'Awa',
    lastName: 'Diop',
    birthDate: '1995-04-12',
    password: 'mot-de-passe-assez-long',
  });
});

test('auth V3 : le tableau de bord salue la gérante sans exposer sa naissance', async ({ page }) => {
  await session(page);
  const state = await mockApi(page);
  await page.goto('/app/accueil');
  await expect(page.getByText('Bonjour Awa !')).toBeVisible();
  expect(state.calls.some((call) => call.path === '/api/auth/me' && call.method === 'GET')).toBe(true);
  await expect(page.getByText('1995-04-12')).toHaveCount(0);
});
