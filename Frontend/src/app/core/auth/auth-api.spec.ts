import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';
import { apiTestProviders } from '../../testing/api-test';
describe('Authentification réelle via le client généré', () => {
  let auth: AuthService, http: HttpTestingController;
  beforeEach(() => {
    sessionStorage.clear();
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
    req.flush({ accessToken: 'test-only', tokenType: 'Bearer', expiresIn: 900 });
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
      password: 'phrase suffisamment longue',
    });
    const req = http.expectOne('/api/auth/register');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({ id: 'user', phoneNumber: '+221771234567' });
    await pending;
    expect(auth.signedIn()).toBe(false);
  });
  it('déconnecte et redirige', () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    auth.acceptSession('test', 900);
    auth.logout();
    expect(auth.token()).toBeNull();
    expect(navigate).toHaveBeenCalledWith('/connexion');
  });
  it('expire automatiquement une session ouverte', async () => {
    vi.useFakeTimers();
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/app/products');
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    auth.acceptSession('test', 1);
    await vi.advanceTimersByTimeAsync(1001);
    expect(auth.signedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/connexion'], {
      queryParams: { expired: '1', returnUrl: '/app/products' },
    });
  });
});
