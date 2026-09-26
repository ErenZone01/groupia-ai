import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpContext } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { apiTestProviders } from '../../testing/api-test';
import { ORDER_TRACKING_TOKEN } from './order-tracking.interceptor';
describe('Périmètre du token de suivi', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: apiTestProviders() }));
  for (const url of [
    'https://checkout.example.test/pay',
    '/api/organizations',
    '/api/public/groupings/example',
  ]) {
    it('ne transmet pas le token à ' + url, () => {
      TestBed.inject(HttpClient)
        .get(url, { context: new HttpContext().set(ORDER_TRACKING_TOKEN, 'private-token') })
        .subscribe();
      const http = TestBed.inject(HttpTestingController),
        req = http.expectOne(url);
      expect(req.request.headers.has('X-Order-Tracking-Token')).toBe(false);
      req.flush({});
      http.verify();
    });
  }
});
