import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  provideRouter,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { authGuard } from './auth.guard';
import { AuthService } from '../auth/auth.service';
describe('authGuard (production configuration)', () => {
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
  });
  const run = () =>
    TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url: '/commandes' } as RouterStateSnapshot),
    );
  it('redirige un visiteur anonyme', () => {
    expect(run()).toBeInstanceOf(UrlTree);
    expect((run() as UrlTree).toString()).toContain('/connexion');
  });
  it('accepte une session valide', () => {
    TestBed.inject(AuthService).acceptSession('test', 60);
    expect(run()).toBe(true);
  });
});
