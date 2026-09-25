import { Component, input, model } from '@angular/core';
import { Icon } from '../ui/icon';
import { Input } from './input';
@Component({
  selector: 'g-search-input',
  imports: [Icon, Input],
  template: `<label
    ><span class="sr-only">{{ label() }}</span
    ><g-icon name="search" /><input
      gInput
      type="search"
      [placeholder]="placeholder()"
      [disabled]="disabled()"
      [value]="value()"
      (input)="value.set($any($event.target).value)"
  /></label>`,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    label {
      display: block;
      position: relative;
    }
    g-icon {
      position: absolute;
      left: var(--s3);
      top: 13px;
      color: var(--muted);
      pointer-events: none;
    }
    input {
      padding-left: var(--s10);
      background: var(--surface-alt);
      border-color: transparent;
    }
  `,
})
export class SearchInput {
  readonly label = input('Rechercher');
  readonly placeholder = input('Rechercher…');
  readonly disabled = input(false);
  readonly value = model('');
}
