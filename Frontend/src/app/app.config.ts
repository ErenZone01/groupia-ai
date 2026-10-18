import { customerAccessInterceptor } from './core/http/customer-access.interceptor';
import { orderTrackingInterceptor } from './core/http/order-tracking.interceptor';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideApiConfiguration } from './api/generated/api-configuration';
import { routes } from './app.routes';
import { authInterceptor } from './core/http/auth.interceptor';
import { AuthService } from './core/auth/auth.service';
export const appConfig: ApplicationConfig = {
  providers: [
    provideAppInitializer(() => {
      void inject(AuthService).restore();
    }),
    provideApiConfiguration(''),
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withInMemoryScrolling({ scrollPositionRestoration: 'enabled' })),
    provideHttpClient(
      withInterceptors([authInterceptor, orderTrackingInterceptor, customerAccessInterceptor]),
    ),
  ],
};
