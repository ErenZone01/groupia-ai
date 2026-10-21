export const navigation = [
  {
    path: '/app/accueil',
    label: 'Accueil',
    shortLabel: 'Accueil',
    icon: 'home',
    mobile: 'primary',
    mobileOrder: 0,
  },
  {
    path: '/app/groupages',
    label: 'Groupages',
    shortLabel: 'Groupages',
    icon: 'group',
    mobile: 'primary',
    mobileOrder: 1,
  },
  {
    path: '/app/products',
    label: 'Produits',
    shortLabel: 'Produits',
    icon: 'box',
    mobile: 'primary',
    mobileOrder: 2,
  },
  {
    path: '/app/orders',
    label: 'Commandes',
    shortLabel: 'Commandes',
    icon: 'orders',
    mobile: 'primary',
    mobileOrder: 3,
  },
  {
    path: '/app/paiements',
    label: 'Paiements',
    shortLabel: 'Paiements',
    icon: 'card',
    mobile: 'secondary',
    mobileOrder: 1,
  },
  {
    path: '/app/statistiques',
    label: 'Statistiques',
    shortLabel: 'Statistiques',
    icon: 'trend',
    mobile: 'secondary',
    mobileOrder: 4,
  },
  {
    path: '/app/boutique',
    label: 'Ma boutique',
    shortLabel: 'Ma boutique',
    icon: 'store',
    mobile: 'secondary',
    mobileOrder: 0,
  },
  {
    path: '/app/versements',
    label: 'Mes versements',
    shortLabel: 'Versements',
    icon: 'card',
    mobile: 'secondary',
    mobileOrder: 2,
  },
  {
    path: '/app/abonnement',
    label: 'Abonnement',
    shortLabel: 'Abonnement',
    icon: 'card',
    mobile: 'secondary',
    mobileOrder: 3,
  },
] as const;
export const primaryMobileNavigation = navigation
  .filter((item) => item.mobile === 'primary')
  .sort((a, b) => a.mobileOrder - b.mobileOrder);
export const secondaryMobileNavigation = navigation
  .filter((item) => item.mobile === 'secondary')
  .sort((a, b) => a.mobileOrder - b.mobileOrder);
