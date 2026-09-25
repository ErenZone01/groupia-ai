import { money, safePrice } from './display';
describe('Montants affichés', () => {
  it('ne transforme pas un tarif absent en zéro', () => expect(money(undefined)).toBe('—'));
  it('conserve les décimales autorisées pour un tarif de livraison', () =>
    expect(money(12.5)).toContain('12,5'));
  it('ne présente pas un grand montant arrondi comme exact', () =>
    expect(money(Number.MAX_SAFE_INTEGER + 1)).toBe('Montant indisponible'));
  it('accepte zéro mais refuse un prix décimal ou hors précision', () => {
    expect(safePrice(0)).toBe(true);
    expect(safePrice(12.5)).toBe(false);
    expect(safePrice(Number.MAX_SAFE_INTEGER + 1)).toBe(false);
  });
});
