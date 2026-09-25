import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { GroupiaApi } from '../../api/groupia-api';
import { PublicSelectionService } from './public-selection.service';
import { apiTestProviders } from '../../testing/api-test';
import { AuthService } from '../../core/auth/auth.service';
import { PublicOfferView } from '../../api/generated/models';
describe('Sélection publique', () => {
  let selection: PublicSelectionService;
  const offer: PublicOfferView = {
    offerId: 'offer',
    active: true,
    unitPrice: 3000,
    priceRevision: 1,
    catalog: { usable: true },
  };
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [...apiTestProviders(), PublicSelectionService] });
    selection = TestBed.inject(PublicSelectionService);
  });
  it('charge la page publique sans JWT même avec session', async () => {
    TestBed.inject(AuthService).acceptSession('test', 900);
    const pending = TestBed.inject(GroupiaApi).publicGrouping('public');
    const http = TestBed.inject(HttpTestingController);
    const req = http.expectOne('/api/public/groupings/public');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({ publicIdentifier: 'public', orderable: true });
    expect((await pending).orderable).toBe(true);
    http.verify();
  });
  it('ajoute une sélection disponible sans mutation HTTP', () => {
    selection.add({ orderable: true }, offer);
    selection.add({ orderable: true }, offer);
    expect(selection.count()).toBe(2);
  });
  it('interdit toute sélection si orderable=false', () => {
    selection.add({ orderable: false }, offer);
    expect(selection.count()).toBe(0);
  });
  it('interdit une variante indisponible', () => {
    selection.add({ orderable: true }, { ...offer, catalog: { usable: false } });
    expect(selection.count()).toBe(0);
  });
  it('vide la sélection après fermeture', () => {
    selection.add({ orderable: true }, offer);
    selection.reconcile({ orderable: false });
    expect(selection.count()).toBe(0);
  });
  it('retire les prix devenus périmés', () => {
    selection.add({ orderable: true }, offer);
    selection.reconcile({ orderable: true, offers: [{ ...offer, priceRevision: 2 }] });
    expect(selection.count()).toBe(0);
  });
});
