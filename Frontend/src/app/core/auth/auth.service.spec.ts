import { apiTestProviders } from '../../testing/api-test';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';
describe('AuthService', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    sessionStorage.clear();
    localStorage.clear();
  });
  afterEach(() => vi.restoreAllMocks());
  it('démarre anonyme', () => expect(TestBed.inject(AuthService).token()).toBeNull());
  it('stocke le JWT seulement en session', () => {
    const auth = TestBed.inject(AuthService);
    auth.acceptSession('test-token', 60);
    expect(auth.token()).toBe('test-token');
    expect(auth.signedIn()).toBe(true);
    expect(localStorage.getItem('groupia.session')).toBeNull();
    expect(sessionStorage.getItem('groupia.session')).not.toBeNull();
  });
  it('restaure une session encore valide', () => {
    sessionStorage.setItem(
      'groupia.session',
      JSON.stringify({ accessToken: 'restored', expiresAt: Date.now() + 60000 }),
    );
    expect(TestBed.inject(AuthService).token()).toBe('restored');
  });
  it('efface un token expiré avant utilisation', () => {
    const auth = TestBed.inject(AuthService);
    auth.acceptSession('test', 1);
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 2000);
    expect(auth.token()).toBeNull();
    expect(sessionStorage.getItem('groupia.session')).toBeNull();
  });
  it('ignore les données corrompues', () => {
    sessionStorage.setItem('groupia.session', '{');
    expect(TestBed.inject(AuthService).token()).toBeNull();
  });
  it('déconnecte et supprime la session', () => {
    const auth = TestBed.inject(AuthService);
    auth.acceptSession('test', 60);
    auth.clear();
    expect(auth.token()).toBeNull();
    expect(auth.signedIn()).toBe(false);
  });
  it('refuse une durée invalide', () => {
    const auth = TestBed.inject(AuthService);
    auth.acceptSession('test', NaN);
    expect(auth.token()).toBeNull();
  });
  it('reste utilisable sans sessionStorage', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('blocked');
    });
    const auth = TestBed.inject(AuthService);
    auth.acceptSession('test', 60);
    expect(auth.token()).toBe('test');
  });
});
