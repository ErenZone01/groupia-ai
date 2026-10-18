import { apiTestProviders } from '../../testing/api-test';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, UrlTree } from '@angular/router';
import { authGuard } from './auth.guard';
import { AuthService } from '../auth/auth.service';
describe('authGuard restoration', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: apiTestProviders() });
  });
  const run = () =>
    TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url: '/app/orders' } as RouterStateSnapshot),
    ) as Promise<boolean | UrlTree>;
  it('attend le refresh avant de décider', async () => {
    const result = run();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/auth/refresh')
      .flush({}, { status: 401, statusText: 'Unauthorized' });
    expect((await result).toString()).toContain('/connexion');
  });
  it('distingue indisponibilité et anonymat', async () => {
    const result = run();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/auth/refresh')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    expect((await result).toString()).toContain('/session-indisponible');
  });
  it('accepte une session valide', async () => {
    TestBed.inject(AuthService).acceptSession('test', 900);
    expect(await run()).toBe(true);
  });
});
