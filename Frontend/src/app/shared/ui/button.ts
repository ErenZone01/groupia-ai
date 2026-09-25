import { Directive, HostListener, input } from '@angular/core';
@Directive({
  selector: 'button[gButton],a[gButton]',
  host: {
    '[class]': "'g-button g-button--'+variant()",
    '[attr.disabled]': 'disabled()||loading()?true:null',
    '[attr.aria-disabled]': 'disabled()||loading()?true:null',
    '[attr.aria-busy]': 'loading()?true:null',
    '[attr.tabindex]': 'disabled()||loading()?-1:null',
  },
})
export class Button {
  readonly variant = input<'primary' | 'secondary' | 'ghost' | 'accent'>('primary');
  readonly disabled = input(false);
  readonly loading = input(false);
  @HostListener('click', ['$event']) block(event: Event) {
    if (this.disabled() || this.loading()) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }
}
