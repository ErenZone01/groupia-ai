import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { MediaGalleryView } from '../../api/generated/models';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { ProductMediaApi } from './product-media.api';

export interface ImageUpload {
  file: File;
  key: string;
  preview: string;
  state: 'ready' | 'sending' | 'pending' | 'done' | 'failed';
  message: string;
  mediaId?: string;
}

@Injectable()
export class ProductMediaStore {
  private readonly api = inject(ProductMediaApi);
  private readonly auth = inject(AuthService);
  private readonly context = inject(BoutiqueContextService);
  readonly gallery = signal<MediaGalleryView | null>(null);
  readonly queue = signal<ImageUpload[]>([]);
  readonly busy = signal(false);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  private epoch = 0;
  private org = '';
  private product = '';
  constructor() {
    inject(DestroyRef).onDestroy(() => this.reset());
  }
  reset() {
    this.epoch++;
    this.queue().forEach((item) => this.release(item));
    this.queue.set([]);
    this.gallery.set(null);
    this.busy.set(false);
    this.loading.set(false);
    this.error.set('');
    this.notice.set('');
  }
  private scope() {
    const epoch = this.epoch,
      generation = this.auth.generation(),
      org = this.org,
      product = this.product;
    return {
      org,
      product,
      valid: () =>
        epoch === this.epoch &&
        generation === this.auth.generation() &&
        org === this.context.selectedId() &&
        org === this.org &&
        product === this.product,
    };
  }
  async open(org: string, product: string) {
    this.reset();
    this.org = org;
    this.product = product;
    const scope = this.scope();
    this.loading.set(true);
    try {
      const value = await this.api.gallery(org, product);
      if (scope.valid()) this.gallery.set(value);
    } catch (error) {
      if (scope.valid()) this.error.set(apiMessage(error));
    } finally {
      if (scope.valid()) this.loading.set(false);
    }
  }
  select(files: File[]) {
    if (this.busy() || !this.gallery()?.enabled || !this.gallery()?.canManage) return;
    this.error.set('');
    const outstanding = this.queue().filter((item) => item.state !== 'done').length;
    const available =
      5 - (this.gallery()?.items?.length ?? 0) - (this.gallery()?.reservedCount ?? 0) - outstanding;
    if (files.length > available) {
      this.error.set('Un produit peut contenir cinq images maximum.');
      return;
    }
    if (
      files.some(
        (file) =>
          !['image/jpeg', 'image/png', 'image/webp'].includes(file.type) ||
          file.size > 8388608 ||
          !file.size,
      )
    ) {
      this.error.set('Choisissez des images JPEG, PNG ou WebP de 8 Mo maximum.');
      return;
    }
    this.queue.update((items) => [
      ...items,
      ...files.map((file): ImageUpload => ({
        file,
        key: crypto.randomUUID(),
        preview: URL.createObjectURL(file),
        state: 'ready',
        message: 'Prêt',
      })),
    ]);
  }
  discard(item: ImageUpload) {
    if (this.busy()) return;
    this.release(item);
    this.queue.update((items) => items.filter((candidate) => candidate !== item));
  }
  async send() {
    if (this.busy()) return;
    const scope = this.scope();
    this.busy.set(true);
    this.error.set('');
    try {
      for (const item of this.queue()) {
        if (!scope.valid()) return;
        if (item.state === 'done') continue;
        item.state = 'sending';
        item.message = 'Envoi et traitement…';
        this.queue.update((items) => [...items]);
        try {
          const result = item.mediaId
            ? await this.api.operation(scope.org, scope.product, item.mediaId)
            : await this.api.upload(scope.org, scope.product, item.key, item.file);
          if (!scope.valid()) return;
          item.mediaId = result.id;
          if (result.state === 'READY') {
            item.state = 'done';
            item.message = 'Terminé';
            this.release(item);
          } else if (result.state === 'UPLOADING') {
            item.state = 'pending';
            item.message = 'Traitement en attente. Vérifiez à nouveau dans quelques instants.';
          } else {
            item.state = 'failed';
            item.message =
              'L’envoi a été abandonné. Retirez cet aperçu puis ajoutez à nouveau votre image.';
          }
          const gallery = await this.api.gallery(scope.org, scope.product);
          if (!scope.valid()) return;
          this.gallery.set(gallery);
          this.queue.update((items) => [...items]);
          if (item.state !== 'done') break;
        } catch (error) {
          if (!scope.valid()) return;
          item.state = 'failed';
          item.message = apiMessage(error);
          this.error.set(item.message);
          this.queue.update((items) => [...items]);
          break;
        }
      }
      if (scope.valid()) this.notice.set('Galerie actualisée.');
    } finally {
      if (scope.valid()) this.busy.set(false);
    }
  }
  async remove(id: string) {
    if (this.busy()) return;
    const scope = this.scope();
    this.busy.set(true);
    this.error.set('');
    try {
      await this.api.remove(scope.org, scope.product, id);
      if (!scope.valid()) return;
      const gallery = await this.api.gallery(scope.org, scope.product);
      if (scope.valid()) {
        this.gallery.set(gallery);
        this.notice.set('Image supprimée.');
      }
    } catch (error) {
      if (scope.valid()) this.error.set(apiMessage(error));
    } finally {
      if (scope.valid()) this.busy.set(false);
    }
  }
  async move(index: number, destination: number) {
    const gallery = this.gallery(),
      items = [...(gallery?.items ?? [])];
    if (this.busy() || destination < 0 || destination >= items.length || index === destination)
      return;
    const scope = this.scope();
    this.busy.set(true);
    this.error.set('');
    items.splice(destination, 0, items.splice(index, 1)[0]);
    try {
      const result = await this.api.reorder(
        scope.org,
        scope.product,
        gallery?.version ?? 0,
        items.map((item) => item.id!),
      );
      if (scope.valid()) {
        this.gallery.set(result);
        this.notice.set('Ordre des images enregistré.');
      }
    } catch (error) {
      if (scope.valid()) {
        this.error.set(apiMessage(error));
        try {
          const fresh = await this.api.gallery(scope.org, scope.product);
          if (scope.valid()) this.gallery.set(fresh);
        } catch {
          /* Keep the visible error. */
        }
      }
    } finally {
      if (scope.valid()) this.busy.set(false);
    }
  }
  private release(item: ImageUpload) {
    if (item.preview) URL.revokeObjectURL(item.preview);
    item.preview = '';
  }
}
