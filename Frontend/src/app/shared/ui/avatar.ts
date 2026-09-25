import { Component, computed, input } from '@angular/core';
import { initials } from '../utils/initials';
@Component({
  selector: 'g-avatar',
  template: '<span [attr.aria-label]="name()" role="img">{{initials()}}</span>',
  styles: `
    span {
      width: 38px;
      height: 38px;
      display: grid;
      place-items: center;
      background: var(--accent-soft);
      color: var(--warning-text);
      border: 2px solid var(--surface);
      border-radius: var(--pill);
      font-size: var(--text-sm);
      font-weight: 600;
    }
  `,
})
export class Avatar {
  readonly name = input('Ma boutique');
  readonly initials = computed(() => initials(this.name()));
}
