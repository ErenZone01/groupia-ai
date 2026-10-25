import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { apiTestProviders } from '../../testing/api-test';
import { OrdersStore } from './orders.store';
import { OrdersDashboardStore } from '../dashboard/orders-dashboard.store';
import { OrderDetailPage } from './order-detail.page';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { orderFilter, orderPage, orderLabel, orderTone, paymentLabel } from './order-labels';
import { money } from '../../shared/utils/display';
import { AuthService } from '../../core/auth/auth.service';
describe('Libellés et filtres de commandes', () => {
  for (const [status, label] of [
    ['CONFIRMED', 'Payée'],
    ['PENDING_PAYMENT', 'En attente'],
    ['EXPIRED', 'Expirée'],
    ['CANCELLED', 'Annulée'],
    ['COMPLETED', 'Terminée'],
  ]) {
    it(status + ' a un libellé traduit', () => expect(orderLabel(status)).toBe(label));
  }
  it('un état inconnu ne devient jamais payé', () => {
    expect(orderLabel('UNKNOWN')).toBe('Statut indisponible');
    expect(orderTone('PENDING_PAYMENT')).toBe('warning');
    expect(paymentLabel('PENDING_PAYMENT')).toBe('En attente de paiement');
  });
  it('borne les filtres et la pagination', () => {
    expect(orderFilter('CONFIRMED')).toBe('CONFIRMED');
    expect(orderFilter('OTHER')).toBe('');
    expect(orderPage('-1')).toBe(0);
    expect(orderPage('1.5')).toBe(0);
    expect(orderPage('2')).toBe(2);
  });
  it('formate XOF sans modifier le montant', () => {
    expect(money(15300, 'XOF').replace(/\s/g, '')).toBe('15300FCFA');
    expect(money(undefined)).toBe('—');
  });
});
describe('Lecture paginée des commandes', () => {
  let http: HttpTestingController;
  let store: OrdersStore;
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        ...apiTestProviders(),
        OrdersStore,
        OrdersDashboardStore,
        { provide: BoutiqueContextService, useValue: { selectedId: signal('shop') } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(OrdersStore);
    TestBed.inject(AuthService).acceptSession('test-session', 900);
  });
  afterEach(() => http.verify());
  it('affiche loading puis une page sans charger chaque détail', () => {
    store.load('shop');
    expect(store.loading()).toBe(true);
    const req = http.expectOne((r) => r.url === '/api/organizations/shop/orders');
    expect(req.request.params.get('size')).toBe('20');
    req.flush({
      items: [{ id: '1', orderNumber: 'GRP-1', status: 'CONFIRMED' }],
      page: 0,
      totalPages: 100,
      totalElements: 2000,
    });
    expect(store.items()).toHaveLength(1);
    expect(store.loading()).toBe(false);
    http.expectNone((r) => r.url.endsWith('/orders/1'));
  });
  it('filtre et pagine côté serveur avec campaignId exact', () => {
    store.load('shop', { campaignId: 'campaign', status: 'CONFIRMED', page: 2 });
    const req = http.expectOne((r) => r.url === '/api/organizations/shop/orders');
    expect(req.request.params.get('campaignId')).toBe('campaign');
    expect(req.request.params.get('status')).toBe('CONFIRMED');
    expect(req.request.params.get('page')).toBe('2');
    req.flush({ items: [], page: 2, totalPages: 3, totalElements: 41 });
    expect(store.data()?.page).toBe(2);
  });
  it('recherche uniquement dans la page chargée, sans appel supplémentaire', () => {
    store.load('shop');
    http
      .expectOne((r) => r.url.endsWith('/orders'))
      .flush({ items: [{ orderNumber: 'GRP-1' }, { orderNumber: 'GRP-2' }], totalElements: 50 });
    store.search.set('grp-2');
    expect(store.items()).toEqual([{ orderNumber: 'GRP-2' }]);
    expect(store.data()?.totalElements).toBe(50);
  });
  it('gère une liste vide', () => {
    store.load('shop');
    http
      .expectOne((r) => r.url.endsWith('/orders'))
      .flush({ items: [], totalElements: 0, totalPages: 0 });
    expect(store.items()).toEqual([]);
    expect(store.error()).toBe('');
  });
  it('gère erreur et retry sans exposer le détail serveur', () => {
    store.load('shop');
    http
      .expectOne((r) => r.url.endsWith('/orders'))
      .flush({ detail: 'SQL secret' }, { status: 500, statusText: 'Error' });
    expect(store.loading()).toBe(false);
    expect(store.error()).not.toContain('SQL');
    expect(store.data()).toBeNull();
    store.load('shop');
    http.expectOne((r) => r.url.endsWith('/orders')).flush({ items: [] });
    expect(store.error()).toBe('');
  });
  it('annule la réponse de l’ancienne boutique', () => {
    store.load('old');
    const old = http.expectOne((r) => r.url.includes('/old/'));
    store.load('new');
    expect(old.cancelled).toBe(true);
    http.expectOne((r) => r.url.includes('/new/')).flush({ items: [{ id: 'new' }] });
    expect(store.items()[0].id).toBe('new');
  });
  it('annule une page dépassée lors du changement de filtre', () => {
    store.load('shop', { page: 2 });
    const old = http.expectOne((r) => r.params.get('page') === '2');
    store.load('shop', { status: 'CONFIRMED' });
    expect(old.cancelled).toBe(true);
    http
      .expectOne((r) => r.params.get('status') === 'CONFIRMED')
      .flush({ items: [{ status: 'CONFIRMED' }] });
    expect(store.items()[0].status).toBe('CONFIRMED');
  });
  it('utilise les totalElements pour les compteurs, pas les lignes chargées', () => {
    const dashboard = TestBed.inject(OrdersDashboardStore);
    dashboard.load('shop');
    const requests = http.match((r) => r.url.endsWith('/orders'));
    expect(requests).toHaveLength(4);
    for (const req of requests) {
      const status = req.request.params.get('status');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe(status ? '1' : '5');
      req.flush({
        items: [{ id: 'one' }],
        totalElements:
          status === 'CONFIRMED'
            ? 30
            : status === 'PENDING_PAYMENT'
              ? 10
              : status === 'EXPIRED'
                ? 5
                : 100,
        totalPages: 100,
      });
    }
    expect(dashboard.stats()).toEqual([100, 30, 10, 5]);
    expect(dashboard.recent()).toHaveLength(1);
  });
  it('ne transforme pas une erreur de métrique en zéro', () => {
    const dashboard = TestBed.inject(OrdersDashboardStore);
    dashboard.load('shop');
    const requests = http.match((r) => r.url.endsWith('/orders'));
    requests[0].flush({}, { status: 403, statusText: 'Forbidden' });
    expect(dashboard.stats()).toEqual([null, null, null, null]);
    expect(dashboard.error()).not.toBe('');
    expect(requests.slice(1).every((r) => r.cancelled)).toBe(true);
  });
});
describe('Détail historique de commande', () => {
  let http: HttpTestingController;
  const params = new BehaviorSubject(convertToParamMap({ orderId: 'order' }));
  beforeEach(() => {
    sessionStorage.clear();
    params.next(convertToParamMap({ orderId: 'order' }));
    const query = convertToParamMap({ campaignId: 'campaign', status: 'CONFIRMED', page: '2' });
    TestBed.configureTestingModule({
      providers: [
        ...apiTestProviders(),
        { provide: BoutiqueContextService, useValue: { selectedId: signal('shop') } },
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: params,
            queryParamMap: new BehaviorSubject(query),
            snapshot: { paramMap: params.value, queryParamMap: query },
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('affiche les snapshots, le contact et les montants sans lecture du catalogue', () => {
    const fixture = TestBed.createComponent(OrderDetailPage);
    fixture.detectChanges();
    http.expectOne('/api/organizations/shop/orders/order').flush({
      id: 'order',
      campaignId: 'campaign',
      customerContactSnapshot: { firstName: 'Awa', lastName: 'Diop', phoneE164: '+221771234567' },
      order: {
        orderNumber: 'GRP-1',
        status: 'CONFIRMED',
        goodsSubtotal: 10000,
        platformFeeAmount: 300,
        initialAmountDue: 10300,
        currency: 'XOF',
        items: [
          {
            lineNumber: 1,
            productNameSnapshot: 'Ancien nom',
            productDescriptionSnapshot: 'Description achetée',
            variantAttributesSnapshot: { color: 'Blanc', size: 'M' },
            quantity: 2,
            unitPriceSnapshot: 5000,
            lineTotal: 10000,
          },
        ],
      },
    });
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Ancien nom');
    expect(text).toContain('Description achetée');
    expect(text).toContain('Awa');
    expect(text).toContain('Paiement confirmé');
    expect(text).not.toContain('Marquer comme payé');
    expect(fixture.componentInstance.backLink()).toEqual(['/app/groupages', 'campaign']);
  });
  it('un détail en attente ne montre jamais Paiement confirmé', () => {
    const fixture = TestBed.createComponent(OrderDetailPage);
    fixture.detectChanges();
    http
      .expectOne('/api/organizations/shop/orders/order')
      .flush({ order: { orderNumber: 'GRP-2', status: 'PENDING_PAYMENT' } });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('En attente de paiement');
    expect(fixture.nativeElement.textContent).not.toContain('Paiement confirmé');
  });
  it('un détail introuvable affiche une erreur avec retry', () => {
    const fixture = TestBed.createComponent(OrderDetailPage);
    fixture.detectChanges();
    http
      .expectOne('/api/organizations/shop/orders/order')
      .flush({}, { status: 404, statusText: 'Not found' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('introuvable');
    expect(fixture.nativeElement.textContent).toContain('Réessayer');
  });
});
