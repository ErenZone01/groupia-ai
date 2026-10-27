import { Component, input, signal } from '@angular/core';
import { Icon } from './icon';

/** A reusable compact product image. A missing or broken image never hides its row. */
@Component({
  selector: 'g-product-thumbnail',
  imports: [Icon],
  host: {
    '[style.width.px]': 'size()',
    '[style.height.px]': 'size()',
  },
  template: `
    @if (src() && failedSrc() !== src()) {
      <img [src]="src()" [alt]="alt()" loading="lazy" decoding="async"
           (error)="failedSrc.set(src())" />
    } @else {
      <g-icon name="box" />
    }
  `,
  styles: `
    :host { display:grid; place-items:center; flex-shrink:0; overflow:hidden;
      border-radius:var(--radius-sm); background:var(--surface-alt);
      border:1px solid var(--border); color:var(--muted); }
    img { display:block; width:100%; height:100%; object-fit:cover; }
    g-icon { width:22px; height:22px; }
  `,
})
export class ProductThumbnail {
  readonly src = input('');
  readonly alt = input('Photo du produit');
  readonly size = input(48);
  readonly failedSrc = signal('');
}
