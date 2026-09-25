import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { GroupiaApi } from '../../api/groupia-api';
import { apiTestProviders } from '../../testing/api-test';
import { AuthService } from '../../core/auth/auth.service';
import { variantLabel } from '../../shared/utils/display';
describe('Catalogue réel', () => {
  let api: GroupiaApi, http: HttpTestingController;
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    TestBed.inject(AuthService).acceptSession('test', 900);
    api = TestBed.inject(GroupiaApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('crée un produit simple sans variante fabriquée', async () => {
    const pending = api.createProduct('shop', { name: 'Chemise', description: 'Lin' });
    const req = http.expectOne('/api/organizations/shop/products');
    expect(req.request.body).toEqual({ name: 'Chemise', description: 'Lin' });
    expect(req.request.responseType).toBe('json');
    req.flush({
      id: 'product',
      name: 'Chemise',
      variants: [{ id: 'variant', attributes: {}, canonicalVariantKey: 'DEFAULT' }],
    });
    const product = await pending;
    expect(variantLabel(product.variants?.[0].attributes)).toBe('Standard');
  });
  it('envoie les options sans recopier de clé canonique', async () => {
    const pending = api.createProduct('shop', {
      name: 'Chemise',
      variants: [{ attributes: { color: 'Noir', shoeSize: '42' } }],
    });
    const req = http.expectOne('/api/organizations/shop/products');
    expect(req.request.body.variants[0].canonicalVariantKey).toBeUndefined();
    req.flush({ id: 'product' });
    await pending;
  });
});
