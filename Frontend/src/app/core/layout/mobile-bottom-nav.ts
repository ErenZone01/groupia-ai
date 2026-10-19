import { DOCUMENT } from '@angular/common';
import {
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  IsActiveMatchOptions,
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
} from '@angular/router';
import { filter, map } from 'rxjs';
import { primaryMobileNavigation, secondaryMobileNavigation } from '../config/navigation';
import { AuthService } from '../auth/auth.service';
import { Icon } from '../../shared/ui/icon';

@Component({
  selector: 'g-mobile-bottom-nav',
  imports: [RouterLink, RouterLinkActive, Icon],
  templateUrl: './mobile-bottom-nav.html',
  styleUrl: './mobile-bottom-nav.scss',
})
export class MobileBottomNav {
  readonly primary = primaryMobileNavigation;
  readonly secondary = secondaryMobileNavigation;
  readonly matchOptions: IsActiveMatchOptions = {
    paths: 'subset',
    queryParams: 'ignored',
    fragment: 'ignored',
    matrixParams: 'ignored',
  };
  readonly opened = signal(false);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly document = inject(DOCUMENT);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('sheet');
  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('more');
  private readonly desktop = this.document.defaultView?.matchMedia('(min-width: 1024px)');
  private readonly navigation = this.router.events.pipe(
    filter((event) => event instanceof NavigationEnd),
  );
  private readonly url = toSignal(this.navigation.pipe(map((event) => event.urlAfterRedirects)), {
    initialValue: this.router.url,
  });
  private previousOverflow: string | undefined;
  readonly secondaryActive = computed(() => {
    this.url();
    return (
      !this.primary.some((item) => this.router.isActive(item.path, this.matchOptions)) &&
      this.secondary.some((item) => this.router.isActive(item.path, this.matchOptions))
    );
  });

  constructor() {
    this.navigation.pipe(takeUntilDestroyed()).subscribe(() => this.close());
    const resize = () => {
      if (this.desktop?.matches) this.close(false);
    };
    this.desktop?.addEventListener('change', resize);
    inject(DestroyRef).onDestroy(() => {
      this.desktop?.removeEventListener('change', resize);
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
    if (restoreFocus && !this.desktop?.matches) this.trigger().nativeElement.focus();
  }

  cancel(event: Event) {
    event.preventDefault();
    this.close();
  }

  backdrop(event: MouseEvent) {
    if (event.target !== this.dialog().nativeElement) return;
    const rect = this.dialog().nativeElement.getBoundingClientRect();
    if (
      event.clientX < rect.left ||
      event.clientX > rect.right ||
      event.clientY < rect.top ||
      event.clientY > rect.bottom
    )
      this.close();
  }

  keepFocus(event: KeyboardEvent) {
    if (event.key !== 'Tab') return;
    const elements = this.dialog().nativeElement.querySelectorAll<HTMLElement>('a[href], button');
    const first = elements[0],
      last = elements[elements.length - 1];
    if (event.shiftKey && this.document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && this.document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  logout() {
    this.close();
    void this.auth.logout();
  }

  private restoreScroll() {
    if (this.previousOverflow === undefined) return;
    this.document.documentElement.style.overflow = this.previousOverflow;
    this.previousOverflow = undefined;
  }
}
