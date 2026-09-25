import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { environment } from './core/config/environment';
export const routes: Routes = [
  {
    path: 'connexion',
    title: 'Connexion · Groupia',
    loadComponent: () => import('./features/auth/auth.page').then((m) => m.AuthPage),
  },
  {
    path: 'g/:publicId',
    title: 'Votre groupage · Groupia',
    loadComponent: () =>
      import('./features/public-groupage/public-groupage.page').then((m) => m.PublicGroupagePage),
  },
  {
    path: 'g/:publicId/commander',
    title: 'Commander · Groupia',
    loadComponent: () => import('./features/checkout/checkout.page').then((m) => m.CheckoutPage),
  },
  {
    path: 'suivi',
    title: 'Suivre ma commande · Groupia',
    loadComponent: () => import('./features/tracking/tracking.page').then((m) => m.TrackingPage),
  },
  {
    path: '',
    canActivate: [authGuard],
    canActivateChild: [authGuard],
    loadComponent: () => import('./core/layout/manager-layout').then((m) => m.ManagerLayout),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'accueil' },
      {
        path: 'accueil',
        title: 'Tableau de bord · Groupia',
        loadComponent: () =>
          import('./features/dashboard/dashboard.page').then((m) => m.DashboardPage),
      },
      {
        path: 'boutique',
        title: 'Ma boutique · Groupia',
        loadComponent: () =>
          import('./features/boutique/boutique.page').then((m) => m.BoutiquePage),
      },
      {
        path: 'catalogue',
        title: 'Produits · Groupia',
        loadComponent: () => import('./features/catalog/catalog.page').then((m) => m.CatalogPage),
      },
      {
        path: 'groupages',
        title: 'Groupages · Groupia',
        loadComponent: () =>
          import('./features/groupages/groupages.page').then((m) => m.GroupagesPage),
      },
      {
        path: 'commandes',
        title: 'Commandes · Groupia',
        loadComponent: () => import('./features/orders/orders.page').then((m) => m.OrdersPage),
      },
      {
        path: 'paiements',
        title: 'Paiements · Groupia',
        loadComponent: () =>
          import('./features/payments/payments.page').then((m) => m.PaymentsPage),
      },
      ...(environment.preview
        ? [
            {
              path: 'atelier',
              title: 'Atelier · Groupia',
              loadComponent: () =>
                import('./features/dashboard/ui-showcase.page').then((m) => m.UiShowcasePage),
            },
          ]
        : []),
    ],
  },
  { path: '**', redirectTo: 'accueil' },
];
