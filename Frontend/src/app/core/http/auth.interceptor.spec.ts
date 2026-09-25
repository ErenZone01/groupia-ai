import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../auth/auth.service';
import { authInterceptor } from './auth.interceptor';
describe('Auth interceptor', () => {
  let http: HttpClient, backend: HttpTestingController, auth: AuthService;
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.acceptSession('test-secret', 60);
  });
  afterEach(() => backend.verify());
  it('ajoute Bearer aux seules routes privées Groupia', () => {
    http.get('/api/organizations').subscribe();
    const req = backend.expectOne('/api/organizations');
    expect(req.request.headers.get('Authorization')).toBe('Bearer test-secret');
    req.flush({});
  });
  it.each([
    'https://external.example/api/orders',
    '/api-evil/orders',
    '/api/public/groupings/demo',
    '/api/auth/login',
  ])('ne divulgue pas le token à %s', (url) => {
    http.get(url).subscribe();
    const req = backend.expectOne(url);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });
  it('efface la session lors d’un 401 de sa requête', () => {
    http.get('/api/private').subscribe({ error: () => {} });
    backend.expectOne('/api/private').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.token()).toBeNull();
  });
  it('un ancien 401 ne détruit pas une nouvelle session', () => {
    http.get('/api/private').subscribe({ error: () => {} });
    auth.acceptSession('new-token', 60);
    backend.expectOne('/api/private').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.token()).toBe('new-token');
  });
});
