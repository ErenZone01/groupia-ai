import { inject, Injectable, signal } from '@angular/core';
import { CampaignView, CreateCampaign, OfferView, ShippingInput } from '../../api/generated/models';
import { GroupiaApi } from '../../api/groupia-api';
import { HttpErrorResponse } from '@angular/common/http';
import { versionConflict } from '../../core/http/api-error';
export interface GroupageLine {
  productVariantId: string;
  name: string;
  variant: string;
  unitPrice: number;
  commercialLabel?: string;
}
@Injectable()
export class GroupageStore {
  private readonly api = inject(GroupiaApi);
  readonly current = signal<CampaignView | null>(null);
  readonly busy = signal(false);
  readonly stale = signal(false);
  private sequence = 0;
  async load(organizationId: string, id: string) {
    const seq = ++this.sequence;
    const result = await this.api.campaign(organizationId, id);
    if (seq === this.sequence) {
      this.current.set(result);
      this.stale.set(false);
    }
    return result;
  }
  async create(organizationId: string, body: CreateCampaign) {
    return this.mutate(organizationId, () => this.api.createCampaign(organizationId, body));
  }
  async add(organizationId: string, line: GroupageLine) {
    const c = this.requireCurrent();
    return this.mutate(organizationId, () =>
      this.api.addOffer(organizationId, c.id!, {
        campaignVersion: this.version(c.version),
        productVariantId: line.productVariantId,
        unitPrice: line.unitPrice,
        commercialLabel: line.commercialLabel,
      }),
    );
  }
  async updatePrice(
    organizationId: string,
    offer: OfferView,
    unitPrice: number,
    commercialLabel?: string,
  ) {
    const c = this.requireCurrent();
    return this.mutate(organizationId, () =>
      this.api.updateOffer(organizationId, c.id!, offer.id!, {
        campaignVersion: this.version(c.version),
        version: this.version(offer.version),
        unitPrice,
        commercialLabel,
      }),
    );
  }
  async open(organizationId: string) {
    const c = this.requireCurrent();
    return this.mutate(organizationId, () =>
      this.api.openCampaign(organizationId, c.id!, { version: this.version(c.version) }),
    );
  }
  async close(organizationId: string) {
    const c = this.requireCurrent();
    return this.mutate(organizationId, () =>
      this.api.closeCampaign(organizationId, c.id!, { version: this.version(c.version) }),
    );
  }
  async settings(
    organizationId: string,
    title: string,
    description: string,
    closesAt: string | undefined,
    shippingTerms: ShippingInput,
  ) {
    const c = this.requireCurrent();
    return this.mutate(organizationId, () =>
      this.api.updateCampaign(organizationId, c.id!, {
        version: this.version(c.version),
        title,
        description,
        closesAt,
        shippingTerms,
      }),
    );
  }
  private version(value?: number) {
    if (!Number.isSafeInteger(value) || value! < 0) throw new Error('Invalid version');
    return value!;
  }
  private requireCurrent() {
    const value = this.current();
    if (!value?.id || this.stale()) throw new Error('Reload required');
    return value;
  }
  private async mutate(organizationId: string, operation: () => Promise<CampaignView>) {
    if (this.busy()) throw new Error('Operation in progress');
    this.busy.set(true);
    try {
      const result = await operation();
      this.current.set(result);
      this.stale.set(false);
      return result;
    } catch (error) {
      if (
        this.current()?.id &&
        (versionConflict(error) ||
          (error instanceof HttpErrorResponse && (error.status === 0 || error.status >= 500)))
      ) {
        this.stale.set(true);
        try {
          await this.load(organizationId, this.current()!.id!);
        } catch {}
      }
      throw error;
    } finally {
      this.busy.set(false);
    }
  }
}
