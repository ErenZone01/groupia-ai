import { DOCUMENT } from '@angular/common';
import { computed, effect, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { OrganizationControllerService } from '../../api/generated/services/organization-controller.service';
import { OrganizationView } from '../../api/generated/models';
import { AuthService } from '../auth/auth.service';
import { apiMessage } from '../http/api-error';
@Injectable({ providedIn: 'root' })
export class BoutiqueContextService {
  private readonly api = inject(OrganizationControllerService);
  private readonly auth = inject(AuthService);
  private readonly window = inject(DOCUMENT).defaultView;
  readonly boutiques = signal<OrganizationView[]>([]);
  readonly selectedId = signal<string | null>(null);
  readonly selected = computed(
    () => this.boutiques().find((b) => b.id === this.selectedId()) ?? null,
  );
  readonly loading = signal(false);
  readonly error = signal('');
  readonly ready = signal(false);
  private owner = -1;
  private pending?: Promise<void>;
  constructor() {
    effect(() => {
      const generation = this.auth.generation();
      if (generation !== this.owner) {
        this.boutiques.set([]);
        this.selectedId.set(null);
        this.ready.set(false);
        this.error.set('');
      }
    });
  }
  async ensure() {
    const generation = this.auth.generation();
    if (this.owner === generation) {
      if (this.pending) return this.pending;
      if (this.ready()) return;
    }
    if (this.owner !== generation) {
      this.boutiques.set([]);
      this.selectedId.set(null);
      this.ready.set(false);
    }
    this.owner = generation;
    this.loading.set(true);
    this.error.set('');
    const run = this.load(generation);
    this.pending = run;
    try {
      await run;
    } finally {
      if (this.owner === generation) {
        this.pending = undefined;
        this.loading.set(false);
      }
    }
  }
  private async load(generation: number) {
    try {
      const result: OrganizationView[] = [];
      for (let page = 0; ; page++) {
        const items = await firstValueFrom(this.api.list({ page, size: 100 }));
        if (generation !== this.auth.generation()) return;
        result.push(...items);
        if (items.length < 100) break;
      }
      this.boutiques.set(result);
      let saved: string | null = null;
      try {
        saved = this.window?.localStorage.getItem('groupia.boutique') ?? null;
      } catch {
        /* Storage may be unavailable; keep the in-memory state. */
      }
      this.select(result.find((b) => b.id === saved)?.id ?? result[0]?.id ?? null);
      this.ready.set(true);
    } catch (error) {
      if (generation === this.auth.generation()) this.error.set(apiMessage(error));
    }
  }
  select(id: string | null) {
    const valid = this.boutiques().some((b) => b.id === id) ? id : null;
    this.selectedId.set(valid);
    try {
      if (valid) this.window?.localStorage.setItem('groupia.boutique', valid);
      else this.window?.localStorage.removeItem('groupia.boutique');
    } catch {
      /* Storage may be unavailable; keep the in-memory state. */
    }
  }
  async create(name: string) {
    const generation = this.auth.generation();
    const result = await firstValueFrom(this.api.create({ body: { name } }));
    if (generation !== this.auth.generation()) return;
    this.boutiques.update((items) => [...items, result]);
    this.select(result.id ?? null);
    this.ready.set(true);
  }
}
