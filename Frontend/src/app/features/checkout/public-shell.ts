import { Component, inject, input } from '@angular/core';
import { Brand } from '../../shared/ui/brand';
import { Select } from '../../shared/forms/select';
import { ThemeService } from '../../core/theme/theme.service';
@Component({
  selector: 'g-public-shell',
  host: { class: 'public-flow', '[class.wide]': 'wide()' },
  imports: [Brand, Select],
  template: ` <header>
      <g-brand /><select
        gSelect
        aria-label="Thème"
        [value]="theme.preference()"
        (change)="theme.setPreference($any($event.target).value)"
      >
        <option value="light">Clair</option>
        <option value="dark">Sombre</option>
        <option value="system">Système</option>
      </select>
    </header>
    <main><ng-content /></main>`,
  styles: `
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--s3);
      padding: var(--s5);
      background: var(--surface);
      border-bottom: 1px solid var(--border);
    }
    select {
      width: 110px;
    }
    main {
      max-width: 800px;
      margin: auto;
      padding: var(--s8) var(--s5) calc(var(--s8) + 80px);
      overflow-wrap: anywhere;
    }
    :host(.wide) main {
      max-width: 1320px;
    }
  `,
})
export class PublicShell {
  readonly wide = input(false);
  readonly theme = inject(ThemeService);
}
