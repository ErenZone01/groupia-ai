import { orderTrackingInterceptor } from '../core/http/order-tracking.interceptor';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideApiConfiguration } from '../api/generated/api-configuration';
import { authInterceptor } from '../core/http/auth.interceptor';
export const apiTestProviders = () => [
  provideRouter([]),
  provideApiConfiguration(''),
  provideHttpClient(withInterceptors([authInterceptor, orderTrackingInterceptor])),
  provideHttpClientTesting(),
];
