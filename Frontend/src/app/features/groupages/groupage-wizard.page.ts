import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { ShippingInput } from '../../api/generated/models';
import { GroupageStore, GroupageLine } from './groupage.store';
import { ShippingFields, defaultShipping, validShipping } from './shipping-fields';
import { ProductPicker } from './product-picker';
import { Card } from '../../shared/ui/card';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { ToastService } from '../../shared/feedback/toast.service';
import { money, dateLabel } from '../../shared/utils/display';
import {
  collectionOptions,
  pricingOptions,
  transportOptions,
  optionLabel,
} from './groupage-labels';
@Component({
  selector: 'g-groupage-wizard',
  providers: [GroupageStore],
  imports: [FormsModule, RouterLink, Card, Button, Input, ShippingFields, ProductPicker],
  templateUrl: './groupage-wizard.page.html',
  styles: `
    .steps {
      list-style: none;
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      padding: 0;
      margin: 0 0 var(--s6);
      gap: var(--s2);
    }
    .steps li {
      border-bottom: 3px solid var(--border);
      padding: var(--s3) 0;
      color: var(--muted);
      font-size: var(--text-xs);
    }
    .steps li[aria-current] {
      border-color: var(--primary);
      color: var(--primary);
      font-weight: 600;
    }
    .lines {
      list-style: none;
      padding: 0;
    }
    .lines li {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--s3);
      padding: var(--s4) 0;
      border-bottom: 1px solid var(--border);
    }
    .lines li div {
      flex: 1;
      min-width: 150px;
      overflow-wrap: anywhere;
    }
    .lines small {
      display: block;
      color: var(--muted);
    }
    .review {
      display: grid;
      gap: var(--s4);
      margin-bottom: var(--s6);
    }
  `,
})
export class GroupageWizardPage {
  readonly context = inject(BoutiqueContextService);
  readonly store = inject(GroupageStore);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  readonly step = signal(1);
  readonly steps = ['Informations', 'Livraison', 'Produits', 'Vérification'];
  readonly error = signal('');
  readonly saving = signal(false);
  title = '';
  description = '';
  closesAt = '';
  readonly shipping = signal<ShippingInput>(defaultShipping());
  readonly lines = signal<GroupageLine[]>([]);
  readonly money = money;
  readonly dateLabel = dateLabel;
  readonly optionLabel = optionLabel;
  readonly transportOptions = transportOptions;
  readonly pricingOptions = pricingOptions;
  readonly collectionOptions = collectionOptions;
  excluded() {
    return this.lines().map((l) => l.productVariantId);
  }
  add(line: GroupageLine) {
    if (!this.lines().some((l) => l.productVariantId === line.productVariantId))
      this.lines.update((list) => [...list, line]);
  }
  remove(id: string) {
    this.lines.update((list) => list.filter((l) => l.productVariantId !== id));
  }
  date() {
    return this.closesAt ? new Date(this.closesAt).toISOString() : undefined;
  }
  next() {
    this.error.set('');
    if (
      this.step() === 1 &&
      (!this.title.trim() ||
        this.title.length > 160 ||
        this.description.length > 5000 ||
        (this.closesAt &&
          (!Number.isFinite(new Date(this.closesAt).getTime()) ||
            new Date(this.closesAt).getTime() <= Date.now())))
    ) {
      this.error.set('Indiquez un titre et, si souhaitée, une date de clôture future.');
      return;
    }
    if (this.step() === 2 && !validShipping(this.shipping())) {
      this.error.set('Renseignez un tarif valide pour la livraison.');
      return;
    }
    this.step.update((value) => Math.min(4, value + 1));
  }
  async save(publish: boolean) {
    const organizationId = this.context.selectedId();
    if (!organizationId || this.saving()) return;
    if (publish && !this.lines().length) {
      this.error.set('Ajoutez au moins un produit pour publier.');
      return;
    }
    this.saving.set(true);
    this.error.set('');
    try {
      if (!this.store.current())
        await this.store.create(organizationId, {
          title: this.title.trim(),
          description: this.description.trim() || undefined,
          closesAt: this.date(),
          shippingTerms: this.shipping(),
        });
      for (const line of this.lines()) {
        if (
          !this.store.current()?.offers?.some((o) => o.productVariantId === line.productVariantId)
        )
          await this.store.add(organizationId, line);
      }
      if (publish) await this.store.open(organizationId);
      const result = this.store.current()!;
      this.toast.show(
        result.status === 'OPEN' ? 'Votre groupage est en ligne 🎉' : 'Brouillon enregistré.',
      );
      await this.router.navigate(['/app/groupages', result.id], {
        queryParams: result.status === 'OPEN' ? { published: '1' } : {},
      });
    } catch (e) {
      this.error.set(apiMessage(e));
      this.toast.show(apiMessage(e), 'error');
    } finally {
      this.saving.set(false);
    }
  }
}
