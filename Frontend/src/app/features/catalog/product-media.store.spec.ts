import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { ProductMediaApi } from './product-media.api';
import { ProductMediaStore } from './product-media.store';
import { MediaGalleryView } from '../../api/generated/models';

describe('Galerie produit', () => {
  let store: ProductMediaStore;
  const generation = signal(1),
    selectedId = signal<string | null>('shop');
  const empty: MediaGalleryView = {
    productId: 'product',
    version: 0,
    enabled: true,
    canManage: true,
    items: [],
    reservedCount: 0,
  };
  let api: {
    gallery: ReturnType<typeof vi.fn>;
    upload: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
    reorder: ReturnType<typeof vi.fn>;
    operation: ReturnType<typeof vi.fn>;
  };
  const file = () => new File(['png fixture'], 'photo.png', { type: 'image/png' });
  beforeEach(async () => {
    generation.set(1);
    selectedId.set('shop');
    vi.stubGlobal(
      'URL',
      Object.assign(URL, {
        createObjectURL: vi.fn(() => 'blob:preview'),
        revokeObjectURL: vi.fn(),
      }),
    );
    api = {
      gallery: vi.fn().mockResolvedValue(empty),
      upload: vi.fn().mockResolvedValue({ id: 'image', state: 'READY' }),
      remove: vi.fn().mockResolvedValue(undefined),
      reorder: vi.fn().mockResolvedValue(empty),
      operation: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [
        ProductMediaStore,
        { provide: ProductMediaApi, useValue: api },
        { provide: AuthService, useValue: { generation } },
        { provide: BoutiqueContextService, useValue: { selectedId } },
      ],
    });
    store = TestBed.inject(ProductMediaStore);
    await store.open('shop', 'product');
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.unstubAllGlobals();
  });
  it('charge une galerie vide sans ajouter de média', () => {
    expect(store.gallery()?.items).toEqual([]);
    expect(api.upload).not.toHaveBeenCalled();
  });
  it('refuse une sélection dépassant cinq', () => {
    store.select(Array.from({ length: 6 }, file));
    expect(store.queue()).toHaveLength(0);
    expect(store.error()).toContain('cinq');
  });
  it('refuse les formats non pris en charge', () => {
    store.select([new File(['svg'], 'image.svg', { type: 'image/svg+xml' })]);
    expect(store.error()).toContain('JPEG');
  });
  it('refuse plus de 8 MiB', () => {
    store.select([new File([new Uint8Array(8388609)], 'image.png', { type: 'image/png' })]);
    expect(store.queue()).toHaveLength(0);
  });
  it('envoie séquentiellement et révoque les aperçus terminés', async () => {
    let finish!: (value: unknown) => void;
    api.upload.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    store.select([file(), file()]);
    const pending = store.send();
    expect(api.upload).toHaveBeenCalledTimes(1);
    expect(store.busy()).toBe(true);
    finish({ id: 'first', state: 'READY' });
    await pending;
    expect(api.upload).toHaveBeenCalledTimes(2);
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2);
    expect(store.busy()).toBe(false);
  });
  it('réutilise la clé après erreur et garde les succès précédents', async () => {
    api.upload
      .mockResolvedValueOnce({ id: 'first', state: 'READY' })
      .mockRejectedValueOnce(new Error());
    store.select([file(), file()]);
    await store.send();
    const key = api.upload.mock.calls[1][2];
    await store.send();
    expect(api.upload).toHaveBeenCalledTimes(3);
    expect(api.upload.mock.calls[2][2]).toBe(key);
  });
  it('suit une opération incertaine sans nouvel upload', async () => {
    api.upload.mockResolvedValueOnce({ id: 'pending', state: 'UPLOADING' });
    api.operation.mockResolvedValue({ id: 'pending', state: 'READY' });
    store.select([file()]);
    await store.send();
    await store.send();
    expect(api.upload).toHaveBeenCalledTimes(1);
    expect(api.operation).toHaveBeenCalledWith('shop', 'product', 'pending');
  });
  for (const change of ['session', 'boutique', 'produit']) {
    it(`ignore les réponses après changement de ${change}`, async () => {
      let finish!: (value: unknown) => void;
      api.upload.mockReturnValue(
        new Promise((resolve) => {
          finish = resolve;
        }),
      );
      store.select([file()]);
      const pending = store.send();
      if (change === 'session') generation.set(2);
      if (change === 'boutique') selectedId.set('other');
      if (change === 'produit') await store.open('shop', 'other');
      finish({ id: 'old-image', state: 'READY' });
      await pending;
      expect(store.gallery()?.items).toEqual([]);
      expect(store.notice()).toBe('');
    });
  }
  it('révoque les aperçus au changement de produit', async () => {
    store.select([file()]);
    await store.open('shop', 'other');
    expect(URL.revokeObjectURL).toHaveBeenCalled();
    expect(store.queue()).toHaveLength(0);
  });
  it('supprime et recharge la galerie', async () => {
    await store.remove('image');
    expect(api.remove).toHaveBeenCalledWith('shop', 'product', 'image');
    expect(store.notice()).toContain('supprimée');
  });
  it('définit la principale par reorder versionné', async () => {
    api.gallery.mockResolvedValue({ ...empty, version: 4, items: [{ id: 'a' }, { id: 'b' }] });
    await store.open('shop', 'product');
    await store.move(1, 0);
    expect(api.reorder).toHaveBeenCalledWith('shop', 'product', 4, ['b', 'a']);
  });
});
