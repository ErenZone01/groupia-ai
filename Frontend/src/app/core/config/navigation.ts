export const navigation = [
  { path: '/app/accueil', label: 'Tableau de bord', shortLabel: 'Accueil', icon: 'home' },
  { path: '/app/groupages', label: 'Groupages', shortLabel: 'Groupages', icon: 'group' },
  { path: '/app/products', label: 'Produits', shortLabel: 'Produits', icon: 'box' },
  { path: '/app/orders', label: 'Commandes', shortLabel: 'Commandes', icon: 'orders' },
  { path: '/app/paiements', label: 'Paiements', shortLabel: 'Paiements', icon: 'card' },
  { path: '/app/boutique', label: 'Ma boutique', shortLabel: 'Boutique', icon: 'store' },
] as const;
export const mobileNavigation = [navigation[0], navigation[1], navigation[3], navigation[5]];
