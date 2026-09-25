import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideApiConfiguration } from './api/generated/api-configuration';
import { routes } from './app.routes';
import { authInterceptor } from './core/http/auth.interceptor';
export const appConfig: ApplicationConfig = {
  providers: [
    provideApiConfiguration(''),
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withInMemoryScrolling({ scrollPositionRestoration: 'enabled' })),
    provideHttpClient(withInterceptors([authInterceptor])),
  ],
};
