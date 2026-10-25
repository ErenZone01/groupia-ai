import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { CampaignPage } from '../../api/generated/models';
import { GroupiaApi } from '../../api/groupia-api';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { GroupagesPage } from './groupages.page';

describe('GroupagesPage V1 — contexte boutique et pagination', () => {
  const selectedId = signal<string | null>('boutique-a');
  const pending: {
    org: string;
    page: number;
    resolve: (result: CampaignPage) => void;
    reject: (error: Error) => void;
  }[] = [];
  let fixture: ComponentFixture<GroupagesPage>;
  let component: GroupagesPage;
  const api = {
    campaigns: vi.fn((org: string, page: number) =>
      new Promise<CampaignPage>((resolve, reject) => {
        pending.push({ org, page, resolve, reject });
      }),
    ),
  };

  beforeEach(() => {
    selectedId.set('boutique-a');
    pending.length = 0;
    api.campaigns.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: GroupiaApi, useValue: api },
        { provide: BoutiqueContextService, useValue: { selectedId } },
      ],
    });
    fixture = TestBed.createComponent(GroupagesPage);
    component = fixture.componentInstance;
    fixture.detectChanges();
    TestBed.tick();
  });

  afterEach(() => fixture.destroy());

  it('présente les statuts sans inventer de lien public', () => {
    expect(component.statusHint({ status: 'DRAFT' })).toContain('non publié');
    expect(component.statusHint({ status: 'OPEN' })).toBe('Commandes ouvertes');
    expect(component.statusHint({ status: 'OPEN', publicIdentifier: 'un-lien' })).toContain('lien disponible');
    expect(component.statusHint({ status: 'IN_TRANSIT' })).toBe('Transport en cours');
    expect(component.statusTone({ status: 'OPEN' })).toBe('positive');
    expect(component.statusTone({ status: 'DRAFT' })).toBe('warning');
    expect(component.statusTone({ status: 'IN_TRANSIT' })).toBe('info');
    expect(component.closing('invalid')).toBe('');
  });

  it('charge seulement une page de 20 groupages et conserve la pagination', async () => {
    expect(pending[0]).toMatchObject({ org: 'boutique-a', page: 0 });
    pending[0].resolve({
      page: 0, totalElements: 28, totalPages: 2,
      items: [{ id: 'g1', title: 'Sélection A', status: 'DRAFT' }],
    });
    await Promise.resolve();
    fixture.detectChanges();
    expect(component.data()?.totalElements).toBe(28);
    const next = component.load(1);
    expect(pending[1]).toMatchObject({ org: 'boutique-a', page: 1 });
    pending[1].resolve({ page: 1, totalPages: 2, totalElements: 28, items: [] });
    await next;
    fixture.detectChanges();
    expect(component.data()?.page).toBe(1);
    component.load();
    expect(pending[2]).toMatchObject({ org: 'boutique-a', page: 1 });
    pending[2].resolve({ page: 1, totalPages: 2, totalElements: 28, items: [] });
    await Promise.resolve();
  });

  it('ignore les réponses anciennes lors du changement de boutique', async () => {
    selectedId.set('boutique-b');
    TestBed.tick();
    expect(component.data()).toBeNull();
    expect(pending[1]).toMatchObject({ org: 'boutique-b', page: 0 });
    pending[0].resolve({ page: 0, totalElements: 1, items: [{ id: 'secret', title: 'Ancienne boutique' }] });
    await Promise.resolve();
    expect(component.data()).toBeNull();
    pending[1].resolve({ page: 0, totalElements: 1, items: [{ id: 'new', title: 'Nouvelle boutique' }] });
    await Promise.resolve();
    expect(component.data()?.items?.[0]?.id).toBe('new');
  });

  it('efface tout quand la boutique sélectionnée disparaît', async () => {
    pending[0].resolve({ totalElements: 1, items: [{ id: 'a' }] });
    await Promise.resolve();
    expect(component.data()?.items).toHaveLength(1);
    selectedId.set(null);
    TestBed.tick();
    expect(component.data()).toBeNull();
    expect(component.error()).toBe('');
    expect(component.loading()).toBe(false);
    expect(api.campaigns).toHaveBeenCalledTimes(1);
  });

  it('ne prend pas une erreur réseau pour une liste vide', async () => {
    pending[0].reject(new Error('Network down'));
    await Promise.resolve();
    fixture.detectChanges();
    expect(component.data()).toBeNull();
    expect(component.error()).toBeTruthy();
    const retry = component.load();
    pending[1].resolve({ page: 0, totalElements: 0, items: [] });
    await retry;
    expect(component.error()).toBe('');
    expect(component.data()?.totalElements).toBe(0);
  });
});
