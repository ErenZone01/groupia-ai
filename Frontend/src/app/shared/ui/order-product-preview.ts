import { Component, input } from '@angular/core';
import { ProductMediaSummary } from '../../api/generated/models';
import { ProductThumbnail } from './product-thumbnail';
import { Icon } from './icon';

/** Identifies products using immutable IDs supplied by the order read model. */
@Component({
  selector: 'g-order-product-preview',
  imports: [ProductThumbnail, Icon],
  template: `
    @if (ids().length) {
      @for (id of ids(); track id) {
        <g-product-thumbnail [src]="media()[id]?.primaryThumbnailUrl ?? ''"
          alt="Miniature du produit commandé" [size]="32" />
      }
      @if (count() > ids().length) {
        <span class="more">+{{ count() - ids().length }}</span>
      }
    } @else {
      <span class="fallback"><g-icon name="box" /> Articles</span>
    }
  `,
  styles: `
    :host { display:inline-flex; align-items:center; gap:3px; min-width:0; max-width:100%; }
    .fallback { display:inline-flex; align-items:center; gap:var(--s2);
      color:var(--muted); font-size:var(--text-xs); }
    .fallback g-icon { width:18px; height:18px; }
    .more { display:inline-grid; place-items:center; min-width:30px; height:30px; padding:0 5px;
      font-size:var(--text-xs); border-radius:var(--radius-sm); color:var(--muted);
      background:var(--surface-alt); }
  `,
})
export class OrderProductPreview {
  readonly ids = input<readonly string[]>([]);
  readonly count = input(0);
  readonly media = input<Record<string, ProductMediaSummary>>({});
}
