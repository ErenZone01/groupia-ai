import { Component, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OfferView } from '../../api/generated/models';
import { GroupageStore } from './groupage.store';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { apiMessage } from '../../core/http/api-error';
import { safePrice } from '../../shared/utils/display';
import { ToastService } from '../../shared/feedback/toast.service';
@Component({
  selector: 'g-offer-price-editor',
  imports: [FormsModule, Button, Input],
  template: `
    @if (!editing()) {
      <button
        gButton
        variant="secondary"
        [disabled]="store.busy() || store.stale()"
        (click)="editing.set(true)"
      >
        Modifier le prix
      </button>
    } @else {
      <form class="form-stack" (ngSubmit)="save()">
        <label
          >Prix dans ce groupage (F CFA)<input
            gInput
            name="price"
            type="number"
            min="0"
            step="1"
            [(ngModel)]="price"
            required /></label
        ><label
          >Nom affiché aux clientes (facultatif)<input
            gInput
            name="label"
            maxlength="160"
            [(ngModel)]="label"
        /></label>
        @if (error()) {
          <p role="alert" class="form-error">{{ error() }}</p>
        }
        <div class="actions">
          <button gButton [loading]="store.busy()" [disabled]="store.stale()">
            Enregistrer le prix</button
          ><button
            gButton
            variant="ghost"
            type="button"
            [disabled]="store.busy()"
            (click)="editing.set(false)"
          >
            Annuler
          </button>
        </div>
      </form>
    }
  `,
})
export class OfferPriceEditor {
  readonly offer = input.required<OfferView>();
  readonly organizationId = input.required<string>();
  readonly store = inject(GroupageStore);
  private readonly toast = inject(ToastService);
  readonly editing = signal(false);
  readonly error = signal('');
  price: number | null = null;
  label = '';
  constructor() {
    effect(() => {
      const o = this.offer();
      this.price = o.unitPrice ?? null;
      this.label = o.commercialLabel ?? '';
    });
  }
  async save() {
    if (this.store.busy()) return;
    if (!safePrice(this.price)) {
      this.error.set('Indiquez un prix entier positif ou nul.');
      return;
    }
    this.error.set('');
    try {
      await this.store.updatePrice(
        this.organizationId(),
        this.offer(),
        this.price,
        this.label.trim() || undefined,
      );
      this.editing.set(false);
      this.toast.show('Prix enregistré.');
    } catch (e) {
      this.error.set(apiMessage(e));
      this.toast.show(apiMessage(e), 'error');
    }
  }
}
