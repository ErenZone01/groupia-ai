import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { apiTestProviders } from '../../testing/api-test';
import { AuthService } from '../../core/auth/auth.service';
import { LogisticsPanel } from './logistics-panel';
import { CampaignShipmentView } from '../../api/generated/models';

describe('Logistique du groupage', () => {
  let fixture: ComponentFixture<LogisticsPanel>;
  let http: HttpTestingController;
  const base = '/api/organizations/shop/grouping-campaigns/campaign/';
  const departure = '2026-01-01T09:00:00Z';
  function response(status = 'SUPPLIER_ORDERED', version = 7): CampaignShipmentView {
    return {
      campaign: { id: 'campaign', organizationId: 'shop', status, version },
      shipment:
        status === 'SUPPLIER_ORDERED'
          ? undefined
          : {
              id: 'shipment',
              transportMode: 'SEA',
              departedAt: departure,
              trackingReference: 'REF-PRIVEE',
              expectedArrivalAt: '2026-01-20T09:00:00Z',
              note: 'Note interne',
              arrivedAt: status === 'ARRIVED' ? '2026-01-21T09:00:00Z' : undefined,
            },
    };
  }
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    TestBed.inject(AuthService).acceptSession('unit-only-token', 600);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LogisticsPanel);
    fixture.componentRef.setInput('organizationId', 'shop');
    fixture.componentRef.setInput('campaign', {
      id: 'campaign',
      organizationId: 'shop',
      status: 'SUPPLIER_ORDERED',
      version: 4,
      shippingTerms: { transportMode: 'SEA' },
    });
  });
  afterEach(() => {
    fixture.destroy();
    http.verify();
  });
  function load(status = 'SUPPLIER_ORDERED', version = 7) {
    fixture.detectChanges();
    http.expectOne(base + 'shipment').flush(response(status, version));
    fixture.detectChanges();
  }
  function begin() {
    fixture.componentInstance.begin();
    fixture.componentInstance.departure = '2026-01-01T09:00';
    fixture.detectChanges();
  }
  it('propose le départ et affiche le transport déjà connu', () => {
    load();
    begin();
    expect(fixture.nativeElement.textContent).toContain('Transport : Bateau');
    expect(fixture.nativeElement.querySelector('input[name="departure"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('select')).toBeNull();
  });
  it('envoie la dernière version serveur et les champs privés seulement au départ', () => {
    load('SUPPLIER_ORDERED', 11);
    begin();
    const c = fixture.componentInstance;
    c.reference = ' REF ';
    c.note = ' interne ';
    c.ship();
    const req = http.expectOne(base + 'ship');
    expect(req.request.body).toMatchObject({
      version: 11,
      trackingReference: 'REF',
      note: 'interne',
    });
    expect(req.request.body.transportMode).toBeUndefined();
    req.flush(response('IN_TRANSIT', 19));
    expect(c.lifecycle()?.version).toBe(19);
    expect(c.lifecycle()?.status).toBe('IN_TRANSIT');
  });
  it('validation bloque les dates futures et les arrivées prévues antérieures', () => {
    load();
    begin();
    const c = fixture.componentInstance;
    c.departure = '2999-01-01T00:00';
    c.ship();
    http.expectNone(base + 'ship');
    c.departure = '2026-01-01T09:00';
    c.expected = '2025-01-01T09:00';
    c.ship();
    http.expectNone(base + 'ship');
    expect(c.error()).toContain('Vérifiez les dates');
  });
  it('double clic reste un seul POST avec état loading', () => {
    load();
    begin();
    const c = fixture.componentInstance;
    c.ship();
    c.ship();
    fixture.detectChanges();
    expect(c.busy()).toBe(true);
    expect(
      fixture.nativeElement.querySelector('button[type="submit"]')?.disabled ??
        fixture.nativeElement.querySelector('form button').disabled,
    ).toBe(true);
    http.expectOne(base + 'ship').flush(response('IN_TRANSIT', 8));
    expect(c.busy()).toBe(false);
  });
  it('arrivée exige la confirmation puis reprend le statut serveur', () => {
    load('IN_TRANSIT', 13);
    const c = fixture.componentInstance;
    c.arrive();
    http.expectNone(base + 'arrive');
    c.confirmingArrival.set(true);
    c.arrive();
    c.arrive();
    const req = http.expectOne(base + 'arrive');
    expect(req.request.body).toEqual({ version: 13 });
    req.flush(response('ARRIVED', 17));
    fixture.detectChanges();
    expect(c.lifecycle()?.version).toBe(17);
    expect(fixture.nativeElement.textContent).toContain('Marchandise arrivée');
    expect(fixture.nativeElement.textContent).toContain('Date d’arrivée');
    expect(fixture.nativeElement.textContent).not.toContain('ARRIVED');
  });
  it('actualisation conserve les informations en transport sans mutation', () => {
    load('IN_TRANSIT');
    fixture.componentInstance.reload();
    http.expectOne(base + 'shipment').flush(response('IN_TRANSIT'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('REF-PRIVEE');
    expect(fixture.nativeElement.textContent).toContain('Arrivée prévue');
    expect(fixture.nativeElement.textContent).toContain('Marquer comme arrivée');
  });
  it('conflit bloque toute mutation jusqu’à relecture et exige une nouvelle action', () => {
    load();
    begin();
    const c = fixture.componentInstance;
    c.ship();
    http
      .expectOne(base + 'ship')
      .flush(
        { code: 'GROUPING_CAMPAIGN_VERSION_CONFLICT', detail: 'SQL privé' },
        { status: 409, statusText: 'Conflict' },
      );
    c.ship();
    http.expectNone(base + 'ship');
    expect(c.stale()).toBe(true);
    expect(c.error()).not.toContain('SQL');
    c.reload();
    http.expectOne(base + 'shipment').flush(response('SUPPLIER_ORDERED', 20));
    c.ship();
    expect(http.expectOne(base + 'ship').request.body.version).toBe(20);
  });
  it('réponse perdue récupérée par GET sans rejouer le POST', () => {
    load();
    begin();
    const c = fixture.componentInstance;
    c.ship();
    http.expectOne(base + 'ship').flush({}, { status: 500, statusText: 'Server Error' });
    expect(c.lifecycle()?.status).toBe('SUPPLIER_ORDERED');
    c.reload();
    http.expectOne(base + 'shipment').flush(response('IN_TRANSIT', 8));
    http.expectNone(base + 'ship');
    expect(c.lifecycle()?.status).toBe('IN_TRANSIT');
  });
  it('erreur de lecture est réessayable sans exposer le détail serveur', () => {
    fixture.detectChanges();
    http
      .expectOne(base + 'shipment')
      .flush({ detail: 'secret' }, { status: 500, statusText: 'Server Error' });
    expect(fixture.componentInstance.stale()).toBe(true);
    expect(fixture.componentInstance.error()).not.toContain('secret');
    fixture.componentInstance.reload();
    http.expectOne(base + 'shipment').flush(response());
    expect(fixture.componentInstance.stale()).toBe(false);
  });
  it('changer de boutique efface les informations privées du formulaire', () => {
    load();
    begin();
    fixture.componentInstance.reference = 'Ancienne référence';
    fixture.componentInstance.note = 'Ancienne note';
    fixture.componentRef.setInput('organizationId', 'other');
    fixture.componentRef.setInput('campaign', {
      id: 'new',
      organizationId: 'other',
      status: 'SUPPLIER_ORDERED',
      version: 3,
    });
    fixture.detectChanges();
    http.expectOne('/api/organizations/other/grouping-campaigns/new/shipment').flush({
      campaign: { id: 'new', organizationId: 'other', status: 'SUPPLIER_ORDERED', version: 3 },
    });
    expect(fixture.componentInstance.reference).toBe('');
    expect(fixture.componentInstance.note).toBe('');
  });
  it('refuse une réponse rattachée à une autre boutique', () => {
    fixture.detectChanges();
    http.expectOne(base + 'shipment').flush({
      campaign: { id: 'campaign', organizationId: 'other', version: 7, status: 'ARRIVED' },
    });
    expect(fixture.componentInstance.stale()).toBe(true);
    expect(fixture.componentInstance.lifecycle()).toBeNull();
  });
});
