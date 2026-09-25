import { Component, effect, inject, signal } from '@angular/core';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { Card } from '../../shared/ui/card';
import { Button } from '../../shared/ui/button';
import { Badge } from '../../shared/ui/badge';
import { EmptyState } from '../../shared/feedback/empty-state';
import { Skeleton } from '../../shared/feedback/skeleton';
import { CatalogStore } from './catalog.store';
import { ProductForm } from './product-form';
import { ToastService } from '../../shared/feedback/toast.service';
import { statusLabel, variantLabel } from '../../shared/utils/display';
@Component({
  selector: 'g-catalog-page',
  providers: [CatalogStore],
  imports: [Card, Button, Badge, EmptyState, Skeleton, ProductForm],
  template: `
    <div class="page-heading">
      <div>
        <p class="eyebrow">Votre catalogue</p>
        <h1>Produits</h1>
        <p>Des produits réutilisables dans tous vos groupages.</p>
      </div>
      <button gButton (click)="creating.set(true)">Créer un produit</button>
    </div>
    @if (creating() && context.selectedId()) {
      <g-card
        ><g-product-form
          [organizationId]="context.selectedId()!"
          (saved)="created()"
          (cancelled)="creating.set(false)"
      /></g-card>
    }
    @if (store.loading()) {
      <g-skeleton /><g-skeleton />
    } @else if (store.error()) {
      <p role="alert" class="form-error">{{ store.error() }}</p>
      <button gButton (click)="load()">Réessayer</button>
    } @else if (!store.products().length) {
      <g-empty-state
        title="Votre catalogue commence ici"
        description="Ajoutez votre premier produit pour préparer un groupage."
        icon="box"
      />
    } @else {
      <div class="product-grid">
        @for (product of store.products(); track product.id) {
          <g-card
            ><div class="section-heading">
              <h2>{{ product.name }}</h2>
              <g-badge>{{ statusLabel(product.status) }}</g-badge>
            </div>
            <p class="description">{{ product.description || 'Aucune description' }}</p>
            <h3>Variantes</h3>
            <ul class="variant-list">
              @for (variant of product.variants; track variant.id) {
                <li>
                  <span>{{ variantLabel(variant.attributes) }}</span
                  ><g-badge [tone]="variant.active ? 'positive' : 'neutral'">{{
                    variant.active ? 'Disponible' : 'Inactive'
                  }}</g-badge>
                </li>
              }
            </ul></g-card
          >
        }
      </div>
    }
    @if (store.totalPages() > 1) {
      <nav class="actions" aria-label="Pages du catalogue">
        <button
          gButton
          variant="secondary"
          [disabled]="store.page() === 0 || store.loading()"
          (click)="load(store.page() - 1)"
        >
          Précédent</button
        ><span>Page {{ store.page() + 1 }} / {{ store.totalPages() }}</span
        ><button
          gButton
          variant="secondary"
          [disabled]="store.page() + 1 >= store.totalPages() || store.loading()"
          (click)="load(store.page() + 1)"
        >
          Suivant
        </button>
      </nav>
    }
  `,
  styles: `
    g-card {
      display: block;
      margin-bottom: var(--s5);
    }
    .product-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 300px), 1fr));
      gap: var(--s5);
    }
    .description {
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      color: var(--muted);
      margin: var(--s3) 0 var(--s5);
    }
    .variant-list {
      list-style: none;
      padding: 0;
      margin: var(--s3) 0 0;
    }
    .variant-list li {
      display: flex;
      justify-content: space-between;
      gap: var(--s3);
      padding: var(--s3) 0;
      border-top: 1px solid var(--border);
    }
    .variant-list span {
      overflow-wrap: anywhere;
    }
  `,
})
export class CatalogPage {
  readonly context = inject(BoutiqueContextService);
  readonly store = inject(CatalogStore);
  readonly creating = signal(false);
  readonly statusLabel = statusLabel;
  readonly variantLabel = variantLabel;
  private readonly toast = inject(ToastService);
  constructor() {
    effect(() => {
      const id = this.context.selectedId();
      this.creating.set(false);
      if (id) void this.store.load(id);
    });
  }
  load(page = 0) {
    const id = this.context.selectedId();
    if (id) void this.store.load(id, page);
  }
  created() {
    this.creating.set(false);
    this.toast.show('Produit créé.');
    this.load();
  }
}
