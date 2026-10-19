import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import {
  navigation,
  primaryMobileNavigation,
  secondaryMobileNavigation,
} from '../config/navigation';
import { MobileBottomNav } from './mobile-bottom-nav';

@Component({ template: '' })
class Destination {}

describe('MobileBottomNav', () => {
  let fixture: ComponentFixture<MobileBottomNav>;
  let el: HTMLElement;
  let dialog: HTMLDialogElement;
  let more: HTMLButtonElement;
  let router: Router;
  let media: {
    matches: boolean;
    addEventListener: ReturnType<typeof vi.fn>;
    removeEventListener: ReturnType<typeof vi.fn>;
  };
  const auth = { logout: vi.fn().mockResolvedValue(undefined) };

  beforeEach(async () => {
    media = { matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() };
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => media),
    );
    auth.logout.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', component: Destination }]),
        { provide: AuthService, useValue: auth },
      ],
    });
    router = TestBed.inject(Router);
    await router.navigateByUrl('/app/accueil');
    fixture = TestBed.createComponent(MobileBottomNav);
    el = fixture.nativeElement;
    fixture.detectChanges();
    dialog = el.querySelector('dialog')!;
    more = el.querySelector('.more')!;
    dialog.showModal = vi.fn(() => {
      dialog.open = true;
      dialog.querySelector<HTMLButtonElement>('.close')!.focus();
    });
    dialog.close = vi.fn(() => {
      dialog.open = false;
      dialog.dispatchEvent(new Event('close'));
    });
    await fixture.whenStable();
  });
  afterEach(() => {
    fixture.destroy();
    document.documentElement.style.overflow = '';
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  function open() {
    more.click();
    fixture.detectChanges();
  }

  it('affiche exactement quatre liens et Plus, avec Produits accessible', () => {
    const entries = [...el.querySelectorAll('.bar > a, .bar > button')];
    expect(entries.map((item) => item.textContent?.trim())).toEqual([
      'Accueil',
      'Groupages',
      'Produits',
      'Commandes',
      'Plus',
    ]);
    expect(entries[2].getAttribute('href')).toBe('/app/products');
    expect(entries[4].tagName).toBe('BUTTON');
    expect(more.getAttribute('aria-expanded')).toBe('false');
    expect(more.getAttribute('aria-haspopup')).toBe('dialog');
  });

  it('garde les destinations desktop et réutilise leurs objets pour le mobile', () => {
    expect(navigation.map(({ path, label, icon }) => [path, label, icon])).toEqual([
      ['/app/accueil', 'Tableau de bord', 'home'],
      ['/app/groupages', 'Groupages', 'group'],
      ['/app/products', 'Produits', 'box'],
      ['/app/orders', 'Commandes', 'orders'],
      ['/app/paiements', 'Paiements', 'card'],
      ['/app/boutique', 'Ma boutique', 'store'],
      ['/app/versements', 'Mes versements', 'card'],
      ['/app/abonnement', 'Abonnement', 'card'],
    ]);
    expect(new Set([...primaryMobileNavigation, ...secondaryMobileNavigation])).toEqual(
      new Set(navigation),
    );
  });

  it('ouvre un dialogue nommé avec les quatre destinations et logout', () => {
    open();
    expect(dialog.open).toBe(true);
    expect(more.getAttribute('aria-controls')).toBe(dialog.id);
    expect(more.getAttribute('aria-expanded')).toBe('true');
    expect(el.querySelector('#' + dialog.getAttribute('aria-labelledby'))?.textContent).toBe(
      'Plus de pages',
    );
    expect([...dialog.querySelectorAll('a')].map((a) => a.textContent?.trim())).toEqual([
      'Ma boutique',
      'Paiements',
      'Versements',
      'Abonnement',
    ]);
    expect(dialog.querySelector('.logout')?.textContent?.trim()).toBe('Se déconnecter');
    expect(document.documentElement.style.overflow).toBe('hidden');
  });

  it('ferme et navigue après clic sur une destination', async () => {
    open();
    dialog.querySelector<HTMLAnchorElement>('[href="/app/paiements"]')!.click();
    await fixture.whenStable();
    expect(router.url).toBe('/app/paiements');
    expect(dialog.open).toBe(false);
    expect(document.documentElement.style.overflow).toBe('');
  });

  it('ferme au clic sur le backdrop mais pas sur le panneau', () => {
    open();
    vi.spyOn(dialog, 'getBoundingClientRect').mockReturnValue({
      left: 0,
      right: 390,
      top: 100,
      bottom: 500,
    } as DOMRect);
    dialog.dispatchEvent(new MouseEvent('click', { clientX: 50, clientY: 150 }));
    expect(dialog.open).toBe(true);
    dialog.dispatchEvent(new MouseEvent('click', { clientX: 50, clientY: 20 }));
    expect(dialog.open).toBe(false);
    expect(document.activeElement).toBe(more);
  });

  it('traite Escape via cancel natif et restitue le focus et le scroll', () => {
    document.documentElement.style.overflow = 'auto';
    open();
    const cancel = new Event('cancel', { cancelable: true });
    dialog.dispatchEvent(cancel);
    fixture.detectChanges();
    expect(cancel.defaultPrevented).toBe(true);
    expect(dialog.open).toBe(false);
    expect(more.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(more);
    expect(document.documentElement.style.overflow).toBe('auto');
  });

  it('boucle le focus au clavier entre fermer et déconnexion', () => {
    open();
    const first = dialog.querySelector<HTMLButtonElement>('.close')!;
    const last = dialog.querySelector<HTMLButtonElement>('.logout')!;
    first.focus();
    first.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }),
    );
    expect(document.activeElement).toBe(last);
    last.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }),
    );
    expect(document.activeElement).toBe(first);
  });

  it('appelle exclusivement le logout existant et ferme le menu', () => {
    open();
    dialog.querySelector<HTMLButtonElement>('.logout')!.click();
    expect(auth.logout).toHaveBeenCalledOnce();
    expect(dialog.open).toBe(false);
    expect(document.documentElement.style.overflow).toBe('');
  });

  it.each([
    '/app/paiements',
    '/app/paiements/requests/123',
    '/app/paiements/reviews/123',
    '/app/versements',
    '/app/abonnement',
    '/app/boutique',
  ])('active Plus et le lien secondaire pour %s', async (path) => {
    await router.navigateByUrl(path + '?page=2#details');
    await fixture.whenStable();
    fixture.detectChanges();
    expect(more.classList.contains('active')).toBe(true);
    expect(el.querySelectorAll('.bar a.active')).toHaveLength(0);
    expect(dialog.querySelectorAll('a[aria-current="page"]')).toHaveLength(1);
  });

  it.each([
    ['/app/accueil', 'Accueil'],
    ['/app/products/123', 'Produits'],
    ['/app/groupages/123', 'Groupages'],
    ['/app/orders/123', 'Commandes'],
  ])('active seulement la destination principale pour %s', async (path, label) => {
    await router.navigateByUrl(path);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(more.classList.contains('active')).toBe(false);
    expect(el.querySelector('.bar a[aria-current="page"]')?.textContent?.trim()).toBe(label);
  });

  it('ne confond pas un préfixe de texte avec une route secondaire', async () => {
    await router.navigateByUrl('/app/paiements-autre');
    await fixture.whenStable();
    expect(fixture.componentInstance.secondaryActive()).toBe(false);
  });

  it('ferme également après une navigation externe au menu', async () => {
    open();
    await router.navigateByUrl('/app/orders');
    expect(dialog.open).toBe(false);
    expect(document.documentElement.style.overflow).toBe('');
  });

  it('ferme au breakpoint desktop et ne peut pas y être rouvert', () => {
    open();
    media.matches = true;
    media.addEventListener.mock.calls[0][1]();
    expect(dialog.open).toBe(false);
    more.click();
    expect(dialog.open).toBe(false);
    expect(document.documentElement.style.overflow).toBe('');
  });

  it('restaure le scroll quand le layout est détruit', () => {
    open();
    fixture.destroy();
    expect(document.documentElement.style.overflow).toBe('');
    expect(media.removeEventListener).toHaveBeenCalledOnce();
  });
});
