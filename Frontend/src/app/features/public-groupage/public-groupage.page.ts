import { Component, DestroyRef, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PublicGroupingView, PublicOfferView, PublicProductImageView } from '../../api/generated/models';
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

  imports: [RouterLink, Brand, Card, Badge, Button, Select, Skeleton, EmptyState],
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
    .product-media {
      display: grid;
      gap: var(--s3);
      margin: calc(var(--s2) * -1) 0 var(--s4);
    }
    .product-media-main,
    .product-media-placeholder {
      width: 100%;
      aspect-ratio: 4 / 3;
      border-radius: var(--radius-md);
      overflow: hidden;
      background: var(--surface-subtle, var(--surface));
      border: 1px solid var(--border);
    }
    .product-media-main img {
      width: 100%;
      height: 100%;
      display: block;
      object-fit: contain;
    }
    .product-media-placeholder {
      display: grid;
      place-items: center;
      color: var(--text-muted);
      min-height: 180px;
    }
    .product-thumbnails {
      display: flex;
      gap: var(--s2);
      overflow-x: auto;
      padding: 2px 2px var(--s1);
      scrollbar-width: thin;
    }
    .product-thumb {
      flex: 0 0 56px;
      width: 56px;
      height: 56px;
      padding: 2px;
      border: 2px solid transparent;
      border-radius: var(--radius-md);
      background: var(--surface);
      cursor: pointer;
    }
    .product-thumb[aria-pressed='true'] {
      border-color: var(--primary);
    }
    .product-thumb:focus-visible {
      outline: 3px solid var(--focus, var(--primary));
      outline-offset: 2px;
    }
    .product-thumb img {
      width: 100%;
      height: 100%;
      display: block;
      border-radius: calc(var(--radius-md) - 3px);
      object-fit: contain;
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
    .cart-bar {
      position: sticky;
      bottom: 0;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--s3);
      padding: var(--s4);
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      margin-top: var(--s5);
    }
    @media (max-width: 1023px) {
      main {
        padding-bottom: calc(var(--s8) + 120px);
      }
      .cart-bar {
        position: fixed;
        left: var(--s3);
        right: var(--s3);
        bottom: var(--s3);
        z-index: 10;
        padding-bottom: calc(var(--s4) + env(safe-area-inset-bottom));
      }
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
  readonly activeImages = signal<Record<string, number>>({});
  readonly money = money;
  readonly dateLabel = dateLabel;
  readonly variantLabel = variantLabel;
  private request = 0;
  constructor() {
    const sub = this.route.paramMap.subscribe(() => {
      this.selection.select(
        this.route.snapshot.paramMap.get('publicIdentifier') ??
          this.route.snapshot.paramMap.get('publicId') ??
          '',
      );
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
      }
    } finally {
      if (seq === this.request) this.loading.set(false);
    }
  }
  activeImageFor(offer: PublicOfferView): PublicProductImageView | null {
    const images = offer.images ?? [];
    if (!images.length) return null;
    const key = offer.offerId ?? offer.productVariantId ?? '';
    const index = Math.min(this.activeImages()[key] ?? 0, images.length - 1);
    return images[index] ?? images[0] ?? null;
  }
  selectImage(offer: PublicOfferView, index: number) {
    const images = offer.images ?? [];
    if (index < 0 || index >= images.length) return;
    const key = offer.offerId ?? offer.productVariantId ?? '';
    this.activeImages.update((current) => ({ ...current, [key]: index }));
  }
  productLabel(offer: PublicOfferView) {
    return offer.commercialLabel || offer.catalog?.productName || 'Produit';
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
