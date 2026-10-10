import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogStore } from '../catalog/catalog.store';
import { GroupageLine } from './groupage.store';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
import { Skeleton } from '../../shared/feedback/skeleton';
import { EmptyState } from '../../shared/feedback/empty-state';
import { safePrice, variantLabel } from '../../shared/utils/display';
@Component({
  selector: 'g-product-picker',
  providers: [CatalogStore],
  imports: [FormsModule, Button, Input, Select, Skeleton, EmptyState],
  template: `
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
  `,
})
export class ProductPicker {
  readonly organizationId = input.required<string>();
  readonly excluded = input<string[]>([]);
  readonly disabled = input(false);
  readonly added = output<GroupageLine>();
  readonly store = inject(CatalogStore);
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
          .map((variant) => ({ name: p.name ?? 'Produit', variant })),
      ),
  );
  constructor() {
    effect(() => {
      const id = this.organizationId();
      this.selected.set('');
      void this.store.load(id);
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
  }
}
