// Synthetic values for automated tests and explicitly labelled review captures only.
export function analyticsFixture(count = 12, empty = false) {
  const current = [0, 12000, 24000, 18000, 42000, 38000, 65000, 54000, 78000, 92000, 86000, 124000];
  const previous = [0, 8000, 14000, 20000, 26000, 32000, 44000, 46000, 52000, 62000, 72000, 84000];
  const months = current.slice(-count).map((value, i) => {
    const index = 12 - count + i;
    return {
      month: `2026-${String(index + 1).padStart(2, '0')}`,
      goodsSales: empty ? 0 : value,
      confirmedOrders: empty || !value ? 0 : 4 + index,
      previousYearGoodsSales: empty ? 0 : previous[index],
      previousYearConfirmedOrders: empty || !previous[index] ? 0 : 3 + index,
    };
  });
  const goodsSales = months.reduce((sum, p) => sum + p.goodsSales, 0);
  const confirmedOrders = months.reduce((sum, p) => sum + p.confirmedOrders, 0);
  const previousYearGoodsSales = months.reduce((sum, p) => sum + p.previousYearGoodsSales, 0);
  const previousYearConfirmedOrders = months.reduce(
    (sum, p) => sum + p.previousYearConfirmedOrders,
    0,
  );
  return {
    currency: 'XOF',
    timezone: 'Africa/Dakar',
    periodStart: `2026-${String(13 - count).padStart(2, '0')}-01T00:00:00Z`,
    periodEndExclusive: '2026-12-21T10:00:00Z',
    comparisonStart: `2025-${String(13 - count).padStart(2, '0')}-01T00:00:00Z`,
    comparisonEndExclusive: '2025-12-21T10:00:00Z',
    goodsSales,
    confirmedOrders,
    averageBasket: confirmedOrders ? Math.round(goodsSales / confirmedOrders) : null,
    previousYearGoodsSales,
    previousYearConfirmedOrders,
    goodsSalesChangePercent: previousYearGoodsSales
      ? (100 * (goodsSales - previousYearGoodsSales)) / previousYearGoodsSales
      : null,
    confirmedOrdersChangePercent: previousYearConfirmedOrders
      ? (100 * (confirmedOrders - previousYearConfirmedOrders)) / previousYearConfirmedOrders
      : null,
    months,
  };
}
