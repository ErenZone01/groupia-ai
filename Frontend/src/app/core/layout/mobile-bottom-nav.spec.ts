import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import {
  navigation,
  primaryMobileNavigation,
  secondaryMobileNavigation,
  sidebarNavigation,
} from '../config/navigation';
import { MobileBottomNav } from './mobile-bottom-nav';
import { MobileMenuService } from './mobile-menu.service';

@Component({ template: '' })
class Destination {}

describe('MobileBottomNav V6', () => {
  let fixture: ComponentFixture<MobileBottomNav>;
  let el: HTMLElement;
  let dialog: HTMLDialogElement;
  let trigger: HTMLButtonElement;
  let router: Router;
  let menu: MobileMenuService;
  let media: {
    matches: boolean;
    addEventListener: ReturnType<typeof vi.fn>;
    removeEventListener: ReturnType<typeof vi.fn>;
  };
  const auth = { logout: vi.fn().mockResolvedValue(undefined) };

  beforeEach(async () => {
    media = { matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() };
    vi.stubGlobal('matchMedia', vi.fn(() => media));
    auth.logout.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', component: Destination }]),
        { provide: AuthService, useValue: auth },
      ],
    });
    router = TestBed.inject(Router);
    menu = TestBed.inject(MobileMenuService);
    await router.navigateByUrl('/app/accueil');
    fixture = TestBed.createComponent(MobileBottomNav);
    el = fixture.nativeElement;
    fixture.detectChanges();
    dialog = el.querySelector('dialog')!;
    trigger = document.createElement('button');
    trigger.textContent = 'Menu';
    document.body.appendChild(trigger);
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
    trigger.remove();
    document.documentElement.style.overflow = '';
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  async function open() {
    menu.requestOpen(trigger);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('affiche quatre destinations métier et aucune entrée Plus dans la barre', () => {
    const entries = [...el.querySelectorAll('.bar > a, .bar > button')];
    expect(entries.map((item) => item.textContent?.trim())).toEqual([
      'Accueil',
      'Groupages',
      'Commandes',
      'Produits',
    ]);
    expect(el.querySelectorAll('.bar > button')).toHaveLength(0);
  });

  it('possède une source unique et une organisation desktop/mobile cohérente', () => {
    expect(primaryMobileNavigation.map((item) => item.path)).toEqual([
      '/app/accueil', '/app/groupages', '/app/orders', '/app/products',
    ]);
    expect(new Set([...primaryMobileNavigation, ...secondaryMobileNavigation])).toEqual(
      new Set(navigation),
    );
    expect(sidebarNavigation.map((section) => section.label)).toEqual([
      'Espace de travail', 'Finances et analyse', 'Boutique',
    ]);
  });

  it('ouvre un tiroir droit nommé et expose les liens groupés et le thème', async () => {
    await open();
    expect(dialog.open).toBe(true);
    expect(dialog.id).toBe('mobile-menu-panel');
    expect(dialog.getAttribute('aria-labelledby')).toBe('mobile-menu-title');
    expect(el.querySelector('#mobile-menu-title')?.textContent).toBe('Menu Groupia');
    expect([...dialog.querySelectorAll('nav a')].map((a) => a.textContent?.trim())).toEqual([
      'Paiements', 'Versements', 'Statistiques', 'Ma boutique', 'Abonnement',
    ]);
    expect(dialog.querySelector('select')?.getAttribute('id')).toBe('mobile-theme');
    expect(document.documentElement.style.overflow).toBe('hidden');
  });

  it('ferme et navigue après un clic sur une destination', async () => {
    await open();
    dialog.querySelector<HTMLAnchorElement>('[href="/app/paiements"]')!.click();
    await fixture.whenStable();
    expect(router.url).toBe('/app/paiements');
    expect(dialog.open).toBe(false);
    expect(document.documentElement.style.overflow).toBe('');
  });

  it('ferme au backdrop mais pas sur le panneau', async () => {
    await open();
    vi.spyOn(dialog, 'getBoundingClientRect').mockReturnValue({
      left: 70, right: 390, top: 0, bottom: 844,
    } as DOMRect);
    dialog.dispatchEvent(new MouseEvent('click', { clientX: 200, clientY: 150 }));
    expect(dialog.open).toBe(true);
    dialog.dispatchEvent(new MouseEvent('click', { clientX: 12, clientY: 100 }));
    expect(dialog.open).toBe(false);
    expect(document.activeElement).toBe(trigger);
  });

  it('traite Escape, ferme et restitue focus/scroll au bouton de la topbar', async () => {
    document.documentElement.style.overflow = 'auto';
    await open();
    const cancel = new Event('cancel', { cancelable: true });
    dialog.dispatchEvent(cancel);
    expect(cancel.defaultPrevented).toBe(true);
    expect(dialog.open).toBe(false);
    expect(document.activeElement).toBe(trigger);
    expect(document.documentElement.style.overflow).toBe('auto');
  });

  it('boucle le focus entre fermer et déconnexion', async () => {
    await open();
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

  it('appelle le logout existant et remet le scroll', async () => {
    await open();
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
    '/app/statistiques',
  ])('surligne le lien secondaire à %s', async (path) => {
    await router.navigateByUrl(path + '?page=2#details');
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.secondaryActive()).toBe(true);
    expect(dialog.querySelectorAll('a[aria-current="page"]')).toHaveLength(1);
  });

  it.each([
    ['/app/accueil', 'Accueil'],
    ['/app/products/123', 'Produits'],
    ['/app/groupages/123', 'Groupages'],
    ['/app/orders/123', 'Commandes'],
  ])('active uniquement %s dans la barre', async (path, label) => {
    await router.navigateByUrl(path);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.secondaryActive()).toBe(false);
    expect(el.querySelector('.bar a[aria-current="page"]')?.textContent?.trim()).toBe(label);
  });

  it('ferme après navigation externe au menu', async () => {
    await open();
    await router.navigateByUrl('/app/orders');
    expect(dialog.open).toBe(false);
    expect(document.documentElement.style.overflow).toBe('');
  });

  it('ferme au breakpoint desktop et restaure le scroll à la destruction', async () => {
    await open();
    media.matches = true;
    for (const [, change] of media.addEventListener.mock.calls) change({ matches: true });
    expect(dialog.open).toBe(false);
    expect(document.documentElement.style.overflow).toBe('');
  });
});
