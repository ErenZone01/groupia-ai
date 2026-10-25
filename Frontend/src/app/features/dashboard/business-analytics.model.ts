import { BusinessAnalyticsView } from '../../api/generated/models/business-analytics-view';

export type AnalyticsPeriod = 3 | 6 | 12;
const count = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) >= 0;

// JSON numbers outside the safe integer range have already lost precision. Never display
// a rounded financial value as exact; require a decimal-string API contract before supporting it.
export function validateAnalytics(view: BusinessAnalyticsView, period: AnalyticsPeriod) {
  const date = (value?: string) => !!value && Number.isFinite(Date.parse(value));
  const variation = (value: unknown) =>
    value == null ||
    (typeof value === 'number' &&
      Number.isFinite(value) &&
      Math.abs(value) <= Number.MAX_SAFE_INTEGER);
  if (
    view.currency !== 'XOF' ||
    view.timezone !== 'Africa/Dakar' ||
    !date(view.periodStart) ||
    !date(view.periodEndExclusive) ||
    !date(view.comparisonStart) ||
    !date(view.comparisonEndExclusive) ||
    ![
      view.goodsSales,
      view.confirmedOrders,
      view.previousYearGoodsSales,
      view.previousYearConfirmedOrders,
    ].every(count) ||
    (view.averageBasket != null && !count(view.averageBasket)) ||
    !variation(view.goodsSalesChangePercent) ||
    !variation(view.confirmedOrdersChangePercent) ||
    view.months?.length !== period ||
    view.months.some(
      (p) =>
        !/^\d{4}-(0[1-9]|1[0-2])$/.test(p.month ?? '') ||
        ![
          p.goodsSales,
          p.confirmedOrders,
          p.previousYearGoodsSales,
          p.previousYearConfirmedOrders,
        ].every(count),
    )
  ) {
    throw new Error('ANALYTICS_PRECISION_OR_CONTRACT');
  }
  if (new Set(view.months.map((p) => p.month)).size !== period)
    throw new Error('ANALYTICS_CONTRACT');
  return view;
}

export function francs(value: number | null | undefined) {
  return count(value) ? new Intl.NumberFormat('fr-FR').format(value) + ' FCFA' : '—';
}
export function quantity(value: number | null | undefined) {
  return count(value) ? new Intl.NumberFormat('fr-FR').format(value) : '—';
}
export function changeLabel(value: number | null | undefined) {
  if (value == null) return 'Comparaison indisponible';
  return (
    new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1, signDisplay: 'exceptZero' }).format(
      value,
    ) + ' %'
  );
}
export function monthLabel(value?: string, short = false) {
  return value
    ? new Intl.DateTimeFormat('fr-FR', {
        month: short ? 'short' : 'long',
        ...(short ? {} : { year: 'numeric' as const }),
        timeZone: 'Africa/Dakar',
      }).format(new Date(value + '-01T00:00:00Z'))
    : '';
}
export function periodLabel(start?: string, end?: string) {
  if (!start || !end) return '';
  const format = new Intl.DateTimeFormat('fr-FR', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'Africa/Dakar',
  });
  return format.format(new Date(start)) + ' – ' + format.format(new Date(end));
}
