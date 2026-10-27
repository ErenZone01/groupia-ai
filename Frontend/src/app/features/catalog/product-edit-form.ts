import { Component, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ProductView, VariantInput } from '../../api/generated/models';
import { GroupiaApi } from '../../api/groupia-api';
import { apiMessage } from '../../core/http/api-error';
import { Input } from '../../shared/forms/input';
import { Button } from '../../shared/ui/button';
import { VariantEditor, VariantDraft, newVariant } from './variant-editor';

@Component({
  selector: 'g-product-edit-form',
  imports: [FormsModule, Input, Button, VariantEditor],
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
        Les variantes existantes conservent leurs références et historiques.</p>
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
    <section class="variant-add" aria-labelledby="variant-add-title">
      <h3 id="variant-add-title">Ajouter une variante</h3>
      <p class="muted">Chaque nouvelle variante possède ses propres options (couleur, taille…).
        Les variantes existantes ne sont pas remplacées.</p>
      @if (latest; as current) {
        <p class="muted">{{ current.variants?.length ?? 0 }} variante(s) existante(s).</p>
      }
      @if (!drafts().length) {
        <button gButton variant="secondary" type="button" [disabled]="busy() || variantBusy()"
          (click)="drafts.set([newVariant()])">Nouvelle variante</button>
      } @else {
        <g-variant-editor [(variants)]="drafts" [maxVariants]="1" />
        @if (variantError()) {
          <p role="alert" class="form-error">{{ variantError() }}</p>
          <button gButton variant="secondary" type="button" [disabled]="variantBusy()"
            (click)="refresh()">Actualiser le produit</button>
        }
        <div class="actions">
          <button gButton type="button" [disabled]="variantBusy() || busy() || !drafts().length"
            [loading]="variantBusy()" (click)="addVariant()">Enregistrer la nouvelle variante</button>
          <button gButton variant="ghost" type="button" [disabled]="variantBusy()"
            (click)="drafts.set([]); variantError.set('')">Annuler l’ajout</button>
        </div>
      }
    </section>
  `,
  styles: `
    :host { display:block; min-width:0; overflow-wrap:anywhere; }
    h2 { margin:0; font-size:var(--text-lg); }
    .eyebrow { font-size:var(--text-xs); color:var(--primary); }
    .muted { font-size:var(--text-xs); color:var(--muted); line-height:1.5; }
    .actions { display:flex; flex-wrap:wrap; gap:var(--s2); margin-top:var(--s3); }
    .variant-add { border-top:1px solid var(--border); margin-top:var(--s4);
      padding-top:var(--s4); min-width:0; }
    .variant-add h3 { font-size:var(--text-base); margin:0 0 var(--s2); }
    .actions button { min-height:44px; white-space:normal; }
    label { font-size:var(--text-sm); font-weight:600; }
  `,
})
export class ProductEditForm implements OnInit {
  readonly organizationId = input.required<string>();
  readonly product = input.required<ProductView>();
  readonly saved = output<ProductView>();
  readonly cancelled = output<void>();
  readonly variantAdded = output<ProductView>();
  private readonly api = inject(GroupiaApi);
  readonly busy = signal(false);
  readonly variantBusy = signal(false);
  readonly variantError = signal('');
  readonly drafts = signal<VariantDraft[]>([]);
  readonly newVariant = newVariant;
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
    this.variantError.set('');
  }

  async refresh() {
    const id = this.product().id;
    if (!id || this.busy() || this.variantBusy()) return;
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
  async addVariant() {
    const id = this.product().id;
    const version = this.latest?.version;
    const draft = this.drafts()[0];
    if (!id || version === undefined || !draft || this.variantBusy() || this.busy()) return;
    if (!draft.options.length || draft.options.some(o => !o.value.trim()) ||
        new Set(draft.options.map(o => o.key)).size !== draft.options.length) {
      this.variantError.set('Renseignez les options sans répéter le même type d’option.');
      return;
    }
    if ([draft.weight, draft.volume].some(v =>
      v !== null && (!Number.isFinite(v) || v <= 0))) {
      this.variantError.set('Les estimations doivent être strictement positives.');
      return;
    }
    const variant: VariantInput = {
      attributes: Object.fromEntries(draft.options.map(o => [o.key, o.value.trim()])),
      ...(draft.sku.trim() ? { sku: draft.sku.trim() } : {}),
      ...(draft.weight !== null ? { estimatedUnitWeightKg: draft.weight } : {}),
      ...(draft.volume !== null ? { estimatedUnitVolumeM3: draft.volume } : {}),
    };
    this.variantBusy.set(true);
    this.variantError.set('');
    try {
      const result = await this.api.addProductVariant(this.organizationId(), id, version, variant);
      this.latest = result;
      this.drafts.set([]);
      this.variantAdded.emit(result);
    } catch (error) {
      this.variantError.set(apiMessage(error));
    } finally {
      this.variantBusy.set(false);
    }
  }

}
