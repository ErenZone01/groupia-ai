import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { PaymentsPage } from './payments.page';
import { PaymentRequestPage } from './payment-request.page';
import { PaymentReviewPage } from './payment-review.page';
import { PaymentsStore } from './payments.store';
import { paymentPage, paymentDetail, paymentReview } from '../../testing/payment-fixtures';
import { paymentLabel } from './payment-display';

describe('Payments pages', () => {
  const initial: ReturnType<PaymentsStore['vm']> = {
    requests: paymentPage,
    detail: paymentDetail,
    reviews: { items: [paymentReview], totalPages: 1 },
    review: paymentReview,
    loadingRequests: false,
    loadingReviews: false,
    loadingDetail: false,
    loadingReview: false,
    requestsError: '',
    reviewsError: '',
    detailError: '',
    reviewError: '',
    page: 0,
    reviewPage: 0,
    status: '',
    purpose: '',
    reviewStatus: 'OPEN',
    busy: false,
    message: '',
    resolutionError: '',
    resolutionState: 'idle',
  };
  const vm = signal(initial);
  const fake = {
    vm,
    showDashboard: vi.fn(),
    showRequest: vi.fn(),
    showReview: vi.fn(),
    filter: vi.fn(),
    filterReviews: vi.fn(),
    loadRequests: vi.fn(),
    loadReviews: vi.fn(),
    reload: vi.fn(),
    requestPage: vi.fn(),
    reviewPage: vi.fn(),
    resolve: vi.fn(),
    retryResolution: vi.fn(),
    checkResolution: vi.fn(),
  };
  beforeEach(() => {
    vm.set(structuredClone(initial));
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: of(convertToParamMap({ requestId: 'request', reviewId: 'review' })),
          },
        },
      ],
    });
    for (const component of [PaymentsPage, PaymentRequestPage, PaymentReviewPage])
      TestBed.overrideComponent(component, {
        set: { providers: [{ provide: PaymentsStore, useValue: fake }] },
      });
    HTMLDialogElement.prototype.showModal = function () {
      this.setAttribute('open', '');
    };
    HTMLDialogElement.prototype.close = function () {
      this.removeAttribute('open');
    };
  });
  function buttons(element: HTMLElement, text: string) {
    return [...element.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!;
  }
  it('affiche les compteurs et transmet un filtre sans recalculer le statut', () => {
    const fixture = TestBed.createComponent(PaymentsPage);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('1 paiements à examiner');
    expect(el.textContent).toContain('À vérifier');
    buttons(el, 'Réglés').click();
    expect(fake.filter).toHaveBeenCalledWith('SETTLED', '');
    expect(el.querySelector('a[href="/app/paiements/requests/request"]')).not.toBeNull();
  });
  it('présente les chargements, les listes vides et les erreurs avec reprise', () => {
    const fixture = TestBed.createComponent(PaymentsPage);
    vm.update((s) => ({ ...s, loadingRequests: true }));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Chargement des paiements');
    vm.update((s) => ({
      ...s,
      loadingRequests: false,
      requests: { items: [] },
      reviews: { items: [] },
    }));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Aucune obligation');
    expect(fixture.nativeElement.textContent).toContain('Aucun incident');
    vm.update((s) => ({ ...s, requestsError: 'Accès refusé' }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain(
      'Accès refusé',
    );
    buttons(fixture.nativeElement, 'Réessayer les paiements').click();
    expect(fake.loadRequests).toHaveBeenCalled();
  });
  it('montre les tentatives et un lien vers la commande sans reproduire sa fiche', () => {
    const fixture = TestBed.createComponent(PaymentRequestPage);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('.attempt')).toHaveLength(2);
    expect(el.textContent).toContain('TX-PRINCIPAL');
    expect(el.querySelector('a[href="/app/orders/order"]')).not.toBeNull();
    expect(el.textContent).not.toContain('checkoutUrl');
  });
  for (const capability of [true, false, undefined])
    it('affiche l’action uniquement pour canResolve=true (' + capability + ')', () => {
      vm.update((s) => ({ ...s, review: { ...paymentReview, canResolve: capability } }));
      const fixture = TestBed.createComponent(PaymentReviewPage);
      fixture.detectChanges();
      expect(!!buttons(fixture.nativeElement, 'Enregistrer un remboursement externe')).toBe(
        capability === true,
      );
    });
  it('montre la transaction snapshot et valide le formulaire avant la confirmation', () => {
    const fixture = TestBed.createComponent(PaymentReviewPage);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    buttons(el, 'Enregistrer un remboursement externe').click();
    fixture.detectChanges();
    const dialog = el.querySelector('dialog')!;
    expect(dialog.open).toBe(true);
    expect(dialog.textContent).toContain('TX-SNAPSHOT');
    expect(dialog.textContent).toContain("Cette action n'effectue aucun remboursement.");
    expect(dialog.textContent).not.toContain('TX-PRINCIPAL');
    fixture.componentInstance.reference.set(' ');
    fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(fake.resolve).not.toHaveBeenCalled();
    expect(dialog.querySelector('[role=alert]')).not.toBeNull();
    fixture.componentInstance.reference.set('EXT');
    fixture.componentInstance.note.set('note');
    fixture.componentInstance.submit();
    expect(fake.resolve).toHaveBeenCalledWith('EXT', 'note');
  });
  it('boucle Tab et Shift+Tab dans les contrôles du dialogue', () => {
    const fixture=TestBed.createComponent(PaymentReviewPage); fixture.detectChanges(); fixture.componentInstance.open();
    const dialog:HTMLDialogElement=fixture.nativeElement.querySelector('dialog');
    const first=dialog.querySelector('input')!,last=dialog.querySelector('button[type=submit]') as HTMLButtonElement;
    first.focus();const backwards=new KeyboardEvent('keydown',{key:'Tab',shiftKey:true,cancelable:true});fixture.componentInstance.cycleFocus(backwards);
    expect(backwards.defaultPrevented).toBe(true);expect(document.activeElement).toBe(last);
    const forwards=new KeyboardEvent('keydown',{key:'Tab',cancelable:true});fixture.componentInstance.cycleFocus(forwards);expect(document.activeElement).toBe(first);
  });
  it('ferme le dialogue et efface ses champs à l’invalidation de session', () => {
    const fixture = TestBed.createComponent(PaymentReviewPage);
    fixture.detectChanges();
    fixture.componentInstance.open();
    fixture.componentInstance.reference.set('PRIVATE');
    vm.update((s) => ({ ...s, review: null }));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('TX-SNAPSHOT');
    expect(fixture.componentInstance.reference()).toBe('');
  });
  it('affiche une résolution et ne propose aucune nouvelle action financière', () => {
    vm.update((s) => ({
      ...s,
      review: {
        ...paymentReview,
        status: 'RESOLVED_REFUNDED',
        canResolve: false,
        externalRefundReference: 'EXT',
      },
    }));
    const fixture = TestBed.createComponent(PaymentReviewPage);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Remboursement externe enregistré');
    expect(buttons(fixture.nativeElement, 'Enregistrer un remboursement externe')).toBeUndefined();
  });
  it('traduit les raisons inconnues sans les utiliser comme état financier', () => {
    expect(paymentLabel('FUTURE_REASON')).toBe('À examiner avec l’équipe');
  });
});
