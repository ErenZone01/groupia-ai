import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../../core/auth/auth.service';
import {
  CommercialCancellationPanel,
  CancellationView,
  RefundDetail,
} from './commercial-cancellation-panel';

const view: CancellationView = {
  caseId: null,
  status: null,
  orderStatus: 'CONFIRMED',
  orderVersion: 3,
  canCancel: true,
  blocker: null,
};
const detail: RefundDetail = {
  refund: {
    id: 'case',
    status: 'REFUND_DUE',
    version: 0,
    currency: 'XOF',
    collectedPrincipal: 2000,
    previouslyRefunded: 0,
    amountDue: 2000,
    legacyPlatformFee: 0,
    reason: 'Demande cliente',
    maskedReference: null,
  },
  canDeclare: true,
  providerRefundAvailable: false,
  historicalFeeCompensationDue: 0,
};
describe('Annulation et remboursement externe', () => {
  const generation = signal(1);
  let http: HttpTestingController;
  beforeEach(() => {
    generation.set(1);
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { generation, commandScope: () => `session-${generation()}` },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function panel(target: 'ORDER' | 'DIRECT_ORDER' = 'ORDER') {
    const fixture = TestBed.createComponent(CommercialCancellationPanel);
    fixture.componentRef.setInput('organizationId', 'org');
    fixture.componentRef.setInput('orderId', 'order');
    fixture.componentRef.setInput('target', target);
    fixture.detectChanges();
    return fixture;
  }
  const path = '/api/organizations/org/orders/order/cancellation';
  async function load(component: CommercialCancellationPanel, state = view) {
    const promise = component.load();
    http.expectOne(path).flush(state);
    if (state.caseId) {
      await vi.waitFor(() => expect(http.match(() => false)).toEqual([]));
      await vi.waitFor(() => expect(component.view()?.caseId).toBe('case'));
      http.expectOne('/api/organizations/org/commercial-refunds/case').flush(detail);
    }
    await promise;
  }
  it('ne lit rien avant ouverture et exige un motif et une confirmation', async () => {
    const fixture = panel(),
      c = fixture.componentInstance;
    http.expectNone(path);
    await load(c);
    await c.cancel();
    http.expectNone(path);
    c.reason = 'Demande cliente';
    await c.cancel();
    http.expectNone(path);
    c.confirmed = true;
    const send = c.cancel();
    await vi.waitFor(() => expect(sessionStorage.length).toBe(1));
    const request = http.expectOne(path);
    expect(request.request.body).toEqual({
      version: 3,
      reason: 'Demande cliente',
      confirmed: true,
    });
    expect(request.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    request.flush({}, { status: 503, statusText: 'Unavailable' });
    await send;
    expect(c.view()?.orderStatus).toBe('CONFIRMED');
    expect(c.error()).toBeTruthy();
  });
  it('réutilise la même intention après perte réseau et recréation de la page', async () => {
    let fixture = panel(),
      c = fixture.componentInstance;
    await load(c);
    c.reason = 'Demande cliente';
    c.confirmed = true;
    let send = c.cancel();
    await vi.waitFor(() => expect(sessionStorage.length).toBe(1));
    let request = http.expectOne(path);
    const key = request.request.headers.get('Idempotency-Key');
    request.error(new ProgressEvent('network'));
    await send;
    fixture.destroy();
    fixture = panel();
    c = fixture.componentInstance;
    await load(c);
    c.reason = 'Demande cliente';
    c.confirmed = true;
    send = c.cancel();
    await vi.waitFor(() => expect(c.busy()).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 10));
    request = http.expectOne(path);
    expect(request.request.headers.get('Idempotency-Key')).toBe(key);
    request.error(new ProgressEvent('network'));
    await send;
  });
  it('affiche les montants du serveur et ne transmet aucun montant de remboursement', async () => {
    const fixture = panel(),
      c = fixture.componentInstance;
    await load(c, { ...view, caseId: 'case', canCancel: false });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Aucun transfert automatique');
    c.declarationConfirmed = true;
    c.reference = 'external-fixture';
    const send = c.declare();
    await vi.waitFor(() => expect(sessionStorage.length).toBe(1));
    const request = http.expectOne(
      '/api/organizations/org/commercial-refunds/case/external-declaration',
    );
    expect(request.request.body).toEqual({
      version: 0,
      externalMethod: 'WAVE',
      reference: 'external-fixture',
      note: null,
      confirmed: true,
    });
    request.error(new ProgressEvent('network'));
    await send;
  });
  it('respecte les permissions et ignore une réponse après changement de session', async () => {
    const fixture = panel(),
      c = fixture.componentInstance;
    await load(c, { ...view, canCancel: false });
    c.reason = 'test';
    c.confirmed = true;
    await c.cancel();
    http.expectNone(path);
    const pending = c.load();
    const request = http.expectOne(path);
    generation.set(2);
    fixture.detectChanges();
    request.flush(view);
    await pending;
    expect(c.view()).toBeNull();
    expect(c.opened()).toBe(false);
  });
  it('utilise le parcours distinct des commandes directes', async () => {
    const c = panel('DIRECT_ORDER').componentInstance,
      pending = c.load();
    http.expectOne('/api/organizations/org/direct-orders/order/paid-cancellation').flush(view);
    await pending;
    expect(c.view()?.orderVersion).toBe(3);
  });
});
