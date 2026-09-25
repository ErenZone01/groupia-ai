import { DOCUMENT } from '@angular/common';
import { computed, inject, Injectable, signal } from '@angular/core';
interface Session {
  accessToken: string;
  expiresAt: number;
}
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly window = inject(DOCUMENT).defaultView;
  private readonly session = signal<Session | null>(this.restore());
  readonly signedIn = computed(() => this.session() !== null);
  acceptSession(accessToken: string, expiresIn: number) {
    if (!accessToken || !Number.isFinite(expiresIn) || expiresIn <= 0) {
      this.clear();
      return;
    }
    const value = { accessToken, expiresAt: Date.now() + expiresIn * 1000 };
    this.session.set(value);
    try {
      this.window?.sessionStorage.setItem('groupia.session', JSON.stringify(value));
    } catch {}
  }
  token(): string | null {
    const v = this.session();
    if (!v || v.expiresAt <= Date.now()) {
      this.clear();
      return null;
    }
    return v.accessToken;
  }
  clear() {
    this.session.set(null);
    try {
      this.window?.sessionStorage.removeItem('groupia.session');
    } catch {}
  }
  private restore(): Session | null {
    try {
      const v = JSON.parse(this.window?.sessionStorage.getItem('groupia.session') ?? 'null');
      if (
        v &&
        typeof v.accessToken === 'string' &&
        v.accessToken &&
        Number.isFinite(v.expiresAt) &&
        v.expiresAt > Date.now()
      )
        return v;
      this.window?.sessionStorage.removeItem('groupia.session');
    } catch {}
    return null;
  }
}
