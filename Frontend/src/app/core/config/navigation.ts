export const navigation = [
  { path: '/accueil', label: 'Tableau de bord', shortLabel: 'Accueil', icon: 'home' },
  { path: '/groupages', label: 'Groupages', shortLabel: 'Groupages', icon: 'group' },
  { path: '/catalogue', label: 'Produits', shortLabel: 'Produits', icon: 'box' },
  { path: '/commandes', label: 'Commandes', shortLabel: 'Commandes', icon: 'orders' },
  { path: '/paiements', label: 'Paiements', shortLabel: 'Paiements', icon: 'card' },
  { path: '/boutique', label: 'Ma boutique', shortLabel: 'Boutique', icon: 'store' },
] as const;
export const mobileNavigation = [navigation[0], navigation[1], navigation[3], navigation[5]];
