import { Component, input, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
import { Button } from '../../shared/ui/button';
export interface VariantDraft {
  sku: string;
  options: { key: string; value: string }[];
  weight: number | null;
  volume: number | null;
}
export function newVariant(): VariantDraft {
  return { sku: '', options: [{ key: 'color', value: '' }], weight: null, volume: null };
}
@Component({
  selector: 'g-variant-editor',
  imports: [FormsModule, Input, Select, Button],
  template: `
    @for (variant of variants(); track $index; let v = $index) {
      <fieldset class="variant">
        <legend>Variante {{ v + 1 }}</legend>
        @for (option of variant.options; track $index; let o = $index) {
          <div class="option-row">
            <label
              >Option<select
                gSelect
                [ngModelOptions]="{ standalone: true }"
                [ngModel]="option.key"
                (ngModelChange)="change(v, o, 'key', $event)"
              >
                <option value="color">Couleur</option>
                <option value="size">Taille</option>
                <option value="shoeSize">Pointure</option>
                <option value="capacity">Capacité</option>
                <option value="material">Matière</option>
              </select></label
            >
            <label
              >Valeur<input
                gInput
                [ngModelOptions]="{ standalone: true }"
                [ngModel]="option.value"
                (ngModelChange)="change(v, o, 'value', $event)"
                maxlength="120"
                required
            /></label>
            <button
              gButton
              variant="ghost"
              type="button"
              [attr.aria-label]="'Retirer l’option ' + (o + 1) + ' de la variante ' + (v + 1)"
              (click)="removeOption(v, o)"
            >
              Retirer
            </button>
          </div>
        }
        <button
          gButton
          variant="ghost"
          type="button"
          [disabled]="variant.options.length >= 5"
          (click)="addOption(v)"
        >
          Ajouter une option
        </button>
        <div class="field-grid">
          <label
            >SKU (facultatif)<input
              gInput
              [ngModelOptions]="{ standalone: true }"
              [ngModel]="variant.sku"
              (ngModelChange)="patch(v, { sku: $event })"
              maxlength="64"
          /></label>
          <label
            >Poids estimé par unité (kg)<input
              gInput
              type="number"
              min="0.000001"
              step="any"
              [ngModelOptions]="{ standalone: true }"
              [ngModel]="variant.weight"
              (ngModelChange)="patch(v, { weight: $event })"
          /></label>
          <label
            >Volume estimé par unité (m³)<input
              gInput
              type="number"
              min="0.000001"
              step="any"
              [ngModelOptions]="{ standalone: true }"
              [ngModel]="variant.volume"
              (ngModelChange)="patch(v, { volume: $event })"
          /></label>
        </div>
        <button gButton variant="ghost" type="button" (click)="remove(v)">
          Retirer cette variante
        </button>
      </fieldset>
    }
    <button
      gButton
      variant="secondary"
      type="button"
      [disabled]="variants().length >= maxVariants()"
      (click)="add()"
    >
      Ajouter une variante
    </button>
  `,
  styles: `
    .variant {
      border: 1px solid var(--border);
      border-radius: var(--radius-md);
      padding: var(--s4);
      margin: 0 0 var(--s4);
      min-width: 0;
    }
    .option-row {
      display: flex;
      gap: var(--s3);
      align-items: end;
      flex-wrap: wrap;
      margin-bottom: var(--s3);
    }
    .option-row label {
      flex: 1;
      min-width: 100px;
    }
    label {
      display: grid;
      gap: var(--s2);
    }
    .field-grid {
      margin: var(--s4) 0;
    }
  `,
})
export class VariantEditor {
  readonly variants = model.required<VariantDraft[]>();
  readonly maxVariants = input(100);
  patch(index: number, value: Partial<VariantDraft>) {
    this.variants.update((list) => list.map((v, i) => (i === index ? { ...v, ...value } : v)));
  }
  change(v: number, o: number, field: 'key' | 'value', value: string) {
    this.patch(v, {
      options: this.variants()[v].options.map((option, i) =>
        i === o ? { ...option, [field]: value } : option,
      ),
    });
  }
  add() {
    this.variants.update((list) => [...list, newVariant()]);
  }
  remove(v: number) {
    this.variants.update((list) => list.filter((_, i) => i !== v));
  }
  addOption(v: number) {
    this.patch(v, { options: [...this.variants()[v].options, { key: 'size', value: '' }] });
  }
  removeOption(v: number, o: number) {
    this.patch(v, { options: this.variants()[v].options.filter((_, i) => i !== o) });
  }
}
