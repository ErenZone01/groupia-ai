import { inject, Injectable, signal } from '@angular/core';
import { ProductView } from '../../api/generated/models';
import { GroupiaApi } from '../../api/groupia-api';
import { apiMessage } from '../../core/http/api-error';
import { AuthService } from '../../core/auth/auth.service';
@Injectable()
export class CatalogStore {
  private readonly api = inject(GroupiaApi);
  private readonly auth = inject(AuthService);
  readonly products = signal<ProductView[]>([]);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly page = signal(0);
  readonly totalPages = signal(0);
  private request = 0;
  clear() {
    this.request++;
    this.products.set([]);
    this.loading.set(false);
    this.error.set('');
    this.page.set(0);
    this.totalPages.set(0);
  }
  async load(organizationId: string, page = 0) {
    const request = ++this.request;
    const generation = this.auth.generation();
    const current = () => request === this.request && generation === this.auth.generation();
    this.loading.set(true);
    this.error.set('');
    this.products.set([]);
    try {
      const result = await this.api.products(organizationId, page);
      const details: ProductView[] = [];
      const items = result.items ?? [];
      for (let i = 0; i < items.length; i += 6) {
        if (!current()) return;
        details.push(
          ...(await Promise.all(
            items.slice(i, i + 6).map((item) => this.api.product(organizationId, item.id!)),
          )),
        );
      }
      if (!current()) return;
      this.products.set(details);
      this.page.set(result.page ?? page);
      this.totalPages.set(result.totalPages ?? 0);
    } catch (e) {
      if (current()) this.error.set(apiMessage(e));
    } finally {
      if (current()) this.loading.set(false);
    }
  }
}
