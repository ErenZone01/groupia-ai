import { ToastService } from '../../shared/feedback/toast.service';
import { Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GroupiaApi } from '../../api/groupia-api';
import { ProductView, VariantInput } from '../../api/generated/models';
import { apiMessage } from '../../core/http/api-error';
import { Input } from '../../shared/forms/input';
import { Button } from '../../shared/ui/button';
import { VariantEditor, VariantDraft, newVariant } from './variant-editor';
@Component({
  selector: 'g-product-form',
  imports: [FormsModule, Input, Button, VariantEditor],
  template: ` <form #form="ngForm" (ngSubmit)="save()" class="form-stack">
    <h2>Nouveau produit</h2>
    <label for="product-name">Nom du produit</label
    ><input gInput id="product-name" name="name" [(ngModel)]="name" required maxlength="160" />
    <label for="product-description">Description (facultative)</label
    ><textarea
      gInput
      id="product-description"
      name="description"
      [(ngModel)]="description"
      maxlength="5000"
      rows="3"
    ></textarea>
    <p class="muted">Le prix sera défini dans chaque groupage.</p>
    @if (!variants().length) {
      <p>Produit simple, sans option.</p>
      <button gButton variant="secondary" type="button" (click)="variants.set([newVariant()])">
        Ajouter une option
      </button>
    } @else {
      <g-variant-editor [(variants)]="variants" />
    }
    @if (error()) {
      <p role="alert" class="form-error">{{ error() }}</p>
    }
    <div class="actions">
      <button gButton [loading]="busy()" [disabled]="form.invalid || !name.trim()">
        Créer le produit</button
      ><button gButton variant="ghost" type="button" [disabled]="busy()" (click)="cancelled.emit()">
        Annuler
      </button>
    </div>
  </form>`,
})
export class ProductForm {
  readonly organizationId = input.required<string>();
  readonly saved = output<ProductView>();
  readonly cancelled = output<void>();
  private readonly api = inject(GroupiaApi);
  private readonly toast = inject(ToastService);
  readonly newVariant = newVariant;
  name = '';
  description = '';
  readonly variants = signal<VariantDraft[]>([]);
  readonly error = signal('');
  readonly busy = signal(false);
  async save() {
    if (this.busy() || !this.name.trim()) return;
    const variants: VariantInput[] = [];
    for (const draft of this.variants()) {
      if (
        !draft.options.length ||
        draft.options.some((o) => !o.value.trim()) ||
        new Set(draft.options.map((o) => o.key)).size !== draft.options.length
      ) {
        this.error.set(
          'Renseignez les options de chaque variante, sans répéter le même type d’option.',
        );
        return;
      }
      if ([draft.weight, draft.volume].some((v) => v !== null && (!Number.isFinite(v) || v <= 0))) {
        this.error.set('Les estimations doivent être positives.');
        return;
      }
      variants.push({
        attributes: Object.fromEntries(draft.options.map((o) => [o.key, o.value.trim()])),
        ...(draft.sku.trim() ? { sku: draft.sku.trim() } : {}),
        ...(draft.weight !== null ? { estimatedUnitWeightKg: draft.weight } : {}),
        ...(draft.volume !== null ? { estimatedUnitVolumeM3: draft.volume } : {}),
      });
    }
    this.busy.set(true);
    this.error.set('');
    try {
      this.saved.emit(
        await this.api.createProduct(this.organizationId(), {
          name: this.name.trim(),
          description: this.description.trim() || undefined,
          ...(variants.length ? { variants } : {}),
        }),
      );
    } catch (e) {
      this.error.set(apiMessage(e));
      this.toast.show(apiMessage(e), 'error');
    } finally {
      this.busy.set(false);
    }
  }
}
