import { Component, ElementRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { ProductView, ProductMediaSummary } from '../../api/generated/models';
import { AuthService } from '../../core/auth/auth.service';
import { Icon } from '../../shared/ui/icon';
import { ProductThumbnail } from '../../shared/ui/product-thumbnail';
import { ProductGallery } from './product-gallery';
import { ProductMediaApi } from './product-media.api';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { Button } from '../../shared/ui/button';
import { Badge } from '../../shared/ui/badge';
import { EmptyState } from '../../shared/feedback/empty-state';
import { Skeleton } from '../../shared/feedback/skeleton';
import { CatalogStore } from './catalog.store';
import { ProductForm } from './product-form';
import { ToastService } from '../../shared/feedback/toast.service';
import { statusLabel, variantLabel } from '../../shared/utils/display';

type CatalogFilter = 'all' | 'active' | 'archived';
type CatalogSort = 'recent' | 'oldest' | 'name' | 'variants';

@Component({
  selector: 'g-catalog-page',
  providers: [CatalogStore],
  imports: [Button, Badge, EmptyState, Skeleton, ProductForm, Icon, ProductGallery, ProductThumbnail],
  templateUrl: './catalog.page.html',
  styleUrl: './catalog.page.scss',
})
export class CatalogPage {
  readonly context = inject(BoutiqueContextService);
  readonly store = inject(CatalogStore);
  readonly creating = signal(false);
  readonly selectedProduct = signal<ProductView | null>(null);
  readonly media = signal<Record<string, ProductMediaSummary>>({});
  readonly mediaError = signal('');
  readonly search = signal('');
  readonly filter = signal<CatalogFilter>('all');
  readonly sort = signal<CatalogSort>('recent');
  readonly showAll = signal(false);
  readonly helpVisible = signal(true);
  readonly expandedVariants = signal<ReadonlySet<string>>(new Set<string>());
  private readonly createDialog = viewChild<ElementRef<HTMLDialogElement>>('createDialog');
  private readonly imageDialog = viewChild<ElementRef<HTMLDialogElement>>('imageDialog');
  private readonly mediaApi = inject(ProductMediaApi);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private mediaRequest = 0;

  readonly statusLabel = statusLabel;
  readonly variantLabel = variantLabel;

  // Counts, search, filters and sorting concern only the loaded backend page.
  // Don't imply a global catalog filter without a server-side endpoint.
  readonly counts = computed(() => {
    const items = this.store.products();
    return {
      all: items.length,
      active: items.filter((item) => item.status === 'ACTIVE').length,
      archived: items.filter((item) => item.status === 'ARCHIVED').length,
    };
  });
  readonly matchingProducts = computed(() => {
    const query = this.search().trim().toLocaleLowerCase('fr');
    const selection = this.filter();
    const sort = this.sort();
    const products = this.store.products().filter((item) => {
      if (selection === 'active' && item.status !== 'ACTIVE') return false;
      if (selection === 'archived' && item.status !== 'ARCHIVED') return false;
      return !query ||
        (item.name ?? '').toLocaleLowerCase('fr').includes(query) ||
        (item.description ?? '').toLocaleLowerCase('fr').includes(query) ||
        (item.variants ?? []).some((variant) => [
          variant.sku ?? '',
          ...Object.values(variant.attributes ?? {}),
        ].some((value) => value.toLocaleLowerCase('fr').includes(query)));
    });
    return [...products].sort((a, b) => {
      if (sort === 'name') return (a.name ?? '').localeCompare(b.name ?? '', 'fr');
      if (sort === 'variants') return (b.variants?.length ?? 0) - (a.variants?.length ?? 0);
      const time = (a.createdAt ?? '').localeCompare(b.createdAt ?? '');
      return sort === 'oldest' ? time : -time;
    });
  });
  readonly visibleProducts = computed(() =>
    this.showAll() ? this.matchingProducts() : this.matchingProducts().slice(0, 6),
  );

  constructor() {
    effect(() => {
      const id = this.context.selectedId();
      this.auth.generation();
      // The dialog view queries are signals too. Don't reload the catalog when
      // they first become available, only when the boutique or session changes.
      untracked(() => {
        this.closeCreate();
        this.closeGallery();
        this.media.set({});
        this.search.set('');
        this.filter.set('all');
        this.sort.set('recent');
        this.showAll.set(false);
        this.expandedVariants.set(new Set());
        if (id) void this.store.load(id);
        else this.store.clear();
      });
    });
    effect(() => {
      this.store.products();
      this.context.selectedId();
      this.auth.generation();
      untracked(() => void this.loadMedia());
    });
  }

  setFilter(next: CatalogFilter) {
    this.filter.set(next);
    this.showAll.set(false);
  }
  changeSort(next: string) {
    if (['recent', 'oldest', 'name', 'variants'].includes(next)) {
      this.sort.set(next as CatalogSort);
      this.showAll.set(false);
    }
  }
  resetFilters() {
    this.search.set('');
    this.filter.set('all');
    this.sort.set('recent');
    this.showAll.set(false);
  }
  toggleVariants(id: string) {
    this.expandedVariants.update((ids) => {
      const next = new Set(ids);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  load(page = 0) {
    const id = this.context.selectedId();
    if (id) {
      this.showAll.set(false);
      this.expandedVariants.set(new Set());
      void this.store.load(id, page);
    }
  }
  openCreate() {
    this.creating.set(true);
    this.createDialog()?.nativeElement.showModal();
  }
  closeCreate() {
    this.creating.set(false);
    const dialog = this.createDialog()?.nativeElement;
    if (dialog?.open) dialog.close();
  }

  onCreateDialogCancel(event: Event) {
    // A file input's native "cancel" event bubbles through its ancestor dialog.
    // Only the dialog's own Escape event should close the dialog.
    if (event.target !== event.currentTarget) return;
    event.preventDefault();
    this.closeCreate();
  }

  onGalleryDialogCancel(event: Event) {
    // Canceling the OS file picker is not a request to close the product gallery.
    // Otherwise Angular clears selectedProduct while the dialog remains open,
    // leaving an empty/white modal covering the catalog.
    if (event.target !== event.currentTarget) return;
    event.preventDefault();
    this.closeGallery();
  }

  openGallery(product: ProductView) {
    if (!product.id) return;
    this.selectedProduct.set(product);
    this.imageDialog()?.nativeElement.showModal();
  }
  closeGallery() {
    this.selectedProduct.set(null);
    const dialog = this.imageDialog()?.nativeElement;
    if (dialog?.open) dialog.close();
  }
  created(product: ProductView) {
    this.closeCreate();
    this.toast.show('Produit créé.');
    this.load();
    this.openGallery(product);
  }

  async loadMedia() {
    const request = ++this.mediaRequest;
    const org = this.context.selectedId();
    const generation = this.auth.generation();
    const ids = this.store.products().map((p) => p.id).filter((id): id is string => !!id);
    this.mediaError.set('');
    this.media.set({});
    if (!org || !ids.length) return;
    const current = () =>
      request === this.mediaRequest &&
      org === this.context.selectedId() &&
      generation === this.auth.generation();
    try {
      const response = await this.mediaApi.batch(org, ids);
      if (current()) this.media.set(Object.fromEntries(
        response.filter((item) => !!item.productId && ids.includes(item.productId))
          .map((item) => [item.productId!, item]),
      ));
    } catch {
      if (current()) this.mediaError.set('Les images ne peuvent pas être chargées pour le moment.');
    }
  }
}
