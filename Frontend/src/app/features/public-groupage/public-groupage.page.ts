import { Component, DestroyRef, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { PublicGroupingView } from '../../api/generated/models';
import { GroupiaApi } from '../../api/groupia-api';
import { apiMessage } from '../../core/http/api-error';
import { ThemeService } from '../../core/theme/theme.service';
import { PublicSelectionService } from './public-selection.service';
import { Brand } from '../../shared/ui/brand';
import { Card } from '../../shared/ui/card';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Select } from '../../shared/forms/select';
import { Skeleton } from '../../shared/feedback/skeleton';
import { EmptyState } from '../../shared/feedback/empty-state';
import { money, dateLabel, variantLabel } from '../../shared/utils/display';
@Component({
  selector: 'g-public-groupage-page',
  providers: [PublicSelectionService],
  imports: [Brand, Card, Badge, Button, Select, Skeleton, EmptyState],
  templateUrl: './public-groupage.page.html',
  styles: `
    header {
      background: var(--surface);
      border-bottom: 1px solid var(--border);
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: var(--s5);
      gap: var(--s3);
    }
    header select {
      width: 110px;
    }
    main {
      max-width: 1060px;
      margin: auto;
      padding: var(--s8) var(--s5);
    }
    .intro {
      padding: var(--s8) 0;
    }
    .intro h1 {
      margin: var(--s3) 0;
    }
    .intro p {
      margin-top: var(--s3);
    }
    .products {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 290px), 1fr));
      gap: var(--s5);
    }
    g-card h2 {
      margin: var(--s3) 0;
    }
    g-card p {
      margin-bottom: var(--s3);
    }
    .price {
      font-size: var(--text-xl);
      font-weight: 600;
      color: var(--primary);
    }
    .selection {
      margin-top: var(--s8);
      padding: var(--s5);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
    }
    .selection li {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--s3);
      padding: var(--s3) 0;
    }
    .selection ul {
      list-style: none;
      padding: 0;
    }
    h1,
    h2,
    p,
    li span {
      overflow-wrap: anywhere;
    }
  `,
})
export class PublicGroupagePage {
  private readonly api = inject(GroupiaApi);
  private readonly route = inject(ActivatedRoute);
  private readonly window = inject(DOCUMENT).defaultView;
  readonly selection = inject(PublicSelectionService);
  readonly theme = inject(ThemeService);
  readonly data = signal<PublicGroupingView | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly money = money;
  readonly dateLabel = dateLabel;
  readonly variantLabel = variantLabel;
  private request = 0;
  constructor() {
    const sub = this.route.paramMap.subscribe(() => {
      this.selection.clear();
      void this.load();
    });
    const focus = () => void this.load(false);
    this.window?.addEventListener('focus', focus);
    inject(DestroyRef).onDestroy(() => {
      sub.unsubscribe();
      this.window?.removeEventListener('focus', focus);
      this.request++;
    });
  }
  async load(showLoading = true) {
    const id =
      this.route.snapshot.paramMap.get('publicIdentifier') ??
      this.route.snapshot.paramMap.get('publicId');
    if (!id) return;
    const seq = ++this.request;
    if (showLoading) {
      this.loading.set(true);
      this.data.set(null);
    }
    this.error.set('');
    try {
      const data = await this.api.publicGrouping(id);
      if (seq === this.request) {
        this.selection.reconcile(data);
        this.data.set(data);
      }
    } catch (e) {
      if (seq === this.request) {
        this.error.set(apiMessage(e));
        this.data.set(null);
        this.selection.clear();
      }
    } finally {
      if (seq === this.request) this.loading.set(false);
    }
  }
  reason() {
    const data = this.data();
    if (data?.status === 'CANCELLED') return 'Ce groupage a été annulé.';
    if (data?.closesAt && new Date(data.closesAt).getTime() <= Date.now())
      return 'La date de clôture est passée. Les commandes sont fermées.';
    if (data?.status === 'CLOSED' || data?.status === 'COMPLETED')
      return 'Les commandes de ce groupage sont fermées.';
    return 'Ce groupage ne reçoit pas de commandes actuellement.';
  }
}
