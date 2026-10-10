import { DOCUMENT } from '@angular/common';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
export type ThemePreference = 'light' | 'dark' | 'system';
const valid = (v: unknown): v is ThemePreference =>
  ['light', 'dark', 'system'].includes(v as string);
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  private readonly window = this.document.defaultView;
  private readonly media = this.window?.matchMedia?.('(prefers-color-scheme: dark)');
  private readonly choice = signal<ThemePreference>(this.read());
  private readonly systemDark = signal(this.media?.matches ?? false);
  readonly preference = this.choice.asReadonly();
  readonly resolved = computed(() =>
    this.choice() === 'system' ? (this.systemDark() ? 'dark' : 'light') : this.choice(),
  );
  constructor() {
    const change = (e: MediaQueryListEvent) => {
      this.systemDark.set(e.matches);
      this.apply();
    };
    const storage = (e: StorageEvent) => {
      if (e.key === 'groupia.theme' || e.key === null) {
        this.choice.set(this.read());
        this.apply();
      }
    };
    this.media?.addEventListener('change', change);
    this.window?.addEventListener('storage', storage);
    inject(DestroyRef).onDestroy(() => {
      this.media?.removeEventListener('change', change);
      this.window?.removeEventListener('storage', storage);
    });
    this.apply();
  }
  setPreference(v: ThemePreference) {
    if (!valid(v)) return;
    this.choice.set(v);
    try {
      this.window?.localStorage.setItem('groupia.theme', v);
    } catch {
      /* Storage may be unavailable; keep the in-memory state. */
    }
    this.apply();
  }
  private read(): ThemePreference {
    try {
      const v = this.window?.localStorage.getItem('groupia.theme');
      return valid(v) ? v : 'system';
    } catch {
      return 'system';
    }
  }
  private apply() {
    this.document.documentElement.dataset['theme'] = this.resolved();
    this.document.documentElement.style.colorScheme = this.resolved();
  }
}
