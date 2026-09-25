import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { GroupageStore } from './groupage.store';
import { apiTestProviders } from '../../testing/api-test';
import { AuthService } from '../../core/auth/auth.service';
describe('GroupageStore : mutations serveur', () => {
  let store: GroupageStore, http: HttpTestingController;
  const base = '/api/organizations/shop/grouping-campaigns';
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [...apiTestProviders(), GroupageStore] });
    TestBed.inject(AuthService).acceptSession('test', 900);
    store = TestBed.inject(GroupageStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  const create = async () => {
    const pending = store.create('shop', { title: 'Groupage' });
    http
      .expectOne(base)
      .flush({ id: 'group', title: 'Groupage', status: 'DRAFT', version: 7, offers: [] });
    await pending;
  };
  it('crée le brouillon et conserve une version non consécutive', async () => {
    await create();
    expect(store.current()?.version).toBe(7);
    expect(store.current()?.status).toBe('DRAFT');
  });
  it('enchaîne ajout, publication et identifiant public avec les versions du serveur', async () => {
    await create();
    let pending = store.add('shop', {
      productVariantId: 'variant',
      name: 'Produit',
      variant: 'Standard',
      unitPrice: 5000,
    });
    let req = http.expectOne(base + '/group/offers');
    expect(req.request.body.campaignVersion).toBe(7);
    req.flush({
      id: 'group',
      status: 'DRAFT',
      version: 42,
      offers: [{ id: 'offer', version: 3, productVariantId: 'variant', unitPrice: 5000 }],
    });
    await pending;
    pending = store.open('shop');
    req = http.expectOne(base + '/group/open');
    expect(req.request.body).toEqual({ version: 42 });
    expect(store.current()?.status).toBe('DRAFT');
    req.flush({ id: 'group', status: 'OPEN', version: 99, publicIdentifier: 'public' });
    await pending;
    expect(store.current()?.status).toBe('OPEN');
    expect(store.current()?.publicIdentifier).toBe('public');
    expect(store.current()?.version).toBe(99);
  });
  it('conserve aussi la version du prix retournée', async () => {
    await create();
    const pending = store.updatePrice('shop', { id: 'offer', version: 15 }, 6000);
    const req = http.expectOne(base + '/group/offers/offer');
    expect(req.request.body).toMatchObject({ campaignVersion: 7, version: 15, unitPrice: 6000 });
    req.flush({
      id: 'group',
      version: 65,
      offers: [{ id: 'offer', version: 80, unitPrice: 6000 }],
    });
    await pending;
    expect(store.current()?.offers?.[0].version).toBe(80);
  });
  it('actualise sur conflit sans rejouer la mutation', async () => {
    await create();
    const pending = store.open('shop');
    const assertion = expect(pending).rejects.toMatchObject({ status: 409 });
    http
      .expectOne(base + '/group/open')
      .flush(
        { code: 'GROUPING_CAMPAIGN_VERSION_CONFLICT' },
        { status: 409, statusText: 'Conflict' },
      );
    await Promise.resolve();
    http.expectOne(base + '/group').flush({ id: 'group', version: 90, status: 'CLOSED' });
    await assertion;
    expect(store.current()?.version).toBe(90);
    expect(store.current()?.status).toBe('CLOSED');
    http.expectNone(base + '/group/open');
  });
  it('garde le brouillon quand la publication est refusée', async () => {
    await create();
    const pending = store.open('shop');
    const assertion = expect(pending).rejects.toMatchObject({ status: 409 });
    http
      .expectOne(base + '/group/open')
      .flush({ code: 'GROUPING_HAS_NO_ACTIVE_OFFERS' }, { status: 409, statusText: 'Conflict' });
    await assertion;
    expect(store.current()?.status).toBe('DRAFT');
  });
});
