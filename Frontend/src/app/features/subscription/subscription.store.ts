import { effect, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import type {
  BillingContactInput,
  SubscriptionDetails,
  SubscriptionPeriodView,
} from '../../api/generated/models';
import { SubscriptionControllerService } from '../../api/generated/services';
import { AuthService } from '../../core/auth/auth.service';
import { apiMessage } from '../../core/http/api-error';

@Injectable({ providedIn: 'root' })
export class SubscriptionStore {
  private readonly api = inject(SubscriptionControllerService);
  private readonly auth = inject(AuthService);
  readonly details = signal<SubscriptionDetails | null>(null);
  readonly history = signal<SubscriptionPeriodView[]>([]);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  private organizationId = '';
  private generation = 0;
  private sessionGeneration = -1;
  private actionGeneration = 0;
  private pending?: Promise<void>;
  private pendingOrganizationId = '';
  private pendingSessionGeneration = -1;

  constructor() {
    effect(() => {
      const sessionGeneration = this.auth.generation();
      if (this.sessionGeneration >= 0 && sessionGeneration !== this.sessionGeneration) {
        this.resetSession(sessionGeneration);
      }
    });
  }

  async load(organizationId: string, force = false) {
    if (!organizationId) return;
    const sessionGeneration = this.auth.generation();
    if (sessionGeneration !== this.sessionGeneration) this.resetSession(sessionGeneration);
    if (
      !force &&
      organizationId === this.organizationId &&
      sessionGeneration === this.sessionGeneration &&
      this.details()
    )
      return;
    if (
      organizationId === this.pendingOrganizationId &&
      sessionGeneration === this.pendingSessionGeneration &&
      this.pending
    )
      return this.pending;

    const generation = ++this.generation;
    this.organizationId = organizationId;
    this.loading.set(true);
    this.error.set('');
    if (this.details() && organizationId !== this.pendingOrganizationId) {
      this.details.set(null);
      this.history.set([]);
    }

    const request = this.fetch(organizationId, generation, sessionGeneration);
    this.pendingOrganizationId = organizationId;
    this.pendingSessionGeneration = sessionGeneration;
    this.pending = request;
    try {
      await request;
    } finally {
      if (generation === this.generation && sessionGeneration === this.auth.generation()) {
        this.pending = undefined;
        this.pendingOrganizationId = '';
        this.pendingSessionGeneration = -1;
        this.loading.set(false);
      }
    }
  }

  async saveBillingContact(body: BillingContactInput) {
    const organizationId = this.organizationId;
    if (this.busy() || !this.details()?.canManageSubscription) return;
    const sessionGeneration = this.sessionGeneration;
    const actionGeneration = ++this.actionGeneration;
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(
        this.api.updateBillingContact({ organizationId, body }).pipe(timeout(20000)),
      );
    } catch (error) {
      if (organizationId === this.organizationId && sessionGeneration === this.auth.generation())
        this.error.set(apiMessage(error));
    } finally {
      if (organizationId === this.organizationId && sessionGeneration === this.auth.generation())
        await this.load(organizationId, true);
      if (actionGeneration === this.actionGeneration) this.busy.set(false);
    }
  }

  async start() {
    const organizationId = this.organizationId;
    const details = this.details();
    if (
      this.busy() ||
      !details?.canManageSubscription ||
      !details.billingProviderAvailable ||
      !details.planConfigured
    )
      return;
    const sessionGeneration = this.sessionGeneration;
    const actionGeneration = ++this.actionGeneration;
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(this.api.start({ organizationId }).pipe(timeout(40000)));
    } catch (error) {
      if (organizationId === this.organizationId && sessionGeneration === this.auth.generation())
        this.error.set(apiMessage(error));
    } finally {
      if (organizationId === this.organizationId && sessionGeneration === this.auth.generation())
        await this.load(organizationId, true);
      if (actionGeneration === this.actionGeneration) this.busy.set(false);
    }
  }

  async cancel() {
    const organizationId = this.organizationId;
    if (this.busy() || !this.details()?.canManageSubscription) return;
    const sessionGeneration = this.sessionGeneration;
    const actionGeneration = ++this.actionGeneration;
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(this.api.cancel({ organizationId }).pipe(timeout(40000)));
    } catch (error) {
      if (organizationId === this.organizationId && sessionGeneration === this.auth.generation())
        this.error.set(apiMessage(error));
    } finally {
      if (organizationId === this.organizationId && sessionGeneration === this.auth.generation())
        await this.load(organizationId, true);
      if (actionGeneration === this.actionGeneration) this.busy.set(false);
    }
  }

  private async fetch(organizationId: string, generation: number, sessionGeneration: number) {
    try {
      const [details, history] = await Promise.all([
        firstValueFrom(this.api.details({ organizationId }).pipe(timeout(15000))),
        firstValueFrom(this.api.history({ organizationId }).pipe(timeout(15000))),
      ]);
      if (generation !== this.generation || sessionGeneration !== this.auth.generation()) return;
      this.details.set(details);
      this.history.set(history);
    } catch (error) {
      if (generation === this.generation && sessionGeneration === this.auth.generation()) {
        this.error.set(apiMessage(error));
        this.details.set(null);
        this.history.set([]);
      }
    }
  }

  private resetSession(sessionGeneration: number) {
    this.sessionGeneration = sessionGeneration;
    this.generation++;
    this.actionGeneration++;
    this.pending = undefined;
    this.pendingOrganizationId = '';
    this.pendingSessionGeneration = -1;
    this.organizationId = '';
    this.details.set(null);
    this.history.set([]);
    this.loading.set(false);
    this.busy.set(false);
    this.error.set('');
  }
}
