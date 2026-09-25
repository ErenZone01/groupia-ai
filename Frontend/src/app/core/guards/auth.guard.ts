import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
export const authGuard: CanActivateFn = (_route, state) =>
  !!inject(AuthService).token() ||
  inject(Router).createUrlTree(['/connexion'], { queryParams: { returnUrl: state.url } });
