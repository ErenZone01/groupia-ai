import { inject, Injectable } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import { ProductMediaControllerService } from '../../api/generated/services/product-media-controller.service';

@Injectable({ providedIn: 'root' })
export class ProductMediaApi {
  private readonly api = inject(ProductMediaControllerService);
  batch(organizationId: string, productIds: string[]) {
    return firstValueFrom(this.api.productMediaBatch({ organizationId, productIds }));
  }
  gallery(organizationId: string, productId: string) {
    return firstValueFrom(this.api.mediaGallery({ organizationId, productId }));
  }
  operation(organizationId: string, productId: string, mediaId: string) {
    return firstValueFrom(this.api.mediaOperation({ organizationId, productId, mediaId }));
  }
  upload(organizationId: string, productId: string, key: string, file: File) {
    return firstValueFrom(
      this.api
        .uploadProductMedia({ organizationId, productId, 'Idempotency-Key': key, body: { file } })
        .pipe(timeout(90000)),
    );
  }
  remove(organizationId: string, productId: string, mediaId: string) {
    return firstValueFrom(this.api.deleteProductMedia({ organizationId, productId, mediaId }));
  }
  reorder(organizationId: string, productId: string, version: number, mediaIds: string[]) {
    return firstValueFrom(
      this.api.reorderProductMedia({ organizationId, productId, body: { version, mediaIds } }),
    );
  }
}
