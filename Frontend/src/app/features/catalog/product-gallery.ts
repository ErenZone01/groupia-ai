import {
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { MediaView } from '../../api/generated/models';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { ProductThumbnail } from '../../shared/ui/product-thumbnail';
import { ProductMediaStore } from './product-media.store';

@Component({
  selector: 'g-product-gallery',
  imports: [Button, Icon, ProductThumbnail],
  providers: [ProductMediaStore],
  templateUrl: './product-gallery.html',
  styleUrl: './product-gallery.scss',
})
export class ProductGallery {
  readonly organizationId = input.required<string>();
  readonly productId = input.required<string>();
  readonly productName = input.required<string>();
  readonly archived = input(false);
  readonly changed = output<void>();
  readonly closed = output<void>();
  readonly store = inject(ProductMediaStore);
  readonly confirm = signal<string | null>(null);
  readonly selectedId = signal<string | null>(null);
  readonly failedPhoto = signal<string | null>(null);
  readonly zoomed = signal(false);
  private readonly photoViewport = viewChild<ElementRef<HTMLDivElement>>('photoViewport');
  readonly selectedIndex = computed(() => {
    const photos = this.store.gallery()?.items ?? [];
    if (!photos.length) return -1;
    const index = photos.findIndex((item) => item.id === this.selectedId());
    return index < 0 ? 0 : index;
  });
  readonly selectedPhoto = computed<MediaView | null>(() => {
    const photos = this.store.gallery()?.items ?? [];
    return photos[this.selectedIndex()] ?? null;
  });
  private readonly auth = inject(AuthService);
  private readonly heading = viewChild<ElementRef<HTMLHeadingElement>>('heading');
  constructor() {
    effect(() => {
      const org = this.organizationId();
      const product = this.productId();
      this.auth.generation();
      untracked(() => {
        this.confirm.set(null);
        this.selectedId.set(null);
        this.failedPhoto.set(null);
        this.zoomed.set(false);
        void this.store.open(org, product);
      });
    });
  }
  selectPhoto(id?: string) {
    if (!id) return;
    this.selectedId.set(id);
    this.zoomed.set(false);
    this.confirm.set(null);
  }
  toggleZoom() {
    const enlarge = !this.zoomed();
    this.zoomed.set(enlarge);
    // Center the 2× canvas after Angular renders it. Users may scroll or swipe
    // inside the image viewport to inspect the edges; outside remains stationary.
    if (enlarge) {
      requestAnimationFrame(() => {
        if (!this.zoomed()) return;
        const element = this.photoViewport()?.nativeElement;
        if (!element) return;
        element.scrollLeft = (element.scrollWidth - element.clientWidth) / 2;
        element.scrollTop = (element.scrollHeight - element.clientHeight) / 2;
      });
    } else {
      const element = this.photoViewport()?.nativeElement;
      element?.scrollTo(0, 0);
    }
  }

  select(event: Event) {
    const input = event.target as HTMLInputElement;
    this.store.select(Array.from(input.files ?? []));
    input.value = '';
  }
  async send() {
    await this.store.send();
    this.changed.emit();
  }
  async move(index: number, destination: number) {
    if (index < 0 || destination < 0) return;
    await this.store.move(index, destination);
    this.changed.emit();
  }
  async remove(id: string) {
    await this.store.remove(id);
    // Preserve the confirmation if the backend reports an error.
    if (!this.store.gallery()?.items?.some((item) => item.id === id)) {
      this.confirm.set(null);
      this.selectedId.set(null);
      this.failedPhoto.set(null);
      this.zoomed.set(false);
      this.heading()?.nativeElement.focus();
    }
    this.changed.emit();
  }
}
