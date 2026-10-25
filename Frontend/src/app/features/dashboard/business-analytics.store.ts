import { computed, DestroyRef, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { Subscription, timeout } from 'rxjs';
import { BusinessAnalyticsControllerService } from '../../api/generated/services/business-analytics-controller.service';
import { BusinessAnalyticsView } from '../../api/generated/models/business-analytics-view';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { AuthService } from '../../core/auth/auth.service';
import { AnalyticsPeriod, validateAnalytics } from './business-analytics.model';

@Injectable()
export class BusinessAnalyticsStore {
  private readonly api = inject(BusinessAnalyticsControllerService);
  private readonly context = inject(BoutiqueContextService);
  private readonly auth = inject(AuthService);
  private subscription?: Subscription;
  readonly period = signal<AnalyticsPeriod>(12);
  private readonly revision = signal(0);
  private readonly key = computed(() =>
    this.auth.signedIn() && this.context.selectedId()
      ? `${this.auth.generation()}:${this.context.selectedId()}:${this.period()}:${this.revision()}`
      : '',
  );
  private readonly result = signal<{
    key: string;
    data: BusinessAnalyticsView | null;
    error: string;
  }>({ key: '', data: null, error: '' });
  readonly data = computed(() =>
    this.key() && this.result().key === this.key() ? this.result().data : null,
  );
  readonly error = computed(() =>
    this.key() && this.result().key === this.key() ? this.result().error : '',
  );
  readonly loading = computed(() => !!this.key() && this.result().key !== this.key());
  constructor() {
    effect(() => {
      const key = this.key();
      const org = this.context.selectedId();
      const period = this.period();
      untracked(() => {
        this.subscription?.unsubscribe();
        this.result.set({ key: '', data: null, error: '' });
        if (!key || !org) return;
        this.subscription = this.api
          .businessAnalytics({ organizationId: org, months: period })
          .pipe(timeout(15000))
          .subscribe({
            next: (data) => {
              if (key !== this.key()) return;
              try {
                this.result.set({ key, data: validateAnalytics(data, period), error: '' });
              } catch {
                this.result.set({
                  key,
                  data: null,
                  error:
                    'Ces chiffres ne peuvent pas être affichés avec une précision garantie. Réessayez ou contactez l’assistance.',
                });
              }
            },
            error: () => {
              if (key === this.key())
                this.result.set({
                  key,
                  data: null,
                  error:
                    'Les statistiques sont momentanément indisponibles. Vos commandes restent accessibles.',
                });
            },
          });
      });
    });
    inject(DestroyRef).onDestroy(() => this.subscription?.unsubscribe());
  }
  retry() {
    this.revision.update((value) => value + 1);
  }
}
