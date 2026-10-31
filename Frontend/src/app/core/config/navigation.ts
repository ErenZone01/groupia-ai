export type NavigationSection = 'work' | 'finance' | 'shop';

export const navigation = [
  {
    path: '/app/accueil',
    label: 'Accueil',
    shortLabel: 'Accueil',
    icon: 'home',
    section: 'work',
    mobile: 'primary',
    mobileOrder: 0,
  },
  {
    path: '/app/groupages',
    label: 'Groupages',
    shortLabel: 'Groupages',
    icon: 'group',
    section: 'work',
    mobile: 'primary',
    mobileOrder: 1,
  },
  {
    path: '/app/vente-directe',
    label: 'Vente directe',
    shortLabel: 'Vente directe',
    icon: 'store',
    section: 'work',
    mobile: 'secondary',
    mobileOrder: 6,
  },
  {
    path: '/app/orders',
    label: 'Commandes',
    shortLabel: 'Commandes',
    icon: 'orders',
    section: 'work',
    mobile: 'primary',
    mobileOrder: 2,
  },
  {
    path: '/app/products',
    label: 'Produits',
    shortLabel: 'Produits',
    icon: 'box',
    section: 'work',
    mobile: 'primary',
    mobileOrder: 3,
  },
  {
    path: '/app/paiements',
    label: 'Paiements',
    shortLabel: 'Paiements',
    icon: 'card',
    section: 'finance',
    mobile: 'secondary',
    mobileOrder: 1,
  },
  {
    path: '/app/versements',
    label: 'Mes versements',
    shortLabel: 'Versements',
    icon: 'card',
    section: 'finance',
    mobile: 'secondary',
    mobileOrder: 2,
  },
  {
    path: '/app/statistiques',
    label: 'Statistiques',
    shortLabel: 'Statistiques',
    icon: 'trend',
    section: 'finance',
    mobile: 'secondary',
    mobileOrder: 3,
  },
  {
    path: '/app/boutique',
    label: 'Ma boutique',
    shortLabel: 'Ma boutique',
    icon: 'store',
    section: 'shop',
    mobile: 'secondary',
    mobileOrder: 4,
  },
  {
    path: '/app/abonnement',
    label: 'Abonnement',
    shortLabel: 'Abonnement',
    icon: 'card',
    section: 'shop',
    mobile: 'secondary',
    mobileOrder: 5,
  },
] as const;

export const navigationSections = [
  { id: 'work', label: 'Espace de travail' },
  { id: 'finance', label: 'Finances et analyse' },
  { id: 'shop', label: 'Boutique' },
] as const;

export const sidebarNavigation = navigationSections.map((section) => ({
  ...section,
  items: navigation.filter((item) => item.section === section.id),
}));

export const primaryMobileNavigation = navigation
  .filter((item) => item.mobile === 'primary')
  .sort((a, b) => a.mobileOrder - b.mobileOrder);
export const secondaryMobileNavigation = navigation
  .filter((item) => item.mobile === 'secondary')
  .sort((a, b) => a.mobileOrder - b.mobileOrder);

export const mobileMenuSections = navigationSections
  .map((section) => ({
    ...section,
    items: secondaryMobileNavigation.filter((item) => item.section === section.id),
  }))
  .filter((section) => section.items.length > 0);
