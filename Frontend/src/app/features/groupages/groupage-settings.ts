import { Component, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CampaignView, ShippingInput } from '../../api/generated/models';
import { GroupageStore } from './groupage.store';
import { ShippingFields, defaultShipping, validShipping } from './shipping-fields';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { apiMessage } from '../../core/http/api-error';
import { ToastService } from '../../shared/feedback/toast.service';
@Component({
  selector: 'g-groupage-settings',
  imports: [FormsModule, Button, Input, ShippingFields],
  template: ` <form #form="ngForm" (ngSubmit)="save()" class="form-stack">
    <h2>Paramètres du groupage</h2>
    <label>Titre<input gInput name="title" [(ngModel)]="title" required maxlength="160" /></label
    ><label
      >Description<textarea
        gInput
        name="description"
        [(ngModel)]="description"
        maxlength="5000"
        rows="4"
      ></textarea></label
    ><label
      >Date de clôture<input gInput name="close" type="datetime-local" [(ngModel)]="closesAt"
    /></label>
    @if (campaign().status === 'DRAFT') {
      <g-shipping-fields [(value)]="shipping" />
    } @else {
      <p class="notice">Les conditions de livraison sont fixées depuis la publication.</p>
    }
    @if (error()) {
      <p role="alert" class="form-error">{{ error() }}</p>
    }
    <button
      gButton
      [loading]="store.busy()"
      [disabled]="form.invalid || store.stale() || !title.trim()"
    >
      Enregistrer les paramètres
    </button>
  </form>`,
})
export class GroupageSettings {
  readonly campaign = input.required<CampaignView>();
  readonly organizationId = input.required<string>();
  readonly store = inject(GroupageStore);
  private readonly toast = inject(ToastService);
  readonly saved = output<void>();
  title = '';
  description = '';
  closesAt = '';
  readonly shipping = signal<ShippingInput>(defaultShipping());
  readonly error = signal('');
  constructor() {
    effect(() => {
      const c = this.campaign();
      this.title = c.title ?? '';
      this.description = c.description ?? '';
      const date = c.closesAt ? new Date(c.closesAt) : null;
      this.closesAt = date
        ? new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
        : '';
      this.shipping.set(c.shippingTerms ?? defaultShipping());
    });
  }
  async save() {
    if (this.store.busy()) return;
    if (!validShipping(this.shipping())) {
      this.error.set('Vérifiez les conditions de livraison.');
      return;
    }
    if (this.closesAt && new Date(this.closesAt).getTime() <= Date.now()) {
      this.error.set('Choisissez une date de clôture future.');
      return;
    }
    this.error.set('');
    try {
      await this.store.settings(
        this.organizationId(),
        this.title.trim(),
        this.description.trim(),
        this.closesAt ? new Date(this.closesAt).toISOString() : undefined,
        this.shipping(),
      );
      this.toast.show('Paramètres enregistrés.');
      this.saved.emit();
    } catch (e) {
      this.error.set(apiMessage(e));
      this.toast.show(apiMessage(e), 'error');
    }
  }
}
