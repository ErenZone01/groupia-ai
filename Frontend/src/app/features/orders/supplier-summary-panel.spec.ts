import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { apiTestProviders } from '../../testing/api-test';
import { AuthService } from '../../core/auth/auth.service';
import { SupplierSummaryPanel } from './supplier-summary-panel';
import { SupplierSummaryView } from '../../api/generated/models';

describe('Récapitulatif fournisseur', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<SupplierSummaryPanel>;
  const endpoint = '/api/organizations/shop/grouping-campaigns/campaign/supplier-summary';
  const summary: SupplierSummaryView = {
    groupingCampaignId: 'campaign',
    acceptedOrderCount: 2,
    totalUnits: 7,
    goodsAmount: 35000,
    currency: 'XOF',
    lines: [
      {
        productNameSnapshot: 'Chemise historique',
        variantAttributesSnapshot: { color: 'noir', size: 'm' },
        totalQuantity: 7,
        goodsAmount: 35000,
        saleUnitSnapshot: 'UNIT',
      },
    ],
  };
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    TestBed.inject(AuthService).acceptSession('unit-only-token', 600);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SupplierSummaryPanel);
    fixture.componentRef.setInput('organizationId', 'shop');
    fixture.componentRef.setInput('campaignId', 'campaign');
    fixture.detectChanges();
  });
  afterEach(() => {
    fixture.destroy();
    http.verify();
  });
  it('charge une seule lecture authentifiée puis affiche les snapshots et les totaux serveur', () => {
    expect(fixture.nativeElement.querySelector('[role="status"]')).not.toBeNull();
    const request = http.expectOne(endpoint);
    expect(request.request.method).toBe('GET');
    expect(request.request.headers.get('Authorization')).toBe('Bearer unit-only-token');
    request.flush(summary);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('7 articles');
    expect(text.replace(/\s/g, '')).toContain('35000FCFA');
    expect(text).toContain('Chemise historique');
    expect(text).toContain('noir');
    expect(fixture.componentInstance.loading()).toBe(false);
  });
  it('affiche les totaux reçus sans les reconstruire depuis les lignes', () => {
    http.expectOne(endpoint).flush({ ...summary, totalUnits: 99, goodsAmount: 123456 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('99 articles');
    expect(fixture.nativeElement.textContent.replace(/\s/g, '')).toContain('123456FCFA');
  });
  it('affiche un vrai état vide après une réponse vide', () => {
    http
      .expectOne(endpoint)
      .flush({ ...summary, acceptedOrderCount: 0, totalUnits: 0, goodsAmount: 0, lines: [] });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Aucune commande acceptée pour le moment');
    expect(fixture.nativeElement.textContent).toContain('0 articles');
  });
  it('erreur puis retry sans détail interne ni ancien total', () => {
    http.expectOne(endpoint).flush({ detail: 'SQL private' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(fixture.componentInstance.data()).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('SQL');
    expect(fixture.nativeElement.textContent).toContain('Réessayer');
    fixture.componentInstance.reload();
    http.expectOne(endpoint).flush(summary);
    fixture.detectChanges();
    expect(fixture.componentInstance.error()).toBe('');
    expect(fixture.nativeElement.textContent).toContain('7 articles');
  });
  it('annule une lecture de l’ancienne boutique et efface ses données', () => {
    const old = http.expectOne(endpoint);
    fixture.componentRef.setInput('organizationId', 'other');
    fixture.detectChanges();
    expect(old.cancelled).toBe(true);
    http
      .expectOne('/api/organizations/other/grouping-campaigns/campaign/supplier-summary')
      .flush({ ...summary, lines: [] });
    expect(fixture.componentInstance.data()?.lines).toEqual([]);
  });
  it('annule la lecture au changement de groupage et à la destruction', () => {
    const old = http.expectOne(endpoint);
    fixture.componentRef.setInput('campaignId', 'second');
    fixture.detectChanges();
    expect(old.cancelled).toBe(true);
    const current = http.expectOne(
      '/api/organizations/shop/grouping-campaigns/second/supplier-summary',
    );
    fixture.destroy();
    expect(current.cancelled).toBe(true);
  });
  it('n’affiche pas une quantité arrondie au-delà des entiers sûrs', () => {
    http.expectOne(endpoint).flush(summary);
    expect(fixture.componentInstance.count(Number.MAX_SAFE_INTEGER + 1)).toBe('—');
    expect(fixture.componentInstance.count(undefined)).toBe('—');
  });
});
