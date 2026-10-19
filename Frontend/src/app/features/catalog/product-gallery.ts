import {
  Component,
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
import { Button } from '../../shared/ui/button';
import { ProductMediaStore } from './product-media.store';

@Component({
  selector: 'g-product-gallery',
  imports: [Button],
  providers: [ProductMediaStore],
  template: `
    <section aria-label="Images du produit" [attr.aria-busy]="store.busy()">
      <div class="section-heading">
        <div>
          <h2 #heading tabindex="-1">Images de {{ productName() }}</h2>
          <p>Jusqu’à cinq images. La première est l’image principale.</p>
        </div>
        <button gButton variant="secondary" (click)="closed.emit()">Fermer</button>
      </div>
      @if (store.loading()) {
        <p role="status">Chargement des images…</p>
      }
      @if (store.error()) {
        <p class="form-error" role="alert">{{ store.error() }}</p>
      }
      <p class="notice" aria-live="polite">{{ store.notice() }}</p>
      @if (store.gallery(); as gallery) {
        @if (!gallery.enabled) {
          <p>Les images ne sont pas encore disponibles pour cette boutique.</p>
        }
        @if (gallery.enabled && gallery.canManage && !archived()) {
          <label class="picker"
            >Ajouter des images<input
              type="file"
              multiple
              accept="image/jpeg,image/png,image/webp"
              [disabled]="
                store.busy() || (gallery.items?.length ?? 0) + (gallery.reservedCount ?? 0) >= 5
              "
              (change)="select($event)"
          /></label>
          <p>JPEG, PNG ou WebP statique · 8 Mo maximum par image.</p>
        }
        @if (archived()) {
          <p>Produit archivé : vous pouvez consulter ou retirer ses images.</p>
        }
        <ol class="gallery">
          @for (item of gallery.items; track item.id; let i = $index) {
            <li>
              <img
                [src]="item.thumbnailUrl"
                [alt]="productName() + ' — image ' + (i + 1)"
                width="240"
                height="180"
              />
              <p>{{ i === 0 ? 'Image principale' : 'Image ' + (i + 1) }}</p>
              @if (gallery.enabled && gallery.canManage) {
                <div class="actions">
                  @if (!archived()) {
                    <button
                      gButton
                      variant="secondary"
                      [disabled]="store.busy() || i === 0"
                      (click)="move(i, 0)"
                    >
                      Définir comme principale
                    </button>
                    <button
                      gButton
                      variant="secondary"
                      [disabled]="store.busy() || i === 0"
                      [attr.aria-label]="'Monter l’image ' + (i + 1)"
                      (click)="move(i, i - 1)"
                    >
                      Monter
                    </button>
                    <button
                      gButton
                      variant="secondary"
                      [disabled]="store.busy() || i === (gallery.items?.length ?? 0) - 1"
                      [attr.aria-label]="'Descendre l’image ' + (i + 1)"
                      (click)="move(i, i + 1)"
                    >
                      Descendre
                    </button>
                  }
                  <button
                    gButton
                    variant="secondary"
                    [disabled]="store.busy()"
                    [attr.aria-label]="'Supprimer l’image ' + (i + 1)"
                    (click)="confirm.set(item.id!)"
                  >
                    Supprimer
                  </button>
                </div>
                @if (confirm() === item.id) {
                  <div role="group" aria-label="Confirmer la suppression">
                    <p>Supprimer cette image du produit ?</p>
                    <button gButton [disabled]="store.busy()" (click)="remove(item.id!)">
                      Confirmer la suppression</button
                    ><button gButton variant="secondary" (click)="confirm.set(null)">
                      Annuler
                    </button>
                  </div>
                }
              }
            </li>
          } @empty {
            <li class="empty">Aucune image pour ce produit.</li>
          }
        </ol>
      }
      @if (store.queue().length) {
        <ul class="uploads" aria-label="Images sélectionnées">
          @for (item of store.queue(); track item.key; let i = $index) {
            <li>
              @if (item.preview) {
                <img
                  [src]="item.preview"
                  [alt]="'Aperçu de ' + productName()"
                  width="80"
                  height="80"
                />
              }
              <span aria-live="polite">Image {{ i + 1 }} — {{ item.message }}</span
              ><button
                gButton
                variant="secondary"
                [disabled]="store.busy()"
                (click)="store.discard(item)"
              >
                Retirer l’aperçu
              </button>
            </li>
          }
        </ul>
        <button gButton [disabled]="store.busy()" (click)="send()">
          {{ store.busy() ? 'Envoi en cours…' : 'Envoyer / réessayer' }}
        </button>
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    h2 {
      overflow-wrap: anywhere;
    }
    .gallery {
      list-style: none;
      padding: 0;
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 1fr));
      gap: var(--s4);
    }
    .gallery li {
      min-width: 0;
      padding: var(--s3);
      border: 1px solid var(--border);
      border-radius: var(--radius-md);
    }
    .gallery img {
      width: 100%;
      height: 180px;
      object-fit: contain;
      background: var(--surface-alt);
    }
    .actions,
    .section-heading {
      display: flex;
      flex-wrap: wrap;
      gap: var(--s2);
    }
    button {
      min-height: 44px;
      white-space: normal;
    }
    .picker {
      display: grid;
      gap: var(--s2);
      font-weight: 600;
    }
    input {
      max-width: 100%;
      min-height: 44px;
    }
    .uploads {
      list-style: none;
      padding: 0;
    }
    .uploads li {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--s3);
      margin-block: var(--s3);
    }
    .uploads img {
      object-fit: contain;
    }
    .notice {
      color: var(--muted);
    }
  `,
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
  private readonly auth = inject(AuthService);
  private readonly heading = viewChild<ElementRef<HTMLHeadingElement>>('heading');
  constructor() {
    effect(() => {
      const org = this.organizationId(),
        product = this.productId();
      this.auth.generation();
      untracked(() => {
        this.confirm.set(null);
        void this.store.open(org, product);
      });
    });
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
    await this.store.move(index, destination);
    this.changed.emit();
  }
  async remove(id: string) {
    await this.store.remove(id);
    this.confirm.set(null);
    this.heading()?.nativeElement.focus();
    this.changed.emit();
  }
}
