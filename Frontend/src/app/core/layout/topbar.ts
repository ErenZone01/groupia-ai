import { Component, computed, ElementRef, HostListener, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ThemeService, ThemePreference } from '../theme/theme.service';
import { BoutiqueContextService } from '../boutique/boutique-context.service';
import { AuthService } from '../auth/auth.service';
import { Button } from '../../shared/ui/button';
import { navigation } from '../config/navigation';
import { SearchInput } from '../../shared/forms/search-input';
import { Select } from '../../shared/forms/select';
import { Avatar } from '../../shared/ui/avatar';
import { Brand } from '../../shared/ui/brand';
import { Icon } from '../../shared/ui/icon';
import { MobileMenuService } from './mobile-menu.service';

@Component({
  selector: 'g-topbar',
  imports: [RouterLink, SearchInput, Select, Avatar, Brand, Icon, Button],
  template: `<header>
    <a class="mobile-brand" routerLink="/app/accueil" aria-label="Groupia, accueil"><g-brand /></a>
    <div class="search">
      <g-search-input
        label="Rechercher une page"
        placeholder="Rechercher une page de votre espace…"
        [(value)]="query"
      />
      @if (query()) {
        <div class="results">
          @for (item of results(); track item.path) {
            <a [routerLink]="item.path" (click)="query.set('')"
              ><g-icon [name]="item.icon" />{{ item.label }}</a
            >
          } @empty {
            <p role="status">Aucune page trouvée.</p>
          }
        </div>
      }
    </div>
    <div class="tools">
      <details #quickCreate class="quick-create">
        <summary aria-label="Créer" title="Créer">
          <g-icon name="plus" /><span class="create-label">Créer</span>
        </summary>
        <nav class="create-options" aria-label="Actions de création">
          <a routerLink="/app/groupages/nouveau" (click)="closeCreation()">
            <g-icon name="group" /><span>Créer un groupage</span>
          </a>
          <a routerLink="/app/products" (click)="closeCreation()">
            <g-icon name="box" /><span>Ajouter un produit dans le catalogue</span>
          </a>
        </nav>
      </details>
      <button type="button" class="menu-trigger" aria-label="Menu" aria-controls="mobile-menu-panel"
        aria-haspopup="dialog" [attr.aria-expanded]="mobileMenu.opened()"
        (click)="openMenu($event)" title="Ouvrir le menu">
        <g-icon name="menu" /><span>Menu</span>
      </button>
      <label class="theme"><span class="sr-only">Thème</span>
        <select
          gSelect
          aria-label="Thème"
          [value]="theme.preference()"
          (change)="changeTheme($any($event.target).value)"
        >
          <option value="light">Clair</option>
          <option value="dark">Sombre</option>
          <option value="system">Système</option>
        </select>
      </label>
      <span class="divider"></span>
      <a routerLink="/app/boutique" class="profile" aria-label="Ouvrir ma boutique">
        <g-avatar [name]="context.selected()?.name || 'Ma boutique'" />
        <span>
          <strong>{{ context.selected()?.name || 'Ma boutique' }}</strong>
          <small>Gérante</small>
        </span>
      </a>
      <button gButton variant="ghost" class="logout" (click)="auth.logout()">Se déconnecter</button>
    </div>
  </header>`,
  styleUrl: './topbar.scss',
})
export class Topbar {
  readonly theme = inject(ThemeService);
  readonly query = signal('');
  readonly context = inject(BoutiqueContextService);
  readonly auth = inject(AuthService);
  readonly mobileMenu = inject(MobileMenuService);
  private readonly root = inject(ElementRef<HTMLElement>);
  private readonly quickCreate = viewChild<ElementRef<HTMLDetailsElement>>('quickCreate');
  readonly results = computed(() =>
    navigation.filter((item) =>
      item.label.toLocaleLowerCase('fr').includes(this.query().toLocaleLowerCase('fr')),
    ),
  );

  openMenu(event: MouseEvent) {
    const element = event.currentTarget;
    if (element instanceof HTMLElement) {
      this.closeCreation();
      this.mobileMenu.requestOpen(element);
    }
  }

  closeCreation(restoreFocus = false) {
    const details = this.quickCreate()?.nativeElement;
    if (!details?.open) return;
    details.open = false;
    if (restoreFocus) details.querySelector('summary')?.focus();
  }

  @HostListener('document:click', ['$event'])
  closeOutside(event: MouseEvent) {
    if (!this.root.nativeElement.contains(event.target as Node)) this.closeCreation();
    else if (!this.quickCreate()?.nativeElement.contains(event.target as Node)) this.closeCreation();
  }

  @HostListener('document:keydown.escape')
  closeCreationOnEscape() {
    this.closeCreation(true);
  }

  changeTheme(value: ThemePreference) {
    this.theme.setPreference(value);
  }
}
