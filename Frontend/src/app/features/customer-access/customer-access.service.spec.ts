import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors, HttpClient, HttpContext } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideApiConfiguration } from '../../api/generated/api-configuration';
import { CustomerAccessService } from './customer-access.service';
import {
  customerAccessInterceptor,
  CUSTOMER_CSRF,
} from '../../core/http/customer-access.interceptor';
describe('Customer access transport', () => {
  let http: HttpTestingController;
  let service: CustomerAccessService;
  const base = '/api/public/customer-access';
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(withInterceptors([customerAccessInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(CustomerAccessService);
  });
  afterEach(() => http.verify());
  it('requests CSRF before OTP and never stores credentials', async () => {
    const local = vi.spyOn(Storage.prototype, 'setItem');
    const promise = service.request('+221771234567');
    http.expectOne(base + '/csrf').flush({ token: 'csrf-test' });
    const request = http.expectOne(base + '/otp/request');
    expect(request.request.headers.get('X-XSRF-TOKEN')).toBe('csrf-test');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.body).toEqual({ phone: '+221771234567' });
    request.flush({ challengeId: 'challenge', message: 'Message neutre' });
    await promise;
    expect(local).not.toHaveBeenCalled();
    local.mockRestore();
  });
  it('does not submit a mutation when CSRF fails', async () => {
    const promise = service.exchange('private-link');
    const rejection = expect(promise).rejects.toBeTruthy();
    http.expectOne(base + '/csrf').flush({}, { status: 503, statusText: 'Unavailable' });
    await rejection;
    http.expectNone(base + '/magic/exchange');
  });
  it('does not automatically repeat a failed OTP attempt', async () => {
    const promise = service.verify('challenge', '123456');
    const rejection = expect(promise).rejects.toBeTruthy();
    http.expectOne(base + '/csrf').flush({ token: 'csrf-test' });
    http
      .expectOne(base + '/otp/verify')
      .flush({ code: 'OTP_INVALID' }, { status: 400, statusText: 'Invalid' });
    await rejection;
  });
  for (const url of [
    'https://external.test/api/public/customer-access/orders',
    '/api/organizations',
  ]) {
    it('never forwards CSRF or credentials to ' + url, () => {
      TestBed.inject(HttpClient)
        .post(url, {}, { context: new HttpContext().set(CUSTOMER_CSRF, 'private-csrf') })
        .subscribe();
      const r = http.expectOne(url);
      expect(r.request.headers.has('X-XSRF-TOKEN')).toBe(false);
      expect(r.request.withCredentials).toBe(false);
      r.flush({});
    });
  }
  it('uses the backend capability without interpreting order status or archive dates', async () => {
    const promise = service.capabilities('GRP-000000000008');
    const request = http.expectOne(base + '/orders/GRP-000000000008/capabilities');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({ canResendTrackingLink: false });
    expect(await promise).toEqual({ canResendTrackingLink: false });
  });
  it('loads paginated orders without a bearer or private URL value', async () => {
    const p = service.orders(2);
    const r = http.expectOne(base + '/orders?page=2&size=20');
    expect(r.request.headers.has('Authorization')).toBe(false);
    r.flush({ items: [], page: 2 });
    await p;
  });
});
