import { InjectionToken } from '@angular/core';
import { OrderShippingRow, ShippingAssessmentView } from '../../api/generated/models';
export const SHIPPING_VARIANCE_WARNING_PERCENT = new InjectionToken<number>(
  'Shipping variance warning percent',
  { providedIn: 'root', factory: () => 20 },
);
export const currentCalculation = (a?: ShippingAssessmentView) =>
  a?.calculations?.find((c) => c.id === a.currentFinalCalculationId);
export const currentMeasurement = (a?: ShippingAssessmentView) =>
  a?.measurements?.find((m) => m.id === a.currentMeasurementId)?.information;
export function variance(estimated?: number, actual?: number) {
  if (estimated == null || actual == null) return null;
  const difference = actual - estimated;
  return { difference, percent: estimated > 0 ? (difference / estimated) * 100 : null };
}
export function shippingTotals(rows: OrderShippingRow[]) {
  const finals = rows.map((r) => currentCalculation(r.assessment)).filter((c) => !!c);
  const sum = (values: (number | undefined)[]) => {
    const known = values.filter((n): n is number => n != null && Number.isFinite(n));
    return {
      value: known.length ? known.reduce((a, b) => a + b, 0) : undefined,
      count: known.length,
    };
  };
  return {
    count: rows.length,
    finalized: finals.length,
    remaining: rows.length - finals.length,
    weight: sum(rows.map((r) => currentMeasurement(r.assessment)?.actualWeightKg)),
    chargeable: sum(
      rows.map(
        (r) =>
          currentMeasurement(r.assessment)?.chargeableWeightKg ??
          (currentCalculation(r.assessment)?.explanation?.billableUnit === 'KG'
            ? currentCalculation(r.assessment)?.explanation?.billableQuantity
            : undefined),
      ),
    ),
    volume: sum(
      rows.map(
        (r) =>
          currentMeasurement(r.assessment)?.volumeCbm ??
          (currentCalculation(r.assessment)?.explanation?.billableUnit === 'CBM'
            ? currentCalculation(r.assessment)?.explanation?.billableQuantity
            : undefined),
      ),
    ),
    amount: sum(finals.map((c) => c.explanation?.amount)),
  };
}
