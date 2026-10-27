import { Component, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ProductView } from '../../api/generated/models';
import { GroupiaApi } from '../../api/groupia-api';
import { apiMessage } from '../../core/http/api-error';
import { Input } from '../../shared/forms/input';
import { Button } from '../../shared/ui/button';

@Component({
  selector: 'g-product-edit-form',
  imports: [FormsModule, Input, Button],
  template: `
    <form #form="ngForm" (ngSubmit)="save()" class="form-stack">
      <p class="eyebrow">Catalogue / Produit</p>
      <h2>Modifier le produit</h2>
      <p class="muted">Modifiez les informations du catalogue. Les commandes déjà enregistrées
        conservent leurs informations historiques.</p>
      <label for="edit-product-name">Nom du produit</label>
      <input gInput id="edit-product-name" name="name" [(ngModel)]="name"
        required maxlength="160" [disabled]="busy()" />
      <label for="edit-product-description">Description (facultative)</label>
      <textarea gInput id="edit-product-description" name="description" [(ngModel)]="description"
        maxlength="5000" rows="4" [disabled]="busy()"></textarea>
      <p class="muted">Les photos restent accessibles par « Voir les images ».
        Les variantes du produit ne sont pas modifiées par ce formulaire.</p>
      @if (error()) {
        <p class="form-error" role="alert">{{ error() }}</p>
        <button gButton variant="secondary" type="button" [disabled]="busy()" (click)="refresh()">
          Actualiser les données du produit
        </button>
      }
      <div class="actions">
        <button gButton type="submit" [loading]="busy()"
          [disabled]="busy() || form.invalid || !name.trim() || !changed()">
          Enregistrer les modifications
        </button>
        <button gButton variant="ghost" type="button" [disabled]="busy()"
          (click)="cancelled.emit()">Annuler</button>
      </div>
    </form>
  `,
  styles: `
    :host { display:block; min-width:0; overflow-wrap:anywhere; }
    h2 { margin:0; font-size:var(--text-lg); }
    .eyebrow { font-size:var(--text-xs); color:var(--primary); }
    .muted { font-size:var(--text-xs); color:var(--muted); line-height:1.5; }
    .actions { display:flex; flex-wrap:wrap; gap:var(--s2); margin-top:var(--s3); }
    .actions button { min-height:44px; white-space:normal; }
    label { font-size:var(--text-sm); font-weight:600; }
  `,
})
export class ProductEditForm implements OnInit {
  readonly organizationId = input.required<string>();
  readonly product = input.required<ProductView>();
  readonly saved = output<ProductView>();
  readonly cancelled = output<void>();
  private readonly api = inject(GroupiaApi);
  readonly busy = signal(false);
  readonly error = signal('');
  name = '';
  description = '';
  private latest: ProductView | null = null;

  ngOnInit() {
    this.setDraft(this.product());
  }

  changed() {
    return this.name.trim() !== (this.latest?.name ?? '') ||
      (this.description.trim() || '') !== (this.latest?.description ?? '');
  }

  private setDraft(product: ProductView) {
    this.latest = product;
    this.name = product.name ?? '';
    this.description = product.description ?? '';
    this.error.set('');
  }

  async refresh() {
    const id = this.product().id;
    if (!id || this.busy()) return;
    this.busy.set(true);
    try {
      const latest = await this.api.product(this.organizationId(), id);
      this.setDraft(latest);
    } catch (error) {
      this.error.set(apiMessage(error));
    } finally {
      this.busy.set(false);
    }
  }

  async save() {
    const id = this.product().id;
    const version = this.latest?.version;
    if (!id || version === undefined || this.busy() || !this.name.trim() || !this.changed()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const result = await this.api.updateProduct(this.organizationId(), id, {
        version, name: this.name.trim(), description: this.description.trim() || undefined,
      });
      this.saved.emit(result);
    } catch (error) {
      this.error.set(apiMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
