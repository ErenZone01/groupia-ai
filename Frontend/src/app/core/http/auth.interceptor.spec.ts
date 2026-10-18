import { provideApiConfiguration } from '../../api/generated/api-configuration';
import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../auth/auth.service';
import { authInterceptor } from './auth.interceptor';
describe('Auth interceptor', () => {
  let http: HttpClient, backend: HttpTestingController, auth: AuthService;
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.acceptSession('test-secret', 60);
  });
  afterEach(() => backend.verify());
  it('ajoute Bearer aux seules routes privées Groupia', () => {
    http.get('/api/organizations').subscribe();
    const req = backend.expectOne('/api/organizations');
    expect(req.request.headers.get('Authorization')).toBe('Bearer test-secret');
    req.flush({});
  });
  it.each([
    'https://external.example/api/orders',
    '/api-evil/orders',
    '/api/public/groupings/demo',
    '/api/auth/login',
  ])('ne divulgue pas le token à %s', (url) => {
    http.get(url).subscribe();
    const req = backend.expectOne(url);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });
  it('ne traite pas un 401 métier arbitraire comme expiration', () => {
    http.get('/api/private').subscribe({ error: () => {} });
    backend.expectOne('/api/private').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.token()).toBe('test-secret');
  });
  it('un ancien 401 ne détruit pas une nouvelle session', () => {
    http.get('/api/private').subscribe({ error: () => {} });
    auth.acceptSession('new-token', 60);
    backend.expectOne('/api/private').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.token()).toBe('new-token');
  });
  it('rejoue une seule fois un vrai rejet Bearer en conservant la commande', async () => {
    auth.acceptSession('old', 900, '11111111-1111-4111-8111-111111111111');
    let failures = 0;
    http
      .post('/api/payments?x=1', { amount: 123 }, { headers: { 'Idempotency-Key': 'same-key' } })
      .subscribe({ error: () => failures++ });
    backend
      .expectOne('/api/payments?x=1')
      .flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/auth/refresh').flush({
      accessToken: 'new',
      tokenType: 'Bearer',
      expiresIn: 900,
      sessionId: '11111111-1111-4111-8111-111111111111',
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const retry = backend.expectOne('/api/payments?x=1');
    expect(retry.request.body).toEqual({ amount: 123 });
    expect(retry.request.method).toBe('POST');
    expect(retry.request.headers.get('Idempotency-Key')).toBe('same-key');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer new');
    retry.flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 401, statusText: 'Unauthorized' });
    expect(failures).toBe(1);
    backend.expectNone('/api/auth/refresh');
  });
  it.each([0, 500, 502, 503])('ne rejoue aucune commande financière en erreur %s', (status) => {
    http.post('/api/payments', {}).subscribe({ error: () => {} });
    backend.expectOne('/api/payments').flush({}, { status, statusText: 'Unavailable' });
    backend.expectNone('/api/auth/refresh');
    backend.expectNone('/api/payments');
  });
  it('20 rejets Bearer simultanés partagent un refresh', async () => {
    auth.acceptSession('old', 900, '11111111-1111-4111-8111-111111111111');
    let replies = 0;
    for (let i = 0; i < 20; i++) http.get('/api/private?i=' + i).subscribe(() => replies++);
    const rejected = backend.match((r) => r.url.startsWith('/api/private'));
    rejected.forEach((r) =>
      r.flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 401, statusText: 'Unauthorized' }),
    );
    backend.expectOne('/api/auth/refresh').flush({
      accessToken: 'new',
      expiresIn: 900,
      tokenType: 'Bearer',
      sessionId: '11111111-1111-4111-8111-111111111111',
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const retried = backend.match((r) => r.url.startsWith('/api/private'));
    expect(retried).toHaveLength(20);
    retried.forEach((r) => r.flush({}));
    expect(replies).toBe(20);
  });
  it('renouvelle le JWT expiré d’un onglet resté inactif', async () => {
    auth.acceptSession('expired', 1, '11111111-1111-4111-8111-111111111111');
    const clock = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 2000);
    try {
      let completed = false;
      http.get('/api/private').subscribe(() => (completed = true));
      const rejected = backend.expectOne('/api/private');
      expect(rejected.request.headers.has('Authorization')).toBe(false);
      rejected.flush(
        { code: 'AUTHENTICATION_REQUIRED' },
        { status: 401, statusText: 'Unauthorized' },
      );
      backend.expectOne('/api/auth/refresh').flush({
        accessToken: 'renewed',
        expiresIn: 900,
        tokenType: 'Bearer',
        sessionId: '11111111-1111-4111-8111-111111111111',
      });
      await new Promise((resolve) => setTimeout(resolve, 0));
      backend.expectOne('/api/private').flush({});
      expect(completed).toBe(true);
    } finally {
      clock.mockRestore();
    }
  });
  it('un vieux rejet Bearer utilise le credential déjà renouvelé sans autre refresh', async () => {
    const sid = '11111111-1111-4111-8111-111111111111';
    auth.acceptSession('old', 900, sid);
    const generation = auth.generation();
    http.get('/api/private').subscribe();
    const old = backend.expectOne('/api/private');
    auth.acceptSession('new', 900, sid);
    old.flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 401, statusText: 'Unauthorized' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    backend.expectNone('/api/auth/refresh');
    const retry = backend.expectOne('/api/private');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer new');
    retry.flush({});
    expect(auth.generation()).toBe(generation);
    expect(auth.token()).toBe('new');
  });
});
