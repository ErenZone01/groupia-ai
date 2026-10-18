import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService),
    router = inject(Router);
  if (await auth.restore()) return true;
  return router.createUrlTree(
    [auth.state() === 'RESTORATION_UNAVAILABLE' ? '/session-indisponible' : '/connexion'],
    { queryParams: { returnUrl: state.url } },
  );
};
