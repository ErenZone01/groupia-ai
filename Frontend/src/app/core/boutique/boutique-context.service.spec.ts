import { Router } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { BoutiqueContextService } from './boutique-context.service';
import { AuthService } from '../auth/auth.service';
import { apiTestProviders } from '../../testing/api-test';
describe('Contexte Boutique', () => {
  let context: BoutiqueContextService, http: HttpTestingController, auth: AuthService;
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    auth = TestBed.inject(AuthService);
    auth.acceptSession('test', 900);
    context = TestBed.inject(BoutiqueContextService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  const list = () => http.expectOne('/api/organizations?page=0&size=100');
  it('sélectionne une boutique existante', async () => {
    const pending = context.ensure();
    list().flush([{ id: 'a', name: 'Boutique A' }]);
    await pending;
    expect(context.selected()?.name).toBe('Boutique A');
  });
  it('revalide l’id persisté et rejette un id étranger', async () => {
    localStorage.setItem('groupia.boutique', 'foreign');
    const pending = context.ensure();
    list().flush([
      { id: 'a', name: 'A' },
      { id: 'b', name: 'B' },
    ]);
    await pending;
    expect(context.selectedId()).toBe('a');
    context.select('foreign');
    expect(context.selectedId()).toBeNull();
  });
  it('restaure uniquement une sélection autorisée', async () => {
    localStorage.setItem('groupia.boutique', 'b');
    const pending = context.ensure();
    list().flush([{ id: 'a' }, { id: 'b' }]);
    await pending;
    expect(context.selectedId()).toBe('b');
  });
  it('onboarding vide puis création', async () => {
    let pending = context.ensure();
    list().flush([]);
    await pending;
    expect(context.ready()).toBe(true);
    expect(context.selected()).toBeNull();
    pending = context.create('Ma boutique');
    const req = http.expectOne('/api/organizations');
    expect(req.request.body).toEqual({ name: 'Ma boutique' });
    req.flush({ id: 'new', name: 'Ma boutique' });
    await pending;
    expect(context.selectedId()).toBe('new');
  });
  it('ignore une réponse après déconnexion', async () => {
    const pending = context.ensure();
    const req = list();
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const logout = auth.logout();
    http.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await logout;
    req.flush([{ id: 'a' }]);
    await pending;
    expect(context.selected()).toBeNull();
  });
  it('permet une nouvelle tentative après erreur', async () => {
    const pending = context.ensure();
    list().flush({}, { status: 503, statusText: 'Unavailable' });
    await pending;
    expect(context.error()).toBeTruthy();
    const retry = context.ensure();
    list().flush([{ id: 'a' }]);
    await retry;
    expect(context.selectedId()).toBe('a');
    expect(context.error()).toBe('');
  });
});
