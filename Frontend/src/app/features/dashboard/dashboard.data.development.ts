import { DashboardView } from './dashboard.model';
// Development-only visual fixture; replaced out of production, never sent over HTTP.
export const dashboardData: DashboardView | null = {
  firstName: 'Awa',
  stats: [38, 32, 4, 2],
  sales: '287 500',
  salesTrend: '12 % cette semaine',
  bars: [32, 44, 41, 76, 54, 68, 94],
  campaign: {
    title: 'Collection chemises',
    subtitle: 'Septembre 2026',
    count: 32,
    target: 40,
    progress: 80,
    deadline: 'Fin dans 5 jours',
  },
  activity: [
    {
      initials: 'FN',
      name: 'Fatou Ndiaye',
      detail: 'Commande de 3 articles',
      amount: '15 300',
      status: 'Payée',
      time: 'Il y a 12 min',
    },
    {
      initials: 'KS',
      name: 'Khady Sow',
      detail: 'Commande de 2 articles',
      amount: '10 300',
      status: 'En attente',
      time: 'Il y a 35 min',
    },
    {
      initials: 'MF',
      name: 'Mariam Fall',
      detail: 'Commande de 1 article',
      amount: '5 300',
      status: 'Payée',
      time: 'Il y a 1 h',
    },
  ],
};
