import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { apiTestProviders } from '../../testing/api-test';
describe('Authentification réelle via le client généré', () => {
  let auth: AuthService, http: HttpTestingController;
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });
  it('connecte avec le contrat réel et décode le JSON', async () => {
    const pending = auth.login({
      phoneNumber: '+221771234567',
      password: 'phrase suffisamment longue',
    });
    const req = http.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.responseType).toBe('json');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({
      accessToken: 'test-only',
      tokenType: 'Bearer',
      expiresIn: 900,
      sessionId: '11111111-1111-4111-8111-111111111111',
    });
    await pending;
    expect(auth.signedIn()).toBe(true);
  });
  it('ne crée pas de session après un échec', async () => {
    const pending = auth.login({ phoneNumber: 'bad', password: 'bad' });
    const assertion = expect(pending).rejects.toMatchObject({ status: 401 });
    http
      .expectOne('/api/auth/login')
      .flush({ code: 'INVALID_CREDENTIALS' }, { status: 401, statusText: 'Unauthorized' });
    await assertion;
    expect(auth.token()).toBeNull();
  });
  it('inscrit sans inventer un jeton de session', async () => {
    const pending = auth.register({
      phoneNumber: '+221771234567',
      firstName: 'Awa',
      lastName: 'Diop',
      birthDate: '1995-04-12',
      password: 'phrase suffisamment longue',
    });
    const req = http.expectOne('/api/auth/register');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(null, { status: 202, statusText: 'Accepted' });
    await pending;
    expect(auth.signedIn()).toBe(false);
  });
  it('déconnecte côté serveur et vide la mémoire', async () => {
    auth.acceptSession('test', 900);
    const pending = auth.logout();
    expect(auth.token()).toBeNull();
    const req = http.expectOne('/api/auth/logout');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(null, { status: 204, statusText: 'No Content' });
    await pending;
  });
});
