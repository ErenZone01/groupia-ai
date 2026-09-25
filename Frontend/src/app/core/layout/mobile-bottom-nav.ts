import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { mobileNavigation } from '../config/navigation';
import { Icon } from '../../shared/ui/icon';
@Component({
  selector: 'g-mobile-bottom-nav',
  imports: [RouterLink, RouterLinkActive, Icon],
  template: `<nav aria-label="Navigation mobile">
    @for (item of items; track item.path) {
      <a [routerLink]="item.path" routerLinkActive="active" ariaCurrentWhenActive="page"
        ><g-icon [name]="item.icon" /><span>{{ item.shortLabel }}</span></a
      >
    }
  </nav>`,
  styles: `
    :host {
      display: none;
    }
    nav {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      height: var(--bottom-nav-height);
      background: var(--surface);
      border-top: 1px solid var(--border);
    }
    a {
      display: flex;
      flex-direction: column;
      justify-content: center;
      align-items: center;
      gap: var(--s1);
      color: var(--muted);
      font-size: var(--text-xs);
    }
    a.active {
      color: var(--primary);
      font-weight: 600;
    }
    a.active g-icon {
      background: var(--primary-soft);
      border-radius: var(--radius-sm);
      box-shadow: 0 0 0 6px var(--primary-soft);
    }
    a:hover {
      background: var(--surface-alt);
    }
    @media (max-width: 1023px) {
      :host {
        display: block;
        position: fixed;
        bottom: 0;
        left: 0;
        right: 0;
        z-index: 30;
        padding-bottom: env(safe-area-inset-bottom);
        background: var(--surface);
      }
    }
  `,
})
export class MobileBottomNav {
  readonly items = mobileNavigation;
}
