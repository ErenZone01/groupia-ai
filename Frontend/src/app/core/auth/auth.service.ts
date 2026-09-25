import { DOCUMENT } from '@angular/common';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthControllerService } from '../../api/generated/services/auth-controller.service';
import { LoginRequest, RegisterRequest } from '../../api/generated/models';
interface Session {
  accessToken: string;
  expiresAt: number;
}
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly window = inject(DOCUMENT).defaultView;
  private readonly api = inject(AuthControllerService);
  private readonly router = inject(Router);
  private readonly session = signal<Session | null>(this.restore());
  private timer?: ReturnType<typeof setTimeout>;
  readonly signedIn = computed(() => this.session() !== null);
  readonly generation = signal(0);
  constructor() {
    this.scheduleExpiry();
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }
  async login(body: LoginRequest) {
    const response = await firstValueFrom(this.api.login({ body }));
    if (!response.accessToken || response.tokenType !== 'Bearer' || !response.expiresIn)
      throw new Error('Invalid session response');
    this.acceptSession(response.accessToken, response.expiresIn);
  }
  async register(body: RegisterRequest) {
    return firstValueFrom(this.api.register({ body }));
  }
  acceptSession(accessToken: string, expiresIn: number) {
    if (!accessToken || !Number.isFinite(expiresIn) || expiresIn <= 0) {
      this.clear();
      return;
    }
    const value = { accessToken, expiresAt: Date.now() + expiresIn * 1000 };
    this.session.set(value);
    this.generation.update((n) => n + 1);
    try {
      this.window?.sessionStorage.setItem('groupia.session', JSON.stringify(value));
    } catch {}
    this.scheduleExpiry();
  }
  token(): string | null {
    const value = this.session();
    if (!value || value.expiresAt <= Date.now()) {
      if (value) this.expire();
      return null;
    }
    return value.accessToken;
  }
  clear() {
    clearTimeout(this.timer);
    this.session.set(null);
    this.generation.update((n) => n + 1);
    try {
      this.window?.sessionStorage.removeItem('groupia.session');
    } catch {}
  }
  logout() {
    this.clear();
    void this.router.navigateByUrl('/connexion');
  }
  expire() {
    this.clear();
    if (this.router.url.startsWith('/app/'))
      void this.router.navigate(['/connexion'], {
        queryParams: { expired: '1', returnUrl: this.router.url },
      });
  }
  private scheduleExpiry() {
    clearTimeout(this.timer);
    const value = this.session();
    if (!value) return;
    this.timer = setTimeout(
      () => {
        if (value.expiresAt <= Date.now()) this.expire();
        else this.scheduleExpiry();
      },
      Math.min(Math.max(value.expiresAt - Date.now(), 0), 2147483647),
    );
  }
  private restore(): Session | null {
    try {
      const value = JSON.parse(this.window?.sessionStorage.getItem('groupia.session') ?? 'null');
      if (
        value &&
        typeof value.accessToken === 'string' &&
        value.accessToken &&
        Number.isFinite(value.expiresAt) &&
        value.expiresAt > Date.now()
      )
        return value;
      this.window?.sessionStorage.removeItem('groupia.session');
    } catch {}
    return null;
  }
}
