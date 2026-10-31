import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { ShippingInput } from '../../api/generated/models';
import { GroupageStore, GroupageLine } from './groupage.store';
import { defaultShipping, validShipping } from './shipping-fields';
import { ProductPicker } from './product-picker';
import { GroupageWizardStepper } from './groupage-wizard-stepper';
import { GroupageWizardSummary } from './groupage-wizard-summary';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
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
  imports: [FormsModule, RouterLink, Button, Input, Icon, ProductPicker, GroupageWizardStepper, GroupageWizardSummary],
  templateUrl: './groupage-wizard.page.html',
  styleUrl: './groupage-wizard.page.scss',
})
export class GroupageWizardPage {
  readonly context = inject(BoutiqueContextService);
  readonly store = inject(GroupageStore);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  readonly step = signal(1);
  readonly visited = signal(1);
  readonly steps = [
    { title: 'Informations', hint: 'Nom et échéance' },
    { title: 'Livraison', hint: 'Transport et frais' },
    { title: 'Produits', hint: 'Variantes et prix' },
    { title: 'Vérification', hint: 'Avant publication' },
  ];
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

  previewTitle() {
    return this.title.trim() || 'Votre futur groupage';
  }
  readonly progress = computed(() => this.step() * 25);

  excluded() {
    return this.lines().map((l) => l.productVariantId);
  }

  add(line: GroupageLine) {
    if (!this.lines().some((l) => l.productVariantId === line.productVariantId)) {
      this.lines.update((list) => [...list, line]);
      this.error.set('');
    }
  }

  remove(id: string) {
    this.lines.update((list) => list.filter((l) => l.productVariantId !== id));
  }

  date() {
    if (!this.closesAt) return undefined;
    const parsed = new Date(this.closesAt);
    return Number.isFinite(parsed.getTime()) ? parsed.toISOString() : undefined;
  }

  rateLabel() {
    const rate = this.shipping().shippingRate;
    if (rate === undefined || rate === null || !Number.isFinite(rate)) return 'À définir';
    const unit: Record<string, string> = {
      PER_KG: ' / kg',
      PER_CBM: ' / m³',
      PER_ITEM: ' / article',
      FIXED: ' · forfait',
    };
    return money(rate) + (unit[this.shipping().shippingPricingMethod ?? ''] ?? '');
  }

  patchShipping(change: Partial<ShippingInput>) {
    this.shipping.update((value) => {
      const next = { ...value, ...change };
      if (next.shippingPricingMethod !== 'PER_KG') delete next.volumetricDivisor;
      return next;
    });
    this.error.set('');
  }

  private validateInformation() {
    const name = this.title.trim();
    if (!name || name.length > 160 || this.description.length > 5000) {
      return 'Indiquez un nom de groupage valide (160 caractères maximum).';
    }
    if (
      this.closesAt &&
      (!Number.isFinite(new Date(this.closesAt).getTime()) ||
        new Date(this.closesAt).getTime() <= Date.now())
    ) {
      return 'La date de clôture doit être dans le futur.';
    }
    return '';
  }

  private validateShipping() {
    return validShipping(this.shipping())
      ? ''
      : 'Renseignez des conditions et un tarif de livraison valides.';
  }

  private validateBefore(target: number) {
    if (target >= 2) {
      const infoError = this.validateInformation();
      if (infoError) return { step: 1, message: infoError };
    }
    if (target >= 3) {
      const shippingError = this.validateShipping();
      if (shippingError) return { step: 2, message: shippingError };
    }
    return null;
  }

  navigateTo(target: number) {
    if (this.saving() || target < 1 || target > 4 || target > this.visited()) return;
    const invalid = target > this.step() ? this.validateBefore(target) : null;
    if (invalid) {
      this.step.set(invalid.step);
      this.error.set(invalid.message);
      return;
    }
    this.error.set('');
    this.step.set(target);
  }

  next() {
    if (this.saving()) return;
    const target = Math.min(4, this.step() + 1);
    const invalid = this.validateBefore(target);
    if (invalid) {
      this.step.set(invalid.step);
      this.error.set(invalid.message);
      return;
    }
    this.error.set('');
    this.step.set(target);
    this.visited.update((value) => Math.max(value, target));
  }

  async save(publish: boolean) {
    const organizationId = this.context.selectedId();
    if (!organizationId || this.saving()) return;

    const invalid = this.validateBefore(4);
    if (invalid) {
      this.step.set(invalid.step);
      this.error.set(invalid.message);
      return;
    }
    if (publish && !this.lines().length) {
      this.step.set(3);
      this.error.set('Ajoutez au moins une variante pour publier ce groupage.');
      return;
    }

    this.saving.set(true);
    this.error.set('');
    try {
      if (!this.store.current()) {
        await this.store.create(organizationId, {
          title: this.title.trim(),
          description: this.description.trim() || undefined,
          closesAt: this.date(),
          shippingTerms: this.shipping(),
        });
      }
      for (const line of this.lines()) {
        if (!this.store.current()?.offers?.some((o) => o.productVariantId === line.productVariantId)) {
          await this.store.add(organizationId, line);
        }
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
