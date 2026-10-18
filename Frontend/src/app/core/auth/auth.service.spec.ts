import { apiTestProviders } from '../../testing/api-test';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { SessionCoordinator } from './session-coordinator';
const sid = '11111111-1111-4111-8111-111111111111';
const response = (token = 'test-access') => ({
  accessToken: token,
  tokenType: 'Bearer',
  expiresIn: 900,
  sessionId: sid,
});
describe('Refresh sessions', () => {
  let auth: AuthService, http: HttpTestingController;
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });
  const start = () => (auth = TestBed.inject(AuthService));
  it('supprime le stockage historique et restaure seulement via le cookie', async () => {
    sessionStorage.setItem(
      'groupia.session',
      JSON.stringify({ accessToken: 'legacy', expiresAt: Date.now() + 900000 }),
    );
    start();
    expect(auth.token()).toBeNull();
    expect(sessionStorage.getItem('groupia.session')).toBeNull();
    const pending = auth.restore();
    expect(auth.state()).toBe('RESTORING');
    const req = http.expectOne('/api/auth/refresh');
    expect(req.request.withCredentials).toBe(true);
    expect(req.request.body).toBeNull();
    expect(req.request.headers.get('X-Groupia-Auth')).toBe('1');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(response());
    expect(await pending).toBe(true);
    expect(auth.state()).toBe('AUTHENTICATED');
    expect(JSON.stringify({ ...sessionStorage, ...localStorage })).not.toContain('test-access');
  });
  it('20 refresh partagent une seule requête et gardent la génération', async () => {
    start();
    auth.acceptSession('old', 900, sid);
    const generation = auth.generation();
    const jobs = Array.from({ length: 20 }, () => auth.refresh('old'));
    http.expectOne('/api/auth/refresh').flush(response());
    expect((await Promise.all(jobs)).every(Boolean)).toBe(true);
    expect(auth.generation()).toBe(generation);
  });
  it('401 invalide la session mais 503 ne change pas son identité', async () => {
    start();
    auth.acceptSession('old', 900, sid);
    const generation = auth.generation();
    let pending = auth.refresh();
    http.expectOne('/api/auth/refresh').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(await pending).toBe(true);
    expect(auth.token()).toBe('old');
    expect(auth.generation()).toBe(generation);
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 6000);
    pending = auth.refresh();
    http.expectOne('/api/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(await pending).toBe(false);
    expect(auth.state()).toBe('ANONYMOUS');
    expect(auth.generation()).toBe(generation + 1);
  });
  it.each([0, 403, 502, 503])('restauration indisponible sans faux logout : %s', async (status) => {
    start();
    const pending = auth.restore();
    http.expectOne('/api/auth/refresh').flush({}, { status, statusText: 'Unavailable' });
    expect(await pending).toBe(false);
    expect(auth.state()).toBe('RESTORATION_UNAVAILABLE');
    expect(auth.generation()).toBe(0);
  });
  it('respecte Retry-After', async () => {
    start();
    let pending = auth.restore();
    http
      .expectOne('/api/auth/refresh')
      .flush(
        {},
        { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '120' } },
      );
    await pending;
    await auth.restore();
    http.expectNone('/api/auth/refresh');
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 121000);
    pending = auth.restore();
    http.expectOne('/api/auth/refresh').flush(response());
    await pending;
  });
  it('ignore un refresh tardif après logout même hors ligne', async () => {
    start();
    auth.acceptSession('old', 900, sid);
    const refresh = auth.refresh(),
      req = http.expectOne('/api/auth/refresh');
    const logout = auth.logout();
    http.expectOne('/api/auth/logout').flush({}, { status: 503, statusText: 'Unavailable' });
    req.flush(response());
    await Promise.all([refresh, logout]);
    expect(auth.token()).toBeNull();
    expect(localStorage.getItem('groupia.logout-pending')).toBe('pending');
    const restore = auth.restore();
    http.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await restore;
    http.expectNone('/api/auth/refresh');
    expect(auth.token()).toBeNull();
  });
  it('un broadcast renouvelle la même identité et un logout empêche sa résurrection', () => {
    start();
    auth.acceptSession('old', 60, sid);
    const generation = auth.generation();
    const coordinator = TestBed.inject(SessionCoordinator);
    coordinator.receive({
      kind: 'session',
      session: { accessToken: 'new', sessionId: sid, expiresAt: Date.now() + 900000 },
    });
    expect(auth.token()).toBe('new');
    expect(auth.generation()).toBe(generation);
    coordinator.receive({ kind: 'logout' });
    coordinator.receive({
      kind: 'session',
      session: { accessToken: 'late', sessionId: sid, expiresAt: Date.now() + 1000000 },
    });
    expect(auth.token()).toBeNull();
  });
  it('409 attend un credential gagnant sans déconnexion', async () => {
    vi.useFakeTimers();
    start();
    auth.acceptSession('old', 900, sid);
    const pending = auth.refresh();
    http
      .expectOne('/api/auth/refresh')
      .flush({ code: 'AUTH_REFRESH_CONCURRENT' }, { status: 409, statusText: 'Conflict' });
    await vi.advanceTimersByTimeAsync(10);
    TestBed.inject(SessionCoordinator).receive({
      kind: 'session',
      session: { accessToken: 'winner', sessionId: sid, expiresAt: Date.now() + 910000 },
    });
    await vi.advanceTimersByTimeAsync(250);
    expect(await pending).toBe(true);
    expect(auth.token()).toBe('winner');
  });
  it('borne les reprises 409', async () => {
    vi.useFakeTimers();
    start();
    const pending = auth.restore();
    for (let i = 0; i < 3; i++) {
      http
        .expectOne('/api/auth/refresh')
        .flush({ code: 'AUTH_REFRESH_CONCURRENT' }, { status: 409, statusText: 'Conflict' });
      await vi.advanceTimersByTimeAsync(250 * (i + 1));
    }
    expect(await pending).toBe(false);
    expect(auth.state()).toBe('RESTORATION_UNAVAILABLE');
  });
  it('renouvelle une session visible et active avant expiration', async () => {
    vi.useFakeTimers();
    start();
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    auth.acceptSession('old', 61, sid);
    await vi.advanceTimersByTimeAsync(1000);
    http.expectOne('/api/auth/refresh').flush(response());
    await vi.advanceTimersByTimeAsync(1);
    expect(auth.token()).toBe('test-access');
  });
  it('un onglet caché ne renouvelle pas', async () => {
    vi.useFakeTimers();
    start();
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    auth.acceptSession('old', 61, sid);
    await vi.advanceTimersByTimeAsync(120000);
    http.expectNone('/api/auth/refresh');
  });
  it('un timeout refresh conserve la distinction indisponible', async () => {
    vi.useFakeTimers();
    start();
    const pending = auth.restore();
    const request = http.expectOne('/api/auth/refresh');
    await vi.advanceTimersByTimeAsync(10001);
    expect(await pending).toBe(false);
    expect(request.cancelled).toBe(true);
    expect(auth.state()).toBe('RESTORATION_UNAVAILABLE');
  });
  it('un nouveau sid invalide les anciens stores', async () => {
    start();
    auth.acceptSession('old', 900, sid);
    const generation = auth.generation();
    const pending = auth.login({ phoneNumber: '+221771234567', password: 'synthetic-password' });
    http
      .expectOne('/api/auth/login')
      .flush({ ...response(), sessionId: '22222222-2222-4222-8222-222222222222' });
    await pending;
    expect(auth.generation()).toBe(generation + 1);
  });

  it('403 ne provoque aucune boucle de refresh automatique', async () => {
    vi.useFakeTimers();
    start();
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    auth.acceptSession('old', 61, sid);
    const pending = auth.refresh();
    http.expectOne('/api/auth/refresh').flush({}, { status: 403, statusText: 'Forbidden' });
    await pending;
    await vi.advanceTimersByTimeAsync(180000);
    document.dispatchEvent(new Event('pointerdown'));
    http.expectNone('/api/auth/refresh');
  });
});
