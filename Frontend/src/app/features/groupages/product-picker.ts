import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogStore } from '../catalog/catalog.store';
import { ProductMediaApi } from '../catalog/product-media.api';
import type { ProductMediaSummary } from '../../api/generated/models';
import { GroupageLine } from './groupage.store';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
import { Skeleton } from '../../shared/feedback/skeleton';
import { EmptyState } from '../../shared/feedback/empty-state';
import { safePrice, variantLabel } from '../../shared/utils/display';
@Component({
  selector: 'g-product-picker',
  providers: [CatalogStore],
  imports: [FormsModule, Button, Icon, Input, Select, Skeleton, EmptyState],
  template: `

    @if (mode() === 'wizard') {
      <section class="wizard-product-picker" aria-label="Catalogue de la boutique">
        <div class="picker-toolbar">
          <h3>Catalogue de la boutique</h3>
          <label class="picker-search">
            <g-icon name="search" />
            <input type="search" aria-label="Rechercher un produit sur cette page"
              placeholder="Chercher un produit…" [ngModel]="search()"
              (ngModelChange)="search.set($event)" />
          </label>
        </div>
        <p class="picker-scope">Recherche limitée à la page de catalogue chargée.</p>
        @if (store.loading()) {
          <g-skeleton /><g-skeleton />
        } @else if (store.error()) {
          <p role="alert" class="form-error">{{ store.error() }}</p>
          <button gButton type="button" variant="secondary" (click)="load()">Réessayer</button>
        } @else if (!choices().length) {
          <g-empty-state title="Aucune variante disponible sur cette page"
            description="Ajoutez un produit ou une variante active dans votre catalogue, ou passez à la page suivante."
            icon="box" />
        } @else {
          <div class="product-tiles">
            @for (product of filteredProducts(); track product.id) {
              <button type="button" class="product-tile"
                [class.active]="chosenProductId() === product.id"
                [attr.aria-pressed]="chosenProductId() === product.id"
                [disabled]="disabled()" (click)="pickProduct(product.id!)">
                <span class="tile-photo">
                  @if (media()[product.id!]?.primaryThumbnailUrl; as url) {
                    <img [src]="url" [alt]="product.name ?? 'Produit'"
                      (error)="imageFailed(product.id!)" loading="lazy" decoding="async" />
                  } @else {
                    <g-icon name="box" />
                  }
                </span>
                <span class="tile-text">
                  <strong>{{ product.name }}</strong>
                  <small>{{ availableCount(product.id!) }} variante(s) disponible(s)</small>
                </span>
                <g-icon name="chevron" />
              </button>
            } @empty {
              <p class="picker-no-results">Aucun produit correspondant sur cette page.</p>
            }
          </div>
          <div class="variant-editor">
            <h4>Choisir une variante et son prix</h4>
            <label class="picker-label">Produit et variante
              <select gSelect aria-label="Produit et variante" [ngModel]="selected()"
                (ngModelChange)="chooseVariant($event)" [disabled]="disabled()">
                <option value="">Choisir un produit</option>
                @for (choice of choices(); track choice.variant.id) {
                  <option [value]="choice.variant.id">
                    {{ choice.name }} · {{ variantLabel(choice.variant.attributes) }}
                  </option>
                }
              </select>
            </label>
            @if (availableVariants().length) {
              <div class="chips-label">Variantes du produit sélectionné</div>
              <div class="variant-chips" role="group" aria-label="Variantes disponibles">
                @for (choice of availableVariants(); track choice.variant.id) {
                  <button type="button" class="variant-chip"
                    [class.active]="selected() === choice.variant.id"
                    [attr.aria-pressed]="selected() === choice.variant.id"
                    [disabled]="disabled()" (click)="chooseVariant(choice.variant.id!)">
                    {{ variantLabel(choice.variant.attributes) }}
                  </button>
                }
              </div>
            }
            <div class="offer-editor-fields">
              <label>Nom commercial (facultatif)
                <input gInput type="text" maxlength="160" [(ngModel)]="label" [disabled]="disabled()"
                  placeholder="Par défaut : nom du produit" />
              </label>
              <label>Prix dans ce groupage (F CFA)
                <input gInput type="number" min="0" step="1" [(ngModel)]="price" [disabled]="disabled()"
                  placeholder="Ex. 12500" />
              </label>
            </div>
            <p class="picker-hint">Ce prix s'applique à ce groupage, indépendamment du catalogue.</p>
            @if (error()) { <p role="alert" class="form-error">{{ error() }}</p> }
            <div class="picker-submit">
              <button gButton type="button" [disabled]="disabled() || !selected()" (click)="add()">
                <g-icon name="plus" /> Ajouter au groupage
              </button>
            </div>
          </div>
        }
        @if (store.totalPages() > 1) {
          <nav class="picker-pagination" aria-label="Pages des produits">
            <button gButton type="button" variant="secondary"
              [disabled]="store.page() === 0 || store.loading()" (click)="load(store.page() - 1)">Précédent</button>
            <span>Page {{ store.page() + 1 }} / {{ store.totalPages() }}</span>
            <button gButton type="button" variant="secondary"
              [disabled]="store.page() + 1 >= store.totalPages() || store.loading()"
              (click)="load(store.page() + 1)">Suivant</button>
          </nav>
        }
      </section>
    } @else {
    @if (store.loading()) {
      <g-skeleton />
    } @else if (store.error()) {
      <p role="alert" class="form-error">{{ store.error() }}</p>
      <button gButton type="button" (click)="load()">Réessayer</button>
    } @else if (!choices().length) {
      <g-empty-state
        title="Aucun produit disponible sur cette page"
        description="Créez un produit dans le catalogue ou consultez la page suivante."
        icon="box"
      />
    } @else {
      <div class="form-stack">
        <label
          >Produit et variante<select
            gSelect
            [ngModel]="selected()"
            (ngModelChange)="selected.set($event)"
          >
            <option value="">Choisir un produit</option>
            @for (choice of choices(); track choice.variant.id) {
              <option [value]="choice.variant.id">
                {{ choice.name }} · {{ variantLabel(choice.variant.attributes) }}
              </option>
            }
          </select></label
        >
        @if (selectedChoice(); as chosen) {
          <div class="chosen-product">
            @if (thumbnail(); as photo) {
              <img [src]="photo" [alt]="chosen.name" width="48" height="48"
                   loading="lazy" decoding="async" (error)="imageFailed(chosen.productId)" />
            } @else {
              <span class="chosen-placeholder"><g-icon name="box" /></span>
            }
            <span class="chosen-label">{{ chosen.name }}</span>
          </div>
        }
        <label
          >Prix dans ce groupage (F CFA)<input
            gInput
            type="number"
            min="0"
            step="1"
            [(ngModel)]="price"
        /></label>
        <p class="muted">Ce prix s’applique à ce groupage, indépendamment du catalogue.</p>
        <label
          >Nom affiché aux clientes (facultatif)<input gInput maxlength="160" [(ngModel)]="label"
        /></label>
        @if (error()) {
          <p role="alert" class="form-error">{{ error() }}</p>
        }
        <button gButton type="button" [disabled]="disabled() || !selected()" (click)="add()">
          Ajouter au groupage
        </button>
      </div>
    }
    @if (store.totalPages() > 1) {
      <nav class="actions" aria-label="Pages des produits">
        <button
          gButton
          type="button"
          variant="secondary"
          [disabled]="store.page() === 0 || store.loading()"
          (click)="load(store.page() - 1)"
        >
          Précédent</button
        ><span>{{ store.page() + 1 }} / {{ store.totalPages() }}</span
        ><button
          gButton
          type="button"
          variant="secondary"
          [disabled]="store.page() + 1 >= store.totalPages() || store.loading()"
          (click)="load(store.page() + 1)"
        >
          Suivant
        </button>
      </nav>
    }
    }
  `,
  styles: `
    .chosen-product { display:flex; align-items:center; gap:var(--s2); margin:var(--s2) 0; min-width:0; }
    .chosen-product img,.chosen-placeholder { width:48px; height:48px; border-radius:var(--radius-sm); object-fit:cover; flex-shrink:0; background:var(--surface-alt); }
    .chosen-placeholder { display:grid; place-items:center; color:var(--muted); }
    .chosen-label { font-size:var(--text-sm); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  `,
})
export class ProductPicker {
  readonly organizationId = input.required<string>();
  readonly mode = input<'compact' | 'wizard'>('compact');
  readonly search = signal('');
  readonly pickedProductId = signal('');
  readonly excluded = input<string[]>([]);
  readonly disabled = input(false);
  readonly added = output<GroupageLine>();
  readonly store = inject(CatalogStore);
  private readonly mediaApi = inject(ProductMediaApi);
  readonly media = signal<Record<string, ProductMediaSummary>>({});
  private mediaRequest = 0;
  readonly selected = signal('');
  price: number | null = null;
  label = '';
  readonly error = signal('');
  readonly variantLabel = variantLabel;
  readonly choices = computed(() =>
    this.store
      .products()
      .filter((p) => p.status === 'ACTIVE')
      .flatMap((p) =>
        (p.variants ?? [])
          .filter((v) => v.active && !this.excluded().includes(v.id!))
          .map((variant) => ({ name: p.name ?? 'Produit', productId: p.id!, variant })),
      ),
  );
  readonly filteredProducts = computed(() => {
    const query = this.search().trim().toLocaleLowerCase('fr');
    const ids = new Set(this.choices().map((choice) => choice.productId));
    return this.store.products().filter((product) =>
      product.status === 'ACTIVE' &&
      !!product.id && ids.has(product.id) &&
      (!query || (product.name ?? '').toLocaleLowerCase('fr').includes(query)),
    );
  });
  readonly selectedChoice = computed(() => this.choices().find((c) => c.variant.id === this.selected()));
  readonly chosenProductId = computed(() => this.selectedChoice()?.productId ?? this.pickedProductId());
  readonly availableVariants = computed(() =>
    this.choices().filter((choice) => choice.productId === this.chosenProductId()),
  );
  availableCount(productId: string) {
    return this.choices().filter((choice) => choice.productId === productId).length;
  }
  pickProduct(productId: string) {
    this.pickedProductId.set(productId);
    if (this.selectedChoice()?.productId !== productId) this.selected.set('');
    this.error.set('');
  }
  chooseVariant(id: string) {
    this.selected.set(id);
    const choice = this.choices().find((c) => c.variant.id === id);
    if (choice) this.pickedProductId.set(choice.productId);
    this.error.set('');
  }
  readonly thumbnail = computed(() => this.media()[this.selectedChoice()?.productId ?? '']?.primaryThumbnailUrl ?? '');
  imageFailed(id: string) {
    this.media.update((items) => ({ ...items, [id]: { ...items[id], primaryThumbnailUrl: undefined } }));
  }
  constructor() {
    effect(() => {
      const id = this.organizationId();
      this.selected.set('');
      this.pickedProductId.set('');
      this.search.set('');
      void this.store.load(id);
    });
    effect(() => {
      const org = this.organizationId();
      const ids = [...new Set(this.choices().map((c) => c.productId).filter(Boolean))];
      const req = ++this.mediaRequest;
      this.media.set({});
      if (ids.length) void this.mediaApi.batch(org, ids).then((items) => {
        if (req === this.mediaRequest && org === this.organizationId())
          this.media.set(Object.fromEntries(items.filter((m) => !!m.productId).map((m) => [m.productId!, m])));
      }).catch(() => {});
    });
    effect(() => {
      if (!this.choices().some((c) => c.variant.id === this.selected())) this.selected.set('');
    });
  }
  load(page = 0) {
    void this.store.load(this.organizationId(), page);
  }
  add() {
    if (this.disabled()) return;
    const choice = this.choices().find((c) => c.variant.id === this.selected());
    if (!choice || !safePrice(this.price)) {
      this.error.set(
        'Choisissez un produit et un prix entier positif ou nul, sans dépasser la précision prise en charge.',
      );
      return;
    }
    this.added.emit({
      productVariantId: choice.variant.id!,
      name: choice.name,
      variant: variantLabel(choice.variant.attributes),
      unitPrice: this.price,
      commercialLabel: this.label.trim() || undefined,
    });
    this.error.set('');
    this.price = null;
    this.label = '';
    this.selected.set('');
    this.pickedProductId.set('');
  }
}
