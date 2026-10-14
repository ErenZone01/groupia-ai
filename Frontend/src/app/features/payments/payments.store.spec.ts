import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideApiConfiguration } from '../../api/generated/api-configuration';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { paymentPage, paymentDetail, paymentReview } from '../../testing/payment-fixtures';
import type { PaymentReviewView } from '../../api/generated/models';
import { PaymentsStore } from './payments.store';

describe('PaymentsStore', () => {
  let store: PaymentsStore;
  let http: HttpTestingController;
  const org = signal<string | null>('shop'),
    generation = signal(0),
    signedIn = signal(true);
  const base = '/api/organizations/shop';
  beforeEach(() => {
    org.set('shop');
    generation.set(0);
    signedIn.set(true);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApiConfiguration(''),
        PaymentsStore,
        { provide: AuthService, useValue: { generation, signedIn } },
        { provide: BoutiqueContextService, useValue: { selectedId: org } },
      ],
    });
    store = TestBed.inject(PaymentsStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify({ ignoreCancelled: true });
    vi.useRealTimers();
  });
  function requests(shop = 'shop') {
    return http.expectOne((r) => r.url === '/api/organizations/' + shop + '/payment-requests');
  }
  function reviews(shop = 'shop') {
    return http.expectOne((r) => r.url === '/api/organizations/' + shop + '/payment-reviews');
  }
  function dashboard() {
    store.showDashboard();
    TestBed.flushEffects();
  }
  function flushDashboard() {
    requests().flush(paymentPage);
    reviews().flush({ items: [paymentReview], totalPages: 1 });
  }
  function showReview(value: PaymentReviewView = paymentReview) {
    store.showReview('review');
    TestBed.flushEffects();
    http.expectOne(base + '/payment-reviews/review').flush(value);
  }
  function finishRefresh() {
    requests().flush(paymentPage);
    reviews().flush({ items: [] });
    http.expectOne(base + '/payment-requests/request').flush(paymentDetail);
  }
  function resolved(reference = 'EXT', note?: string): PaymentReviewView {
    return {
      ...paymentReview,
      status: 'RESOLVED_REFUNDED',
      canResolve: false,
      externalRefundReference: reference,
      resolutionNote: note,
    };
  }
  it('expose le chargement puis les listes et compteurs serveur', () => {
    dashboard();
    expect(store.vm().loadingRequests).toBe(true);
    flushDashboard();
    expect(store.vm().requests).toEqual(paymentPage);
    expect(store.vm().loadingRequests).toBe(false);
    expect(store.vm().reviews?.items).toHaveLength(1);
  });
  it('gère une page vide et une erreur sans conserver les anciens montants', () => {
    dashboard();
    requests().flush({ items: [], totalElements: 0 });
    reviews().flush({ items: [] });
    expect(store.vm().requests?.items).toEqual([]);
    store.loadRequests();
    requests().flush({}, { status: 403, statusText: 'Forbidden' });
    expect(store.vm().requestsError).toContain('autorisée');
    expect(store.vm().requests).toBeNull();
  });
  it('envoie les filtres serveur, remet la page à zéro et sépare les paginations', () => {
    dashboard();
    flushDashboard();
    store.requestPage(2);
    const page = requests();
    expect(page.request.params.get('page')).toBe('2');
    page.flush(paymentPage);
    http.expectNone((r) => r.url.endsWith('/payment-reviews'));
    store.filter('SETTLED', 'SHIPPING_BALANCE');
    const filtered = requests();
    expect(filtered.request.params.get('page')).toBe('0');
    expect(filtered.request.params.get('operationalStatus')).toBe('SETTLED');
    expect(filtered.request.params.get('purpose')).toBe('SHIPPING_BALANCE');
    filtered.flush(paymentPage);
    store.reviewPage(3);
    const incidents = reviews();
    expect(incidents.request.params.get('page')).toBe('3');
    incidents.flush({ items: [] });
    http.expectNone((r) => r.url.endsWith('/payment-requests'));
    store.filterReviews('RESOLVED_REFUNDED');
    const history = reviews();
    expect(history.request.params.get('page')).toBe('0');
    expect(history.request.params.get('status')).toBe('RESOLVED_REFUNDED');
    history.flush({ items: [resolved()] });
  });
  it('charge le détail, toutes les tentatives et les reviews contextuelles', () => {
    store.showRequest('request');
    TestBed.flushEffects();
    http.expectOne(base + '/payment-requests/request').flush(paymentDetail);
    const context = reviews();
    expect(context.request.params.get('paymentRequestId')).toBe('request');
    context.flush({ items: [paymentReview] });
    expect(store.vm().detail?.attempts).toHaveLength(2);
    expect(store.vm().detail?.request?.orderId).toBe('order');
    http.expectNone((r) => r.url.endsWith('/payment-requests'));
  });
  for (const change of ['boutique', 'session'] as const) {
    it('annule les GET et masque immédiatement les données au changement ' + change, () => {
      dashboard();
      flushDashboard();
      store.loadRequests();
      const old = requests();
      if (change === 'boutique') org.set('next');
      else generation.update((n) => n + 1);
      expect(store.vm().requests).toBeNull();
      expect(store.vm().reviews).toBeNull();
      TestBed.flushEffects();
      expect(old.cancelled).toBe(true);
      const shop = change === 'boutique' ? 'next' : 'shop';
      requests(shop).flush({ items: [], summary: { pending: 7 } });
      reviews(shop).flush({ items: [] });
      expect(store.vm().requests?.summary?.pending).toBe(7);
    });
    it('ignore le résultat POST de l’ancienne ' + change + ' et efface la preuve', () => {
      showReview();
      store.resolve('EXT', 'note');
      const post = http.expectOne(base + '/payment-reviews/review/resolve-refunded');
      if (change === 'boutique') org.set('next');
      else generation.update((n) => n + 1);
      post.flush(resolved('EXT', 'note'));
      expect(store.vm().review).toBeNull();
      TestBed.flushEffects();
      const next =
        '/api/organizations/' +
        (change === 'boutique' ? 'next' : 'shop') +
        '/payment-reviews/review';
      http.expectOne(next).flush({ ...paymentReview, canResolve: false });
      expect(store.vm().resolutionState).toBe('idle');
      expect(store.vm().busy).toBe(false);
      store.retryResolution();
      http.expectNone((r) => r.method === 'POST');
    });
  }
  for (const change of ['boutique', 'session'] as const) {
    it('ignore aussi un GET livré avant le nettoyage de ' + change, () => {
      dashboard();
      const old = requests();
      reviews().flush({ items: [] });
      if (change === 'boutique') org.set('next');
      else generation.update((n) => n + 1);
      old.flush(paymentPage);
      expect(store.vm().requests).toBeNull();
      TestBed.flushEffects();
      const shop = change === 'boutique' ? 'next' : 'shop';
      requests(shop).flush({ items: [] });
      reviews(shop).flush({ items: [] });
      expect(store.vm().requests?.items).toEqual([]);
    });
  }
  it('annule le POST en vol au changement de boutique', () => {
    showReview();
    store.resolve('EXT', '');
    const post = http.expectOne(base + '/payment-reviews/review/resolve-refunded');
    org.set('next');
    TestBed.flushEffects();
    expect(post.cancelled).toBe(true);
    http
      .expectOne('/api/organizations/next/payment-reviews/review')
      .flush({ ...paymentReview, canResolve: false });
  });
  for (const capability of [false, undefined])
    it('ne résout pas sans capacité explicite ' + capability, () => {
      showReview({ ...paymentReview, canResolve: capability });
      store.resolve('EXT', '');
      http.expectNone((r) => r.method === 'POST');
    });
  it('valide la référence et les limites avant tout POST', () => {
    showReview();
    for (const [ref, note] of [
      ['  ', ''],
      ['x'.repeat(161), ''],
      ['EXT', 'x'.repeat(1001)],
    ]) {
      store.resolve(ref, note);
      expect(store.vm().resolutionError).toBeTruthy();
    }
    http.expectNone((r) => r.method === 'POST');
  });
  it('envoie uniquement la preuve, puis recharge obligation, compteurs et reviews', () => {
    showReview();
    store.resolve(' EXT ', ' note ');
    const post = http.expectOne(base + '/payment-reviews/review/resolve-refunded');
    expect(post.request.body).toEqual({ externalRefundReference: 'EXT', resolutionNote: 'note' });
    post.flush(resolved('EXT', 'note'));
    finishRefresh();
    expect(store.vm().resolutionState).toBe('done');
    expect(store.vm().review?.canResolve).toBe(false);
  });
  it('après timeout et OPEN, renvoie exactement la même preuve sans second remboursement', () => {
    vi.useFakeTimers();
    showReview();
    store.resolve(' EXT ', ' note ');
    const first = http.expectOne(base + '/payment-reviews/review/resolve-refunded');
    vi.advanceTimersByTime(20001);
    expect(first.cancelled).toBe(true);
    http.expectOne(base + '/payment-reviews/review').flush(paymentReview);
    expect(store.vm().resolutionState).toBe('retry');
    expect(store.vm().message).toContain('sans refaire');
    store.retryResolution();
    const second = http.expectOne(base + '/payment-reviews/review/resolve-refunded');
    expect(second.request.body).toEqual(first.request.body);
    second.flush(resolved('EXT', 'note'));
    finishRefresh();
    expect(store.vm().resolutionState).toBe('done');
  });
  it('après timeout et résolution correspondante, termine sans renvoyer de POST', () => {
    vi.useFakeTimers();
    showReview();
    store.resolve('EXT', '');
    http.expectOne(base + '/payment-reviews/review/resolve-refunded');
    vi.advanceTimersByTime(20001);
    http.expectOne(base + '/payment-reviews/review').flush(resolved());
    finishRefresh();
    expect(store.vm().resolutionState).toBe('done');
    store.retryResolution();
    http.expectNone((r) => r.method === 'POST');
  });
  it('affiche un conflit quand une preuve différente est déjà enregistrée', () => {
    showReview();
    store.resolve('EXT', '');
    http
      .expectOne(base + '/payment-reviews/review/resolve-refunded')
      .flush(
        { code: 'PAYMENT_REVIEW_RESOLUTION_CONFLICT' },
        { status: 409, statusText: 'Conflict' },
      );
    http.expectOne(base + '/payment-reviews/review').flush(resolved('OTHER'));
    finishRefresh();
    expect(store.vm().resolutionState).toBe('conflict');
    expect(store.vm().review?.externalRefundReference).toBe('OTHER');
  });
  it('interdit une nouvelle soumission tant que la relecture incertaine échoue', () => {
    showReview();
    store.resolve('EXT', '');
    http
      .expectOne(base + '/payment-reviews/review/resolve-refunded')
      .error(new ProgressEvent('error'));
    http.expectOne(base + '/payment-reviews/review').error(new ProgressEvent('error'));
    expect(store.vm().resolutionState).toBe('uncertain');
    store.retryResolution();
    store.resolve('OTHER', '');
    http.expectNone((r) => r.method === 'POST');
    store.checkResolution();
    http.expectOne(base + '/payment-reviews/review').flush(paymentReview);
    expect(store.vm().resolutionState).toBe('retry');
  });
  it('ne conserve aucune preuve dans le stockage navigateur', () => {
    const local = vi.spyOn(Storage.prototype, 'setItem');
    showReview();
    store.resolve('EXT', 'note');
    http
      .expectOne(base + '/payment-reviews/review/resolve-refunded')
      .flush({}, { status: 400, statusText: 'Bad request' });
    expect(local).not.toHaveBeenCalled();
    local.mockRestore();
  });
  it('invalide immédiatement toutes les vues à la déconnexion', () => {
    showReview();
    signedIn.set(false);
    generation.update((n) => n + 1);
    expect(store.vm().review).toBeNull();
    TestBed.flushEffects();
    expect(store.vm().message).toBe('');
    http.expectNone(() => true);
  });
});
