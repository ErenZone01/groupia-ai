import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';
describe('ThemeService', () => {
  let listeners: Set<(event: MediaQueryListEvent) => void>;
  let media: MediaQueryList;
  beforeEach(() => {
    localStorage.clear();
    listeners = new Set();
    media = {
      matches: false,
      addEventListener: (_type: string, fn: (e: MediaQueryListEvent) => void) => listeners.add(fn),
      removeEventListener: (_type: string, fn: (e: MediaQueryListEvent) => void) =>
        listeners.delete(fn),
    } as unknown as MediaQueryList;
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => media),
    );
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  it('utilise le système par défaut', () => {
    const theme = TestBed.inject(ThemeService);
    expect(theme.preference()).toBe('system');
    expect(theme.resolved()).toBe('light');
  });
  it('restaure le thème sombre enregistré', () => {
    localStorage.setItem('groupia.theme', 'dark');
    const theme = TestBed.inject(ThemeService);
    expect(theme.resolved()).toBe('dark');
    expect(document.documentElement.dataset['theme']).toBe('dark');
  });
  it('ignore une préférence corrompue', () => {
    localStorage.setItem('groupia.theme', 'invalid');
    expect(TestBed.inject(ThemeService).preference()).toBe('system');
  });
  it.each(['light', 'dark', 'system'] as const)('persiste le choix %s', (choice) => {
    const theme = TestBed.inject(ThemeService);
    theme.setPreference(choice);
    expect(localStorage.getItem('groupia.theme')).toBe(choice);
    expect(theme.preference()).toBe(choice);
  });
  it('suit les changements du système', () => {
    const theme = TestBed.inject(ThemeService);
    listeners.forEach((fn) => fn({ matches: true } as MediaQueryListEvent));
    expect(theme.resolved()).toBe('dark');
    expect(document.documentElement.style.colorScheme).toBe('dark');
  });
  it('préserve le choix explicite malgré un changement système', () => {
    const theme = TestBed.inject(ThemeService);
    theme.setPreference('light');
    listeners.forEach((fn) => fn({ matches: true } as MediaQueryListEvent));
    expect(theme.resolved()).toBe('light');
  });
  it('fonctionne lorsque le stockage est bloqué', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('blocked');
    });
    const theme = TestBed.inject(ThemeService);
    expect(() => theme.setPreference('dark')).not.toThrow();
    expect(theme.resolved()).toBe('dark');
  });
  it('synchronise le thème entre onglets', () => {
    const theme = TestBed.inject(ThemeService);
    localStorage.setItem('groupia.theme', 'dark');
    window.dispatchEvent(new StorageEvent('storage', { key: 'groupia.theme' }));
    expect(theme.resolved()).toBe('dark');
  });
  it('détache les écouteurs à la destruction', () => {
    TestBed.inject(ThemeService);
    expect(listeners.size).toBe(1);
    TestBed.resetTestingModule();
    expect(listeners.size).toBe(0);
  });
});
