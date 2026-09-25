export interface DashboardView {
  firstName: string;
  stats: readonly number[];
  sales: string;
  salesTrend: string;
  bars: readonly number[];
  campaign: {
    title: string;
    subtitle: string;
    count: number;
    target: number;
    progress: number;
    deadline: string;
  };
  activity: readonly {
    initials: string;
    name: string;
    detail: string;
    amount: string;
    status: 'Payée' | 'En attente';
    time: string;
  }[];
}
