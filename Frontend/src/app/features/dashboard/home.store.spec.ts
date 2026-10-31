import { signal, type WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideApiConfiguration } from '../../api/generated/api-configuration';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { HomeStore, priorityLabel } from './home.store';

describe('Accueil : requêtes bornées et contexte', () => {
  let selectedId: WritableSignal<string | null>;
  let signedIn: WritableSignal<boolean>;
  let generation: WritableSignal<number>;
  let store: HomeStore, http: HttpTestingController;
  beforeEach(() => {
    // Fresh signals for every test: a failed HTTP expectation must not leave
    // an effect from a previous HomeStore linked to a subsequent TestBed.
    selectedId = signal<string | null>('a');
    signedIn = signal(true);
    generation = signal(1);
    TestBed.configureTestingModule({
      providers: [
        HomeStore,
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApiConfiguration(''),
        { provide: AuthService, useValue: { signedIn, generation } },
        { provide: BoutiqueContextService, useValue: { selectedId } },
      ],
    });
    store = TestBed.inject(HomeStore);
    http = TestBed.inject(HttpTestingController);
    TestBed.tick();
    // The personalized greeting loads independently of the active boutique.
    http.expectOne('/api/auth/me').flush({ firstName: 'Awa', lastName: 'Diop' });
  });
  afterEach(() => {
    try {
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });
  const groupRequest = (org = 'a') =>
    http.expectOne(`/api/organizations/${org}/grouping-campaigns?page=0&size=20`);
  const ordersRequest = (org = 'a') =>
    http.expectOne(`/api/organizations/${org}/orders?page=0&size=3`);
  const populate = () => {
    groupRequest().flush({
      totalElements: 1,
      items: [{ id: 'draft', title: 'Ancienne boutique', status: 'DRAFT' }],
    });
    ordersRequest().flush({ items: [{ id: 'order' }] });
  };
  it('affiche le profil du compte sans le répéter lors du changement de boutique', () => {
    expect(store.profile.value()?.firstName).toBe('Awa');
    populate();
    selectedId.set('b');
    TestBed.tick();
    groupRequest('b').flush({ totalElements: 0, items: [] });
    ordersRequest('b').flush({ items: [] });
    TestBed.tick();
    http.expectOne('/api/organizations/b/products?page=0&size=1')
      .flush({ totalElements: 0, items: [] });
    expect(store.profile.value()?.firstName).toBe('Awa');
    http.expectNone('/api/auth/me');
  });

  it('borne les listes, sans catalogue complet ni comptage par statut', () => {
    populate();
    TestBed.tick();
    http.expectNone(() => true);
    expect(store.priorities()).toHaveLength(1);
    expect(store.recent()).toHaveLength(1);
  });
  it('ne répète pas les priorités dans les autres groupages, ignore les terminés', () => {
    groupRequest().flush({
      totalElements: 5,
      items: [
        { id: '1', status: 'OPEN', publicIdentifier: 'p' },
        { id: '2', status: 'DRAFT' },
        { id: '3', status: 'IN_TRANSIT' },
        { id: '4', status: 'COMPLETED' },
        { id: '5', status: 'CANCELLED' },
      ],
    });
    ordersRequest().flush({ items: [] });
    expect(store.priorities().map((g) => g.id)).toEqual(['2', '1']);
    expect(store.otherGroups().map((g) => g.id)).toEqual(['3']);
  });
  it('ne consulte un seul produit que si le total des groupages est exactement zéro', () => {
    groupRequest().flush({ totalElements: 0, items: [] });
    ordersRequest().flush({ items: [] });
    TestBed.tick();
    http
      .expectOne('/api/organizations/a/products?page=0&size=1')
      .flush({ totalElements: 0, items: [] });
    expect(store.noGroups()).toBe(true);
    expect(store.products.value()?.totalElements).toBe(0);
  });
  it('une page vide parmi plusieurs ne déclenche pas un onboarding', () => {
    groupRequest().flush({ totalElements: 25, items: [] });
    ordersRequest().flush({ items: [] });
    TestBed.tick();
    expect(store.noGroups()).toBe(false);
    http.expectNone(() => true);
  });
  it('les erreurs sont indépendantes et ne deviennent jamais des zéros', () => {
    groupRequest().flush({}, { status: 503, statusText: 'Unavailable' });
    ordersRequest().flush({ items: [{ id: 'order' }] });
    TestBed.tick();
    expect(store.groups.error()).toBe(true);
    expect(store.noGroups()).toBe(false);
    expect(store.recent()).toHaveLength(1);
    http.expectNone(() => true);
    store.groups.retry();
    TestBed.tick();
    groupRequest().flush({ totalElements: 1, items: [] });
    expect(store.groups.error()).toBe(false);
    http.expectNone(() => true);
  });
  it('masque immédiatement les données lors du changement de boutique', () => {
    populate();
    selectedId.set('b');
    expect(store.groups.value()).toBeNull();
    expect(store.recent()).toEqual([]);
    TestBed.tick();
    groupRequest('b').flush({ totalElements: 1, items: [{ id: 'b', status: 'OPEN' }] });
    ordersRequest('b').flush({ items: [] });
    expect(store.otherGroups()[0].id).toBe('b');
  });
  it('annule les réponses tardives lors des changements rapides', () => {
    const oldGroup = groupRequest(),
      oldOrders = ordersRequest();
    selectedId.set('b');
    TestBed.tick();
    expect(oldGroup.cancelled).toBe(true);
    expect(oldOrders.cancelled).toBe(true);
    groupRequest('b').flush({ totalElements: 1, items: [] });
    ordersRequest('b').flush({ items: [] });
  });
  it.each(['logout', 'expiration', 'generation'])('efface et annule le contexte : %s', (action) => {
    const oldGroup = groupRequest(),
      oldOrders = ordersRequest();
    if (action === 'generation') generation.set(2);
    else signedIn.set(false);
    expect(store.groups.value()).toBeNull();
    expect(store.orders.value()).toBeNull();
    TestBed.tick();
    expect(oldGroup.cancelled).toBe(true);
    expect(oldOrders.cancelled).toBe(true);
    if (action === 'generation') {
      http.expectOne('/api/auth/me').flush({ firstName: 'Awa', lastName: 'Diop' });
      groupRequest().flush({ totalElements: 1, items: [] });
      ordersRequest().flush({ items: [] });
    } else http.expectNone(() => true);
  });
  it('annule aussi la vérification catalogue au changement de contexte', () => {
    groupRequest().flush({ totalElements: 0, items: [] });
    ordersRequest().flush({ items: [] });
    TestBed.tick();
    const pending = http.expectOne('/api/organizations/a/products?page=0&size=1');
    selectedId.set(null);
    TestBed.tick();
    expect(pending.cancelled).toBe(true);
    expect(store.products.value()).toBeNull();
  });
  it('ne propose pas une distribution autorisée sans preuve ni un partage sans lien', () => {
    populate();
    for (const status of ['ARRIVED', 'DISTRIBUTING', 'COMPLETED', 'PENDING_PAYMENT', 'UNKNOWN'])
      expect(priorityLabel({ id: 'x', status })).toBeNull();
    expect(priorityLabel({ id: 'x', status: 'OPEN' })).toBeNull();
    expect(priorityLabel({ id: 'x', status: 'OPEN', publicIdentifier: 'public' })).toBe(
      'Partager le groupage',
    );
    expect(priorityLabel({ status: 'DRAFT' })).toBeNull();
  });
});
