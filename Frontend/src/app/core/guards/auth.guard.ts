import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { environment } from '../config/environment';
export const authGuard: CanActivateFn = (_route, state) =>
  !!inject(AuthService).token() ||
  environment.preview ||
  inject(Router).createUrlTree(['/connexion'], { queryParams: { returnUrl: state.url } });
