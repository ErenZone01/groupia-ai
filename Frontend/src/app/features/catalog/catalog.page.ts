import { Component, effect, inject, signal, untracked } from '@angular/core';
import { ProductView, ProductMediaSummary } from '../../api/generated/models';
import { AuthService } from '../../core/auth/auth.service';
import { Icon } from '../../shared/ui/icon';
import { ProductGallery } from './product-gallery';
import { ProductMediaApi } from './product-media.api';
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
  imports: [Card, Button, Badge, EmptyState, Skeleton, ProductForm, Icon, ProductGallery],
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
          (saved)="created($event)"
          (cancelled)="creating.set(false)"
      /></g-card>
    }
    @if (selectedProduct(); as selected) {
      @if (context.selectedId(); as org) {
        <g-card
          ><g-product-gallery
            [organizationId]="org"
            [productId]="selected.id!"
            [productName]="selected.name ?? 'Produit'"
            [archived]="selected.status === 'ARCHIVED'"
            (closed)="selectedProduct.set(null)"
            (changed)="loadMedia()"
        /></g-card>
      }
    }
    @if (mediaError()) {
      <p role="status">{{ mediaError() }}</p>
      <button gButton variant="secondary" (click)="loadMedia()">Recharger les images</button>
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
          <g-card>
            @if (media()[product.id!]?.primaryThumbnailUrl; as thumbnail) {
              <img
                class="product-photo"
                [src]="thumbnail"
                [alt]="product.name ?? 'Produit'"
                width="300"
                height="180"
                loading="lazy"
                (error)="imageFailed(product.id!)"
              />
            } @else {
              <div class="product-photo placeholder" aria-label="Produit sans image">
                <g-icon name="box" /><span>Aucune image</span>
              </div>
            }
            <div class="section-heading">
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
            </ul>
            <button gButton variant="secondary" (click)="selectedProduct.set(product)">
              Voir les images
            </button></g-card
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
    .product-photo {
      width: 100%;
      height: 180px;
      object-fit: contain;
      background: var(--surface-alt);
      margin-bottom: var(--s3);
    }
    .placeholder {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: var(--s2);
      color: var(--muted);
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
  readonly selectedProduct = signal<ProductView | null>(null);
  readonly media = signal<Record<string, ProductMediaSummary>>({});
  readonly mediaError = signal('');
  private readonly mediaApi = inject(ProductMediaApi);
  private readonly auth = inject(AuthService);
  private mediaRequest = 0;
  readonly statusLabel = statusLabel;
  readonly variantLabel = variantLabel;
  private readonly toast = inject(ToastService);
  constructor() {
    effect(() => {
      const id = this.context.selectedId();
      this.auth.generation();
      this.creating.set(false);
      this.selectedProduct.set(null);
      this.media.set({});
      if (id) void this.store.load(id);
      else this.store.clear();
    });
    effect(() => {
      this.store.products();
      this.context.selectedId();
      this.auth.generation();
      untracked(() => void this.loadMedia());
    });
  }
  load(page = 0) {
    const id = this.context.selectedId();
    if (id) void this.store.load(id, page);
  }
  created(product: ProductView) {
    this.creating.set(false);
    this.selectedProduct.set(product);
    this.toast.show('Produit créé.');
    this.load();
  }
  async loadMedia() {
    const request = ++this.mediaRequest,
      org = this.context.selectedId(),
      generation = this.auth.generation();
    const ids = this.store
      .products()
      .map((p) => p.id!)
      .filter(Boolean);
    this.mediaError.set('');
    if (!org || !ids.length) {
      this.media.set({});
      return;
    }
    const current = () =>
      request === this.mediaRequest &&
      org === this.context.selectedId() &&
      generation === this.auth.generation();
    try {
      const response = await this.mediaApi.batch(org, ids);
      if (current())
        this.media.set(Object.fromEntries(response.map((item) => [item.productId!, item])));
    } catch {
      if (current()) this.mediaError.set('Les images ne peuvent pas être chargées pour le moment.');
    }
  }
  imageFailed(id: string) {
    this.media.update((items) => ({
      ...items,
      [id]: { ...items[id], primaryThumbnailUrl: undefined },
    }));
  }
}
