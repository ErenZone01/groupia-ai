import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
export const routes: Routes = [
  {
    path: 'session-indisponible',
    loadComponent: () =>
      import('./features/auth/session-unavailable.page').then((m) => m.SessionUnavailablePage),
  },
  {
    path: 's/:token',
    loadComponent: () =>
      import('./features/customer-access/magic-link.page').then((m) => m.MagicLinkPage),
  },
  { path: 's', redirectTo: 'suivi', pathMatch: 'full' },
  {
    path: 'mes-commandes',
    loadComponent: () =>
      import('./features/customer-access/my-orders.page').then((m) => m.MyOrdersPage),
  },
  {
    path: 'mes-commandes/:orderNumber',
    loadComponent: () =>
      import('./features/customer-access/customer-order.page').then((m) => m.CustomerOrderPage),
  },
  {
    path: 'v/:publicIdentifier/panier',
    title: 'Mon panier · Vente directe · Groupia',
    loadComponent: () => import('./features/direct-sales/direct-cart.page').then((m) => m.DirectCartPage),
  },
  {
    path: 'v/:publicIdentifier/coordonnees',
    title: 'Vos coordonnées · Vente directe · Groupia',
    loadComponent: () =>
      import('./features/direct-sales/direct-checkout.page').then((m) => m.DirectCheckoutPage),
  },
  {
    path: 'v/:publicIdentifier',
    title: 'Vente directe · Groupia',
    loadComponent: () => import('./features/direct-sales/public-direct.page').then((m) => m.PublicDirectPage),
  },
  {
    path: 'public/groupages/:publicIdentifier/cart',
    title: 'Mon panier · Groupia',
    loadComponent: () => import('./features/checkout/cart.page').then((m) => m.CartPage),
  },
  {
    path: 'public/groupages/:publicIdentifier/checkout',
    title: 'Mes coordonnées · Groupia',
    loadComponent: () => import('./features/checkout/checkout.page').then((m) => m.CheckoutPage),
  },
  {
    path: 'public/order',
    title: 'Ma commande · Groupia',
    loadComponent: () => import('./features/tracking/tracking.page').then((m) => m.TrackingPage),
  },

  {
    path: 'connexion',
    title: 'Connexion · Groupia',
    loadComponent: () => import('./features/auth/auth.page').then((m) => m.AuthPage),
  },
  {
    path: 'inscription',
    title: 'Créer mon compte · Groupia',
    data: { register: true },
    loadComponent: () => import('./features/auth/auth.page').then((m) => m.AuthPage),
  },
  {
    path: 'public/groupages/:publicIdentifier',
    title: 'Votre groupage · Groupia',
    loadComponent: () =>
      import('./features/public-groupage/public-groupage.page').then((m) => m.PublicGroupagePage),
  },
  {
    path: 'g/:publicId',
    loadComponent: () =>
      import('./features/public-groupage/public-groupage.page').then((m) => m.PublicGroupagePage),
  },
  {
    path: 'g/:publicId/commander',
    loadComponent: () => import('./features/checkout/checkout.page').then((m) => m.CheckoutPage),
  },
  {
    path: 'suivi',
    loadComponent: () =>
      import('./features/customer-access/recovery.page').then((m) => m.RecoveryPage),
  },
  {
    path: 'app',
    canActivate: [authGuard],
    canActivateChild: [authGuard],
    loadComponent: () => import('./core/layout/manager-layout').then((m) => m.ManagerLayout),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'accueil' },
      {
        path: 'accueil',
        title: 'Accueil · Groupia',
        loadComponent: () =>
          import('./features/dashboard/dashboard.page').then((m) => m.DashboardPage),
      },
      {
        path: 'statistiques',
        title: 'Statistiques · Groupia',
        loadComponent: () =>
          import('./features/dashboard/statistics.page').then((m) => m.StatisticsPage),
      },
      {
        path: 'boutique',
        title: 'Ma boutique · Groupia',
        loadComponent: () =>
          import('./features/boutique/boutique.page').then((m) => m.BoutiquePage),
      },
      {
        path: 'vente-directe',
        title: 'Vente directe · Groupia',
        loadComponent: () => import('./features/direct-sales/direct-sales.page').then((m) => m.DirectSalesPage),
      },
      {
        path: 'vente-directe/commandes',
        title: 'Commandes directes · Groupia',
        loadComponent: () =>
          import('./features/direct-sales/direct-orders.page').then((m) => m.DirectOrdersPage),
      },
      {
        path: 'products',
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
        path: 'groupages/nouveau',
        title: 'Créer un groupage · Groupia',
        loadComponent: () =>
          import('./features/groupages/groupage-wizard.page').then((m) => m.GroupageWizardPage),
      },
      {
        path: 'groupages/:id',
        title: 'Mon groupage · Groupia',
        loadComponent: () =>
          import('./features/groupages/groupage-detail.page').then((m) => m.GroupageDetailPage),
      },
      {
        path: 'orders',
        title: 'Commandes · Groupia',
        loadComponent: () => import('./features/orders/orders.page').then((m) => m.OrdersPage),
      },
      {
        path: 'orders/:orderId',
        title: 'Commande · Groupia',
        loadComponent: () =>
          import('./features/orders/order-detail.page').then((m) => m.OrderDetailPage),
      },
      { path: 'commandes', redirectTo: 'orders', pathMatch: 'full' },
      {
        path: 'versements',
        title: 'Mes versements · Groupia',
        loadComponent: () =>
          import('./features/settlements/settlements.page').then((m) => m.SettlementsPage),
      },
      {
        path: 'abonnement',
        title: 'Abonnement · Groupia',
        loadComponent: () =>
          import('./features/subscription/subscription.page').then((m) => m.SubscriptionPage),
      },
      {
        path: 'paiements/requests/:requestId',
        title: 'Détail du paiement · Groupia',
        loadComponent: () =>
          import('./features/payments/payment-request.page').then((m) => m.PaymentRequestPage),
      },
      {
        path: 'paiements/reviews/:reviewId',
        title: 'Paiement à examiner · Groupia',
        loadComponent: () =>
          import('./features/payments/payment-review.page').then((m) => m.PaymentReviewPage),
      },
      {
        path: 'paiements',
        title: 'Paiements · Groupia',
        loadComponent: () =>
          import('./features/payments/payments.page').then((m) => m.PaymentsPage),
      },
      {
        path: 'atelier',
        title: 'Atelier · Groupia',
        loadComponent: () =>
          import('./features/dashboard/ui-showcase.page').then((m) => m.UiShowcasePage),
      },
    ],
  },
  { path: 'accueil', redirectTo: 'app/accueil', pathMatch: 'full' },
  { path: 'catalogue', redirectTo: 'app/products', pathMatch: 'full' },
  { path: 'groupages', redirectTo: 'app/groupages', pathMatch: 'full' },
  { path: 'boutique', redirectTo: 'app/boutique', pathMatch: 'full' },
  { path: 'commandes', redirectTo: 'app/orders', pathMatch: 'full' },
  { path: 'paiements', redirectTo: 'app/paiements', pathMatch: 'full' },
  { path: '', pathMatch: 'full', redirectTo: 'app/accueil' },
  { path: '**', redirectTo: 'app/accueil' },
];
