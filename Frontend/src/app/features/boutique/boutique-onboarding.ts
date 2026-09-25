import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { Card } from '../../shared/ui/card';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { ToastService } from '../../shared/feedback/toast.service';
@Component({
  selector: 'g-boutique-onboarding',
  imports: [FormsModule, Card, Button, Input],
  template: ` <g-card
    ><p class="eyebrow">Bienvenue dans votre espace</p>
    <h1>Comment s’appelle votre boutique ?</h1>
    <p class="muted">Un nom, et votre aventure commence.</p>
    <form #form="ngForm" (ngSubmit)="create()" class="form-stack">
      <label for="shop-name">Nom de la boutique</label
      ><input
        gInput
        id="shop-name"
        name="name"
        [(ngModel)]="name"
        required
        maxlength="160"
        autocomplete="organization"
      />
      @if (error()) {
        <p class="form-error" role="alert">{{ error() }}</p>
      }
      <button gButton [loading]="busy()" [disabled]="form.invalid || !name.trim()">
        Créer ma boutique
      </button>
    </form></g-card
  >`,
  styles: `
    :host {
      display: block;
      max-width: 640px;
      margin: var(--s8) auto;
    }
    h1 {
      margin: var(--s3) 0;
    }
    form {
      margin-top: var(--s6);
    }
  `,
})
export class BoutiqueOnboarding {
  readonly context = inject(BoutiqueContextService);
  private readonly toast = inject(ToastService);
  name = '';
  readonly busy = signal(false);
  readonly error = signal('');
  async create() {
    if (this.busy() || !this.name.trim()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.context.create(this.name.trim());
      this.toast.show('Votre boutique est prête.');
    } catch (e) {
      this.error.set(apiMessage(e));
      this.toast.show(apiMessage(e), 'error');
    } finally {
      this.busy.set(false);
    }
  }
}
