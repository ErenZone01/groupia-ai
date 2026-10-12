import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { apiTestProviders } from '../../testing/api-test';
import { AuthService } from '../../core/auth/auth.service';
import { CompletionPanel } from './completion-panel';
import { GroupingCompletionView } from '../../api/generated/models';
describe('Fin définitive du groupage', () => {
  let fixture: ComponentFixture<CompletionPanel>;
  let http: HttpTestingController;
  const base = '/api/organizations/shop/grouping-campaigns/campaign/';
  const eligible: GroupingCompletionView = {
    eligible: true,
    canComplete: true,
    campaignStatus: 'DISTRIBUTING',
    campaignVersion: 12,
    totalOrders: 1,
    completedOrders: 1,
    blockers: [],
  };
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    TestBed.inject(AuthService).acceptSession('unit-only-token', 600);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CompletionPanel);
    fixture.componentRef.setInput('organizationId', 'shop');
    fixture.componentRef.setInput('campaign', {
      id: 'campaign',
      status: 'DISTRIBUTING',
      version: 12,
    });
    fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    dialog.close = () => dialog.removeAttribute('open');
    dialog.showModal = () => dialog.setAttribute('open', '');
  });
  afterEach(() => {
    fixture.destroy();
    http.verify();
  });
  function load(value = eligible) {
    http.expectOne(base + 'completion').flush(value);
    fixture.detectChanges();
  }
  it('envoie seulement version et confirmation, sans faits financiers', () => {
    load();
    fixture.componentInstance.ask();
    expect(fixture.nativeElement.querySelector('dialog').hasAttribute('open')).toBe(true);
    fixture.componentInstance.submit();
    fixture.componentInstance.submit();
    const request = http.expectOne(base + 'complete');
    expect(request.request.body).toEqual({ version: 12, confirmed: true });
    request.flush({
      ...eligible,
      eligible: false,
      campaignStatus: 'COMPLETED',
      campaignVersion: 13,
      completedAt: '2026-10-12T12:00:00Z',
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Groupage terminé');
  });
  it('refuse une commande avec blocages et montre un lien valide', () => {
    load({
      ...eligible,
      eligible: false,
      blockers: [{ code: 'PAYMENT_REVIEW_OPEN', orderId: 'order', orderNumber: 'GRP-1' }],
    });
    fixture.componentInstance.submit();
    http.expectNone(base + 'complete');
    expect(fixture.nativeElement.textContent).toContain('Anomalie de paiement à régulariser');
    expect(fixture.nativeElement.querySelector('a').getAttribute('href')).toBe('/app/orders/order');
  });
  it('ne propose aucune mutation au lecteur', () => {
    load({ ...eligible, canComplete: false });
    fixture.componentInstance.submit();
    http.expectNone(base + 'complete');
    expect(fixture.nativeElement.textContent).not.toContain('Terminer définitivement le groupage');
  });
  it('recharge le diagnostic et demande le rechargement de campagne après 409', () => {
    load();
    const refresh = vi.fn();
    fixture.componentInstance.refreshCampaign.subscribe(refresh);
    fixture.componentInstance.submit();
    http
      .expectOne(base + 'complete')
      .flush({ code: 'GROUPING_COMPLETION_BLOCKED' }, { status: 409, statusText: 'Conflict' });
    expect(refresh).toHaveBeenCalledOnce();
    load({
      ...eligible,
      eligible: false,
      campaignVersion: 13,
      blockers: [{ code: 'PAYMENT_RECEIPT_PENDING' }],
    });
    fixture.componentInstance.submit();
    http.expectNone(base + 'complete');
  });
  it('ne montre pas le diagnostic financier à un opérateur', () => {
    http.expectOne(base + 'completion').flush({}, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('section')).toBeNull();
  });
});
