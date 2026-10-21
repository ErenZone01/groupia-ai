import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideApiConfiguration } from '../../api/generated/api-configuration';
import { BusinessAnalyticsView } from '../../api/generated/models/business-analytics-view';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { BusinessAnalyticsStore } from './business-analytics.store';
import { changeLabel, francs, validateAnalytics } from './business-analytics.model';
import { SalesChart } from './sales-chart';

function fixture(count = 12): BusinessAnalyticsView {
  return {
    currency: 'XOF',
    timezone: 'Africa/Dakar',
    periodStart: '2026-01-01T00:00:00Z',
    periodEndExclusive: '2026-12-21T10:00:00Z',
    comparisonStart: '2025-01-01T00:00:00Z',
    comparisonEndExclusive: '2025-12-21T10:00:00Z',
    goodsSales: 6000,
    confirmedOrders: 3,
    averageBasket: 2000,
    previousYearGoodsSales: 3000,
    previousYearConfirmedOrders: 3,
    goodsSalesChangePercent: 100,
    confirmedOrdersChangePercent: 0,
    months: Array.from({ length: count }, (_, i) => ({
      month: `2026-${String(13 - count + i).padStart(2, '0')}`,
      goodsSales: i === count - 1 ? 6000 : 0,
      confirmedOrders: i === count - 1 ? 3 : 0,
      previousYearGoodsSales: i === count - 1 ? 3000 : 0,
      previousYearConfirmedOrders: i === count - 1 ? 3 : 0,
    })),
  };
}

describe('Statistiques commerciales : isolation et requêtes', () => {
  let store: BusinessAnalyticsStore, http: HttpTestingController;
  const selectedId = signal<string | null>('a'),
    signedIn = signal(true),
    generation = signal(1);
  beforeEach(() => {
    selectedId.set('a');
    signedIn.set(true);
    generation.set(1);
    TestBed.configureTestingModule({
      providers: [
        BusinessAnalyticsStore,
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApiConfiguration(''),
        { provide: AuthService, useValue: { signedIn, generation } },
        { provide: BoutiqueContextService, useValue: { selectedId } },
      ],
    });
    store = TestBed.inject(BusinessAnalyticsStore);
    http = TestBed.inject(HttpTestingController);
    TestBed.tick();
  });
  afterEach(() => http.verify());
  const request = (org = 'a', months = 12) =>
    http.expectOne(`/api/organizations/${org}/analytics/business?months=${months}`);
  it('charge une seule agrégation, sans télécharger les commandes', () => {
    expect(store.loading()).toBe(true);
    request().flush(fixture());
    expect(store.data()?.goodsSales).toBe(6000);
    expect(store.loading()).toBe(false);
    TestBed.tick();
    http.expectNone(() => true);
  });
  for (const months of [3, 6, 12] as const)
    it(`consomme la période ${months} sans recalculer les montants`, () => {
      request().flush(fixture());
      store.period.set(months);
      TestBed.tick();
      if (months !== 12) request('a', months).flush(fixture(months));
      expect(store.data()?.months).toHaveLength(months);
      expect(store.data()?.averageBasket).toBe(2000);
    });
  it('efface immédiatement les données et annule une période obsolète', () => {
    request().flush(fixture());
    store.period.set(3);
    expect(store.data()).toBeNull();
    TestBed.tick();
    const stale = request('a', 3);
    store.period.set(6);
    TestBed.tick();
    expect(stale.cancelled).toBe(true);
    request('a', 6).flush(fixture(6));
    expect(store.data()?.months).toHaveLength(6);
  });
  it('ne conserve aucune donnée de la boutique précédente avant même l’effet', () => {
    request().flush(fixture());
    selectedId.set('b');
    expect(store.data()).toBeNull();
    TestBed.tick();
    const stale = request('b');
    selectedId.set('c');
    TestBed.tick();
    expect(stale.cancelled).toBe(true);
    request('c').flush({ ...fixture(), goodsSales: 42 });
    expect(store.data()?.goodsSales).toBe(42);
  });
  for (const end of ['logout', 'expiration', 'generation'])
    it(`annule et efface lors de ${end}`, () => {
      const stale = request();
      if (end === 'generation') generation.update((x) => x + 1);
      else signedIn.set(false);
      expect(store.data()).toBeNull();
      TestBed.tick();
      expect(stale.cancelled).toBe(true);
      if (end === 'generation') request().flush(fixture());
      else http.expectNone(() => true);
    });
  it('vide les chiffres déjà affichés à la déconnexion', () => {
    request().flush(fixture());
    signedIn.set(false);
    expect(store.data()).toBeNull();
    TestBed.tick();
    http.expectNone(() => true);
  });
  it('distingue erreur, retry et succès sans conserver de faux zéro', () => {
    request().flush({}, { status: 503, statusText: 'Unavailable' });
    expect(store.error()).toContain('indisponibles');
    expect(store.data()).toBeNull();
    store.retry();
    TestBed.tick();
    expect(store.loading()).toBe(true);
    request().flush(fixture());
    expect(store.error()).toBe('');
  });
  it('refuse une somme que JSON a rendue imprécise', () => {
    request().flush({ ...fixture(), goodsSales: Number.MAX_SAFE_INTEGER + 1 });
    expect(store.data()).toBeNull();
    expect(store.error()).toContain('précision');
  });
  it('ne requête pas sans boutique sélectionnée', () => {
    const stale = request();
    selectedId.set(null);
    TestBed.tick();
    expect(stale.cancelled).toBe(true);
    expect(store.loading()).toBe(false);
    http.expectNone(() => true);
  });
});

describe('Contrat financier et libellés', () => {
  it('conserve zéro et moyenne absente sans inventer une comparaison', () => {
    const view = {
      ...fixture(3),
      goodsSales: 0,
      confirmedOrders: 0,
      averageBasket: undefined,
      goodsSalesChangePercent: undefined,
    };
    expect(validateAnalytics(view, 3)).toBe(view);
    expect(francs(0)).toBe('0 FCFA');
    expect(francs(null)).toBe('—');
    expect(changeLabel(null)).toBe('Comparaison indisponible');
  });
  it('affiche les variations positives, négatives et nulles avec le signe correct', () => {
    expect(changeLabel(12.5)).toBe('+12,5 %');
    expect(changeLabel(-25)).toBe('-25 %');
    expect(changeLabel(0)).toBe('0 %');
  });
  it('préserve exactement la plus grande valeur entière sûre', () => {
    expect(francs(Number.MAX_SAFE_INTEGER).replace(/\s/g, '')).toBe('9007199254740991FCFA');
    expect(francs(Number.MAX_SAFE_INTEGER + 1)).toBe('—');
  });
  for (const field of [
    'goodsSales',
    'confirmedOrders',
    'previousYearGoodsSales',
    'previousYearConfirmedOrders',
    'averageBasket',
  ])
    it(`rejette ${field} imprécis`, () => {
      expect(() =>
        validateAnalytics({ ...fixture(3), [field]: Number.MAX_SAFE_INTEGER + 1 }, 3),
      ).toThrow();
    });
  it('rejette devise, périodes et points incohérents', () => {
    expect(() => validateAnalytics({ ...fixture(3), currency: 'EUR' }, 3)).toThrow();
    expect(() => validateAnalytics(fixture(3), 12)).toThrow();
    const view = fixture(3);
    view.months![0].goodsSales = 0.1;
    expect(() => validateAnalytics(view, 3)).toThrow();
  });
});

describe('Graphique de ventes : valeurs exactes et sélection', () => {
  it('trace les deux séries, expose les données et navigue au clavier', () => {
    const f = TestBed.createComponent(SalesChart);
    f.componentRef.setInput('months', fixture(3).months);
    f.detectChanges();
    const c = f.componentInstance;
    expect(c.maximum()).toBe(6000);
    expect(c.y(6000)).toBe(24);
    expect(c.y(3000)).toBe(124);
    expect(c.selected()?.goodsSales).toBe(6000);
    c.keydown(new KeyboardEvent('keydown', { key: 'Home' }));
    expect(c.selectedIndex()).toBe(0);
    c.keydown(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    expect(c.selectedIndex()).toBe(1);
    c.keydown(new KeyboardEvent('keydown', { key: 'End' }));
    expect(c.selectedIndex()).toBe(2);
    f.detectChanges();
    expect(f.nativeElement.textContent.replace(/\s/g, '')).toContain('6000FCFA');
    expect(f.nativeElement.querySelectorAll('tbody tr')).toHaveLength(3);
  });
  it('garde une échelle finie pour zéro et un faible volume', () => {
    const f = TestBed.createComponent(SalesChart);
    f.componentRef.setInput('months', [
      { month: '2026-01', goodsSales: 0, previousYearGoodsSales: 0 },
    ]);
    f.detectChanges();
    expect(f.componentInstance.currentPath()).not.toMatch(/NaN|Infinity/);
    expect(f.componentInstance.y(0)).toBe(224);
    f.componentRef.setInput('months', [
      { month: '2026-02', goodsSales: 1, previousYearGoodsSales: 0 },
    ]);
    f.detectChanges();
    expect(f.componentInstance.maximum()).toBe(1);
    expect(f.componentInstance.y(1)).toBe(24);
  });
});
