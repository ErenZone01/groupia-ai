import { Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
@Component({
  selector: 'g-payout-destination-editor',
  imports: [FormsModule, Button, Input, Select],
  template: `
    <form class="form-stack" (ngSubmit)="submit()">
      <fieldset [disabled]="busy()">
        <legend>Nouvelle destination de versement</legend>
        <label
          >Méthode<select gSelect name="method" [(ngModel)]="method">
            <option value="WAVE">Wave</option>
            <option value="ORANGE_MONEY">Orange Money</option>
          </select></label
        >
        <label
          >Nom du titulaire<input
            gInput
            name="holder"
            [(ngModel)]="holder"
            required
            maxlength="160"
            autocomplete="name"
        /></label>
        <label
          >Numéro sénégalais<input
            gInput
            name="phone"
            [(ngModel)]="phone"
            required
            type="tel"
            autocomplete="tel"
        /></label>
        <label class="confirmation"
          ><input type="checkbox" name="confirmed" [(ngModel)]="confirmed" required /> Les futurs
          versements seront envoyés à ce numéro.</label
        >
      </fieldset>
      <button gButton type="submit" [loading]="busy()" [disabled]="!valid()">
        Enregistrer la destination
      </button>
    </form>
  `,
  styles: `
    fieldset {
      border: 1px solid var(--border);
      border-radius: var(--radius-md);
      padding: var(--s4);
      min-width: 0;
    }
    label {
      display: block;
      margin: var(--s3) 0;
    }
    .confirmation {
      display: flex;
      gap: var(--s3);
      align-items: flex-start;
    }
    input[type='checkbox'] {
      accent-color: var(--primary);
      margin-top: var(--s1);
      flex-shrink: 0;
    }
  `,
})
export class PayoutDestinationEditor {
  readonly busy = input(false);
  readonly save = output<{ method: string; phone: string; holder: string; confirmed: boolean }>();
  method = 'WAVE';
  phone = '';
  holder = '';
  confirmed = false;
  valid() {
    return this.confirmed && !!this.phone.trim() && !!this.holder.trim() && !this.busy();
  }
  submit() {
    if (!this.valid()) return;
    this.save.emit({
      method: this.method,
      phone: this.phone,
      holder: this.holder.trim(),
      confirmed: true,
    });
    this.confirmed = false;
  }
}
