import { TestBed, ComponentFixture } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { apiTestProviders } from '../../testing/api-test';
import { ShippingOrderEditor } from './shipping-order-editor';
import { ShippingBatch } from './shipping-batch';
import { ShippingExplanation } from '../../shared/ui/shipping-explanation';
import { OrderShippingRow } from '../../api/generated/models';
import { variance, shippingTotals } from './shipping-presentation';

const initial: OrderShippingRow = {
  orderId: 'o',
  orderNumber: 'GRP-1',
  customerName: 'Awa Diop',
  assessment: {
    version: 0,
    status: 'PENDING',
    snapshot: {
      totalItems: 4,
      estimatedWeightKg: 1.2,
      terms: {
        shippingPricingMethod: 'PER_KG',
        shippingRate: 3500,
        shippingCurrency: 'XOF',
        transportMode: 'AIR',
      },
    },
    measurements: [],
    calculations: [],
  },
};
const base = '/api/organizations/shop/grouping-campaigns/campaign/orders/o/';
describe('Transport : contrôle des estimations et totaux', () => {
  it('calcule un écart sans assimiler inconnu à zéro', () => {
    expect(variance(1.2, 1.4)?.difference).toBeCloseTo(0.2);
    expect(variance(1.2, 1.4)?.percent).toBeCloseTo(16.666666);
    expect(variance(undefined, 1)).toBeNull();
    expect(variance(0, 1)?.percent).toBeNull();
  });
  it('affiche la couverture des totaux partiels', () => {
    const t = shippingTotals([
      initial,
      {
        ...initial,
        orderId: 'b',
        assessment: {
          currentMeasurementId: 'm',
          measurements: [{ id: 'm', information: { actualWeightKg: 2.8 } }],
          currentFinalCalculationId: 'c',
          calculations: [
            { id: 'c', explanation: { amount: 9800, billableUnit: 'KG', billableQuantity: 2.8 } },
          ],
        },
      },
    ]);
    expect(t.weight).toEqual({ value: 2.8, count: 1 });
    expect(t.amount.value).toBe(9800);
    expect(t.remaining).toBe(1);
    expect(t.volume.value).toBeUndefined();
  });
});
describe('Saisie du transport', () => {
  let fixture: ComponentFixture<ShippingOrderEditor>, http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ShippingOrderEditor);
    fixture.componentRef.setInput('row', structuredClone(initial));
    fixture.componentRef.setInput('organizationId', 'shop');
    fixture.componentRef.setInput('campaignId', 'campaign');
    fixture.detectChanges();
  });
  afterEach(() => {
    fixture.destroy();
    http.verify();
  });
  it('enregistre uniquement une mesure avec la version serveur', () => {
    const c = fixture.componentInstance;
    c.begin();
    c.info = { chargeableWeightKg: 2.8 };
    c.source = 'FORWARDER';
    c.saveMeasure();
    const req = http.expectOne(base + 'shipping-measurements');
    expect(req.request.body.version).toBe(0);
    expect(req.request.body.information.chargeableWeightKg).toBe(2.8);
    expect(req.request.body.information.actorId).toBeUndefined();
    req.flush({ version: 1 });
    http.expectNone(base + 'shipping-finalizations');
  });
  for (const info of [
    { actualWeightKg: 2.4 },
    { volumeCbm: 0.165 },
    { lengthCm: 60, widthCm: 35, heightCm: 20 },
  ]) {
    it('accepte une information physique partielle ' + Object.keys(info)[0], () => {
      const c = fixture.componentInstance;
      c.begin();
      c.info = info;
      c.saveMeasure();
      http.expectOne(base + 'shipping-measurements').flush({ version: 1 });
    });
  }
  it('sépare aperçu et finalisation du montant communiqué', () => {
    const c = fixture.componentInstance;
    c.begin();
    c.origin = 'PROVIDER_QUOTED';
    c.amount = 12500;
    c.source = 'FORWARDER';
    c.showPreview();
    const preview = http.expectOne(base + 'shipping-preview');
    expect(preview.request.body.measurementId).toBeUndefined();
    preview.flush({ amount: 12500, origin: 'PROVIDER_QUOTED' });
    http.expectNone(base + 'shipping-finalizations');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Ce montant servira ensuite');
    c.finalize();
    const final = http.expectOne(base + 'shipping-finalizations');
    expect(final.request.body).toEqual(preview.request.body);
    final.flush({ version: 1, status: 'FINALIZED' });
  });
  it('invalide tout aperçu dès une modification', () => {
    const c = fixture.componentInstance;
    c.begin();
    c.origin = 'PROVIDER_QUOTED';
    c.amount = 12500;
    c.showPreview();
    http.expectOne(base + 'shipping-preview').flush({ amount: 12500 });
    c.invalidate();
    c.finalize();
    http.expectNone(base + 'shipping-finalizations');
  });
  it('bloque un aperçu basé sur une mesure non enregistrée', () => {
    const c = fixture.componentInstance;
    c.begin();
    c.info = { actualWeightKg: 2 };
    c.measureDirty.set(true);
    c.showPreview();
    http.expectNone(base + 'shipping-preview');
  });
  it('n’invente aucune mesure pour un forfait', () => {
    fixture.componentRef.setInput('row', {
      ...initial,
      assessment: {
        ...initial.assessment,
        snapshot: {
          ...initial.assessment?.snapshot,
          terms: { shippingPricingMethod: 'FIXED', shippingRate: 2500 },
        },
      },
    });
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.begin();
    c.showPreview();
    const req = http.expectOne(base + 'shipping-preview');
    expect(req.request.body.measurementId).toBeUndefined();
    req.flush({ amount: 2500 });
    http.expectNone(base + 'shipping-measurements');
  });
  it('conflit : aucun retry financier et actualisation explicite', () => {
    const c = fixture.componentInstance;
    c.begin();
    c.showPreview();
    http
      .expectOne(base + 'shipping-preview')
      .flush({ code: 'SHIPPING_VERSION_CONFLICT' }, { status: 409, statusText: 'Conflict' });
    expect(c.stale()).toBe(true);
    c.showPreview();
    http.expectNone(base + 'shipping-preview');
  });
  it('corrige en désignant la mesure précédente et un motif', () => {
    fixture.componentRef.setInput('row', {
      ...initial,
      assessment: { version: 2, currentMeasurementId: 'old' },
    });
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.begin();
    c.info = { actualWeightKg: 2.6 };
    c.reason = 'Correction reçue';
    c.saveMeasure();
    const r = http.expectOne(base + 'shipping-measurements');
    expect(r.request.body.information.supersedesMeasurementId).toBe('old');
    expect(r.request.body.information.correctionReason).toBe('Correction reçue');
    r.flush({ version: 3 });
  });
});
describe('Limites de précision du transport', () => {
  it('refuse un montant communiqué non entier ou non représentable', () => {
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    const http = TestBed.inject(HttpTestingController),
      f = TestBed.createComponent(ShippingOrderEditor);
    f.componentRef.setInput('row', initial);
    f.componentRef.setInput('organizationId', 'shop');
    f.componentRef.setInput('campaignId', 'campaign');
    f.detectChanges();
    f.componentInstance.origin = 'PROVIDER_QUOTED';
    for (const amount of [12.5, Number.MAX_SAFE_INTEGER + 1, -1]) {
      f.componentInstance.amount = amount;
      f.componentInstance.showPreview();
    }
    http.expectNone(base + 'shipping-preview');
    f.destroy();
    http.verify();
  });
  it('ne permet pas de finaliser un montant impossible à présenter précisément', () => {
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    const http = TestBed.inject(HttpTestingController),
      f = TestBed.createComponent(ShippingOrderEditor);
    f.componentRef.setInput('row', initial);
    f.componentRef.setInput('organizationId', 'shop');
    f.componentRef.setInput('campaignId', 'campaign');
    f.detectChanges();
    f.componentInstance.showPreview();
    http.expectOne(base + 'shipping-preview').flush({ amount: Number.MAX_SAFE_INTEGER + 1 });
    expect(f.componentInstance.preview()).toBeNull();
    f.componentInstance.finalize();
    http.expectNone(base + 'shipping-finalizations');
    f.destroy();
    http.verify();
  });
});
describe('Liste des commandes et explication sûre', () => {
  it('charge les commandes de la boutique et du groupage', () => {
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    const h = TestBed.inject(HttpTestingController),
      f = TestBed.createComponent(ShippingBatch);
    f.componentRef.setInput('organizationId', 'shop');
    f.componentRef.setInput('campaignId', 'campaign');
    f.detectChanges();
    h.expectOne('/api/organizations/shop/grouping-campaigns/campaign/shipping-assessments').flush([
      initial,
    ]);
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('GRP-1');
    expect(f.nativeElement.textContent).toContain('Awa Diop');
    expect(f.nativeElement.textContent).toContain('1 commandes concernées');
    f.destroy();
    h.verify();
  });
  for (const origin of ['CALCULATED', 'PROVIDER_QUOTED'] as const) {
    it('rend une explication publique ' + origin + ' sans métadonnées privées', () => {
      const f = TestBed.createComponent(ShippingExplanation);
      f.componentRef.setInput('value', {
        origin,
        amount: 9800,
        rate: 3500,
        billableQuantity: 2.8,
        billableUnit: 'KG',
        source: 'FORWARDER',
        transportMode: 'AIR',
        method: 'ACTUAL_WEIGHT',
        note: 'SECRET-NOTE',
        actorId: 'SECRET-ACTOR',
      });
      f.detectChanges();
      const t = f.nativeElement.textContent;
      expect(t).not.toContain('SECRET');
      expect(t).toContain('Prestataire logistique');
      if (origin === 'PROVIDER_QUOTED') expect(t).not.toContain('Calcul :');
      else expect(t).toContain('Calcul :');
      f.destroy();
    });
  }
});
