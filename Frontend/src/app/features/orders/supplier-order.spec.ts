import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { apiTestProviders } from '../../testing/api-test';
import { AuthService } from '../../core/auth/auth.service';
import { SupplierSummaryPanel } from './supplier-summary-panel';
import { SupplierOrderResponse } from '../../api/generated/models';

describe('Commande fournisseur figée', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<SupplierSummaryPanel>;
  const base = '/api/organizations/shop/grouping-campaigns/campaign/';
  const line = {
    productNameSnapshot: 'Chemise historique',
    variantAttributesSnapshot: { color: 'noir', size: 'm' },
    saleUnitSnapshot: 'UNIT',
    totalQuantity: 7,
    goodsAmount: 35000,
  };
  const live = {
    groupingCampaignId: 'campaign',
    acceptedOrderCount: 2,
    totalUnits: 7,
    goodsAmount: 35000,
    currency: 'XOF',
    lines: [line],
  };
  const result: SupplierOrderResponse = {
    campaign: { id: 'campaign', organizationId: 'shop', status: 'SUPPLIER_ORDERED', version: 8 },
    supplierOrder: {
      ...live,
      id: 'purchase',
      organizationId: 'shop',
      orderedAt: '2026-09-26T09:30:00Z',
      lines: [{ productId: 'p', productVariantId: 'v', groupingOfferId: 'o', snapshot: line }],
    },
    lateAcceptedOrderCount: 0,
    replayed: false,
  };
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    TestBed.inject(AuthService).acceptSession('unit-only-token', 600);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SupplierSummaryPanel);
    fixture.componentRef.setInput('organizationId', 'shop');
    fixture.componentRef.setInput('campaignId', 'campaign');
    fixture.componentRef.setInput('campaignStatus', 'CLOSED');
    fixture.componentRef.setInput('campaignVersion', 7);
  });
  afterEach(() => {
    fixture.destroy();
    http.verify();
  });
  function load() {
    fixture.detectChanges();
    http.expectOne(base + 'supplier-summary').flush(live);
    fixture.detectChanges();
  }
  function create() {
    fixture.componentInstance.begin();
    fixture.componentInstance.create();
    return http.expectOne(base + 'supplier-order');
  }
  it('CLOSED propose une confirmation explicite et transmet la version du serveur', () => {
    load();
    expect(fixture.nativeElement.textContent).toContain('J’ai passé la commande fournisseur');
    fixture.componentInstance.create();
    http.expectNone(base + 'supplier-order');
    fixture.componentInstance.begin();
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('[aria-label="Confirmation de commande fournisseur"]'),
    ).not.toBeNull();
    const request = create();
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ campaignVersion: 7 });
    request.flush(result);
  });
  it('double clic et loading ne déclenchent qu’un seul POST', () => {
    load();
    const request = create();
    fixture.componentInstance.create();
    fixture.componentInstance.begin();
    fixture.detectChanges();
    expect(fixture.componentInstance.busy()).toBe(true);
    const buttons = [...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[];
    expect(buttons.find((b) => b.textContent?.includes('Confirmer la commande'))?.disabled).toBe(
      true,
    );
    http.expectNone(base + 'supplier-order');
    request.flush(result);
    expect(fixture.componentInstance.busy()).toBe(false);
  });
  it('succès affiche le bon, la date, la mention définitive et émet la version reçue', () => {
    load();
    const changed = vi.fn();
    fixture.componentInstance.campaignChanged.subscribe(changed);
    create().flush(result);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Commande fournisseur');
    expect(fixture.nativeElement.textContent).toContain('ne changera plus');
    expect(fixture.nativeElement.querySelector('time').getAttribute('datetime')).toBe(
      '2026-09-26T09:30:00Z',
    );
    expect(changed).toHaveBeenCalledWith(result.campaign);
    expect(fixture.componentInstance.purchase()?.id).toBe('purchase');
  });
  it('rafraîchir un achat relit uniquement le bon conservé', () => {
    load();
    create().flush(result);
    fixture.componentInstance.reload();
    http.expectOne(base + 'supplier-order').flush(result);
    http.expectNone(base + 'supplier-summary');
    expect(fixture.componentInstance.data()?.totalUnits).toBe(7);
  });
  it('un groupage déjà commandé ne charge jamais le live summary', () => {
    fixture.componentRef.setInput('campaignStatus', 'SUPPLIER_ORDERED');
    fixture.detectChanges();
    http.expectOne(base + 'supplier-order').flush(result);
    fixture.detectChanges();
    expect(fixture.componentInstance.canCreate()).toBe(false);
    http.expectNone(base + 'supplier-summary');
  });
  it('le refus ne montre que le nombre de commandes en attente', () => {
    load();
    create().flush(
      {
        code: 'SUPPLIER_ORDER_PENDING_PAYMENTS',
        details: { pendingOrderCount: 2 },
        detail: 'email privé',
      },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(
      '2 commandes sont encore en attente de paiement.',
    );
    expect(fixture.nativeElement.textContent).not.toContain('email');
    expect(fixture.componentInstance.purchase()).toBeNull();
  });
  it('OPEN affiche la gestion des commandes sans bouton de création', () => {
    fixture.componentRef.setInput('campaignStatus', 'OPEN');
    load();
    expect(fixture.nativeElement.textContent).toContain('Gérer les commandes');
    expect(fixture.nativeElement.textContent).not.toContain('J’ai passé');
    expect(fixture.componentInstance.canCreate()).toBe(false);
  });
  it('n’invente jamais une version absente ni un bon vide', () => {
    fixture.componentRef.setInput('campaignVersion', undefined);
    load();
    expect(fixture.componentInstance.canCreate()).toBe(false);
    fixture.componentRef.setInput('campaignVersion', 11);
    fixture.componentInstance.reload();
    http
      .expectOne(base + 'supplier-summary')
      .flush({ ...live, acceptedOrderCount: 0, totalUnits: 0, goodsAmount: 0, lines: [] });
    expect(fixture.componentInstance.canCreate()).toBe(false);
  });
  it('réponse perdue : récupère le bon par GET sans répéter le POST', () => {
    load();
    create().flush({}, { status: 500, statusText: 'Error' });
    const recovery = http.expectOne(base + 'supplier-order');
    expect(recovery.request.method).toBe('GET');
    recovery.flush(result);
    fixture.detectChanges();
    expect(fixture.componentInstance.purchase()?.id).toBe('purchase');
    expect(fixture.componentInstance.stale()).toBe(false);
  });
  it('conflit de version : bloque la mutation jusqu’à actualisation, sans deviner la version', () => {
    load();
    create().flush(
      { code: 'GROUPING_CAMPAIGN_VERSION_CONFLICT' },
      { status: 409, statusText: 'Conflict' },
    );
    http
      .expectOne(base + 'supplier-order')
      .flush({ code: 'SUPPLIER_ORDER_NOT_FOUND' }, { status: 404, statusText: 'Not found' });
    fixture.detectChanges();
    expect(fixture.componentInstance.canCreate()).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Actualiser le groupage');
    fixture.componentInstance.begin();
    fixture.componentInstance.create();
    http.expectNone(base + 'supplier-order');
  });
  it('signale une confirmation tardive sans ajouter ses articles', () => {
    fixture.componentRef.setInput('campaignStatus', 'SUPPLIER_ORDERED');
    fixture.detectChanges();
    http.expectOne(base + 'supplier-order').flush({ ...result, lateAcceptedOrderCount: 1 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(
      '1 commande(s) ont été confirmées après cet achat',
    );
    expect(fixture.componentInstance.data()?.totalUnits).toBe(7);
  });
  it('lecture figée en erreur puis retry reste une lecture', () => {
    fixture.componentRef.setInput('campaignStatus', 'SUPPLIER_ORDERED');
    fixture.detectChanges();
    http
      .expectOne(base + 'supplier-order')
      .flush({ detail: 'SQL private' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Réessayer');
    expect(fixture.nativeElement.textContent).not.toContain('SQL');
    fixture.componentInstance.reload();
    http.expectOne(base + 'supplier-order').flush(result);
    expect(fixture.componentInstance.error()).toBe('');
  });
});
