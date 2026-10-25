import { DOCUMENT } from '@angular/common';
import {
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { IsActiveMatchOptions, NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter, map } from 'rxjs';
import { primaryMobileNavigation, mobileMenuSections } from '../config/navigation';
import { AuthService } from '../auth/auth.service';
import { ThemePreference, ThemeService } from '../theme/theme.service';
import { Icon } from '../../shared/ui/icon';
import { MobileMenuService } from './mobile-menu.service';

@Component({
  selector: 'g-mobile-bottom-nav',
  imports: [RouterLink, RouterLinkActive, Icon],
  templateUrl: './mobile-bottom-nav.html',
  styleUrl: './mobile-bottom-nav.scss',
})
export class MobileBottomNav {
  readonly primary = primaryMobileNavigation;
  readonly sections = mobileMenuSections;
  readonly menu = inject(MobileMenuService);
  readonly theme = inject(ThemeService);
  readonly opened = this.menu.opened;
  readonly matchOptions: IsActiveMatchOptions = {
    paths: 'subset',
    queryParams: 'ignored',
    fragment: 'ignored',
    matrixParams: 'ignored',
  };
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly document = inject(DOCUMENT);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('sheet');
  private readonly desktop = this.document.defaultView?.matchMedia('(min-width: 1024px)');
  private readonly navigation = this.router.events.pipe(
    filter((event) => event instanceof NavigationEnd),
  );
  private readonly url = toSignal(this.navigation.pipe(map((event) => event.urlAfterRedirects)), {
    initialValue: this.router.url,
  });
  private previousOverflow: string | undefined;
  private touchX = 0;
  private touchY = 0;
  readonly secondaryActive = computed(() => {
    this.url();
    return this.sections.some((section) =>
      section.items.some((item) => this.router.isActive(item.path, this.matchOptions)),
    );
  });

  constructor() {
    effect(() => {
      const request = this.menu.requested();
      if (request) untracked(() => this.open());
    });
    this.navigation.pipe(takeUntilDestroyed()).subscribe(() => this.close(false));
    const resize = () => {
      if (this.desktop?.matches) this.close(false);
    };
    this.desktop?.addEventListener('change', resize);
    inject(DestroyRef).onDestroy(() => {
      this.desktop?.removeEventListener('change', resize);
      this.close(false);
      this.restoreScroll();
    });
  }

  open() {
    if (this.opened() || this.desktop?.matches) return;
    this.dialog().nativeElement.showModal();
    this.dialog().nativeElement.querySelector<HTMLButtonElement>('.close')?.focus();
    this.previousOverflow = this.document.documentElement.style.overflow;
    this.document.documentElement.style.overflow = 'hidden';
    this.opened.set(true);
  }

  close(restoreFocus = true) {
    if (!this.opened()) return;
    this.opened.set(false);
    this.dialog().nativeElement.close();
    this.restoreScroll();
    const trigger = this.menu.requested()?.element;
    if (restoreFocus && !this.desktop?.matches && trigger?.isConnected) trigger.focus();
  }

  cancel(event: Event) {
    event.preventDefault();
    this.close();
  }

  backdrop(event: MouseEvent) {
    // The modal fills the viewport; the scrollable panel occupies only the right side.
    // Coordinate checks also work when Chromium retargets backdrop clicks to the dialog.
    const panel = this.dialog().nativeElement.querySelector<HTMLElement>('.panel');
    if (!panel) return;
    const rect = panel.getBoundingClientRect();
    if (
      event.clientX < rect.left ||
      event.clientX > rect.right ||
      event.clientY < rect.top ||
      event.clientY > rect.bottom
    ) this.close();
  }

  keepFocus(event: KeyboardEvent) {
    if (event.key !== 'Tab') return;
    const elements = [...this.dialog().nativeElement.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), select:not([disabled])',
    )];
    const first = elements[0], last = elements[elements.length - 1];
    if (event.shiftKey && this.document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && this.document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  }

  touchStart(event: TouchEvent) {
    this.touchX = event.touches[0]?.clientX ?? 0;
    this.touchY = event.touches[0]?.clientY ?? 0;
  }
  touchEnd(event: TouchEvent) {
    const touch = event.changedTouches[0];
    if (touch && touch.clientX - this.touchX > 85 &&
        Math.abs(touch.clientY - this.touchY) < 50) this.close();
  }

  changeTheme(value: string) {
    if (value === 'light' || value === 'dark' || value === 'system')
      this.theme.setPreference(value as ThemePreference);
  }

  logout() {
    this.close(false);
    void this.auth.logout();
  }

  private restoreScroll() {
    if (this.previousOverflow === undefined) return;
    this.document.documentElement.style.overflow = this.previousOverflow;
    this.previousOverflow = undefined;
  }
}
