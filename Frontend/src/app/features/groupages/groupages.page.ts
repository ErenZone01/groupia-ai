import { Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CampaignPage } from '../../api/generated/models';
import { GroupiaApi } from '../../api/groupia-api';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { Card } from '../../shared/ui/card';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Skeleton } from '../../shared/feedback/skeleton';
import { EmptyState } from '../../shared/feedback/empty-state';
import { dateLabel, statusLabel } from '../../shared/utils/display';
@Component({
  selector: 'g-groupages-page',
  imports: [RouterLink, Card, Badge, Button, Skeleton, EmptyState],
  template: `
    <div class="page-heading">
      <div>
        <p class="eyebrow">Ensemble, on va plus loin</p>
        <h1>Groupages</h1>
        <p>Préparez, publiez et partagez vos sélections.</p>
      </div>
      <a gButton routerLink="/app/groupages/nouveau">Créer un groupage</a>
    </div>
    @if (loading()) {
      <g-skeleton /><g-skeleton />
    } @else if (error()) {
      <p class="form-error" role="alert">{{ error() }}</p>
      <button gButton (click)="load()">Réessayer</button>
    } @else if (!data()?.items?.length) {
      <g-empty-state
        title="Votre premier groupage vous attend"
        description="Réunissez vos produits et fixez vos prix pour partager votre sélection."
        icon="group"
      />
    } @else {
      <div class="list">
        @for (group of data()?.items; track group.id) {
          <g-card
            ><div class="section-heading">
              <h2>{{ group.title }}</h2>
              <g-badge [tone]="group.status === 'OPEN' ? 'positive' : 'neutral'">{{
                statusLabel(group.status)
              }}</g-badge>
            </div>
            <p class="muted">{{ dateLabel(group.closesAt) }}</p>
            <a gButton variant="secondary" [routerLink]="['/app/groupages', group.id]"
              >Voir le groupage</a
            ></g-card
          >
        }
      </div>
    }
    @if ((data()?.totalPages ?? 0) > 1) {
      <nav class="actions" aria-label="Pages des groupages">
        <button
          gButton
          variant="secondary"
          [disabled]="!data()?.page || loading()"
          (click)="load((data()?.page ?? 0) - 1)"
        >
          Précédent</button
        ><span>Page {{ (data()?.page ?? 0) + 1 }} / {{ data()?.totalPages }}</span
        ><button
          gButton
          variant="secondary"
          [disabled]="(data()?.page ?? 0) + 1 >= (data()?.totalPages ?? 0) || loading()"
          (click)="load((data()?.page ?? 0) + 1)"
        >
          Suivant
        </button>
      </nav>
    }
  `,
  styles: `
    .list {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr));
      gap: var(--s5);
    }
    g-card p {
      margin: var(--s4) 0;
    }
  `,
})
export class GroupagesPage {
  private readonly api = inject(GroupiaApi);
  readonly context = inject(BoutiqueContextService);
  readonly data = signal<CampaignPage | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly statusLabel = statusLabel;
  readonly dateLabel = dateLabel;
  private request = 0;
  constructor() {
    effect(() => {
      const id = this.context.selectedId();
      if (id) void this.load();
    });
  }
  async load(page = 0) {
    const id = this.context.selectedId();
    if (!id) return;
    const seq = ++this.request;
    this.loading.set(true);
    this.error.set('');
    this.data.set(null);
    try {
      const result = await this.api.campaigns(id, page);
      if (seq === this.request) this.data.set(result);
    } catch (e) {
      if (seq === this.request) this.error.set(apiMessage(e));
    } finally {
      if (seq === this.request) this.loading.set(false);
    }
  }
}
