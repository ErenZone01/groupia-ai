import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom, timeout } from 'rxjs';
import { AuthControllerService } from '../../api/generated/services/auth-controller.service';
import { AccessToken, LoginRequest, RegisterRequest } from '../../api/generated/models';
import { MemorySession, SessionCoordinator, validSession } from './session-coordinator';
export type AuthState = 'RESTORING' | 'AUTHENTICATED' | 'ANONYMOUS' | 'RESTORATION_UNAVAILABLE';
export const REFRESH_AHEAD_MS = new InjectionToken<number>('refresh ahead', {
  providedIn: 'root',
  factory: () => 60000,
});
const LOGOUT = 'groupia.logout-pending';
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly document = inject(DOCUMENT);
  private readonly window = this.document.defaultView;
  private readonly api = inject(AuthControllerService);
  private readonly router = inject(Router);
  private readonly coordinator = inject(SessionCoordinator);
  private readonly ahead = inject(REFRESH_AHEAD_MS);
  private readonly session = signal<MemorySession | null>(null);
  private timer?: ReturnType<typeof setTimeout>;
  private flight?: Promise<boolean>;
  private epoch = 0;
  private lastActivity = Date.now();
  private retryAt = 0;
  private localLogout = false;
  readonly state = signal<AuthState>('RESTORING');
  readonly problem = signal('');
  readonly hasSession = computed(() => this.session() !== null);
  readonly signedIn = computed(() => this.state() === 'AUTHENTICATED');
  readonly generation = signal(0);
  constructor() {
    try {
      this.window?.sessionStorage.removeItem('groupia.session');
    } catch {
      /* Storage can be denied. */
    }
    this.coordinator.receive = (message) => {
      if (message.kind === 'logout') {
        this.clear();
        this.localLogout = true;
        this.navigateLogin();
      } else {
        if (message.login && !this.persistedLogout()) this.localLogout = false;
        if (this.logoutRequested()) return;
        const current = this.session();
        if (
          current?.sessionId === message.session.sessionId &&
          current.expiresAt >= message.session.expiresAt
        )
          return;
        this.adopt(message.session, false);
      }
    };
    const activity = () => {
      this.lastActivity = Date.now();
      this.checkActive();
    };
    const visible = () => this.checkActive();
    this.document.addEventListener('pointerdown', activity);
    this.document.addEventListener('keydown', activity);
    this.document.addEventListener('visibilitychange', visible);
    this.window?.addEventListener('online', visible);
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.timer);
      this.document.removeEventListener('pointerdown', activity);
      this.document.removeEventListener('keydown', activity);
      this.document.removeEventListener('visibilitychange', visible);
      this.window?.removeEventListener('online', visible);
    });
  }
  async login(body: LoginRequest) {
    const epoch = ++this.epoch;
    await this.coordinator.run(async () => {
      if (epoch !== this.epoch) throw new Error('Connexion interrompue.');
      const response = await firstValueFrom(this.api.login({ body }).pipe(timeout(10000)));
      if (epoch !== this.epoch) throw new Error('Connexion interrompue.');
      const value = this.decode(response);
      this.markLogout(null);
      this.localLogout = false;
      this.adopt(value, true, true);
    });
  }
  register(body: RegisterRequest) {
    return firstValueFrom(this.api.register({ body }).pipe(timeout(10000)));
  }
  // Also used by isolated store tests; production credentials enter through decode().
  acceptSession(accessToken: string, expiresIn: number, sessionId = crypto.randomUUID()) {
    const value = { accessToken, expiresAt: Date.now() + expiresIn * 1000, sessionId };
    if (!validSession(value)) {
      this.clear();
      return;
    }
    this.adopt(value, false);
  }
  token(): string | null {
    const value = this.session();
    return value && value.expiresAt > Date.now() ? value.accessToken : null;
  }
  async restore(): Promise<boolean> {
    if (this.token()) return true;
    if (this.state() === 'ANONYMOUS' && !this.logoutRequested()) return false;
    return this.refresh();
  }
  retryRestoration(): Promise<boolean> {
    if (this.retryAt === Number.POSITIVE_INFINITY) this.retryAt = 0;
    return this.refresh();
  }
  refresh(rejectedToken?: string): Promise<boolean> {
    if (this.flight) return this.flight;
    const epoch = this.epoch,
      original = this.session()?.accessToken;
    const task = this.coordinator.run(async () => {
      if (epoch !== this.epoch) return !this.logoutRequested() && !!this.token();
      if (this.logoutRequested()) {
        await this.finishLogout();
        this.clear();
        return false;
      }
      if (
        this.token() &&
        ((rejectedToken && this.token() !== rejectedToken) ||
          this.session()?.accessToken !== original)
      )
        return true;
      if (Date.now() < this.retryAt) return !!this.token();
      for (let attempt = 0; attempt < 3; attempt++) {
        try {
          const response = await firstValueFrom(this.api.refresh().pipe(timeout(10000)));
          if (epoch !== this.epoch || this.logoutRequested())
            return !this.logoutRequested() && !!this.token();
          this.adopt(this.decode(response), true);
          return true;
        } catch (error) {
          if (epoch !== this.epoch || this.logoutRequested())
            return !this.logoutRequested() && !!this.token();
          if (
            error instanceof HttpErrorResponse &&
            error.status === 409 &&
            error.error?.code === 'AUTH_REFRESH_CONCURRENT'
          ) {
            await new Promise((resolve) => setTimeout(resolve, 250 * (attempt + 1)));
            if (epoch !== this.epoch || this.logoutRequested())
              return !this.logoutRequested() && !!this.token();
            if (this.token() && this.session()?.accessToken !== original) return true;
            continue;
          }
          if (error instanceof HttpErrorResponse && error.status === 401) {
            this.clear();
            this.coordinator.send({ kind: 'logout' });
            this.navigateLogin();
            return false;
          }
          this.problem.set(
            error instanceof HttpErrorResponse && error.status === 403
              ? 'La configuration de sécurité empêche la restauration. Contactez le support.'
              : 'La connexion est temporairement indisponible. Réessayez.',
          );
          if (error instanceof HttpErrorResponse && error.status === 429) {
            const header = error.headers.get('Retry-After') ?? '60';
            const delay = /^\d+$/.test(header)
              ? Number(header) * 1000
              : Date.parse(header) - Date.now();
            this.retryAt = Date.now() + (Number.isFinite(delay) ? Math.max(1000, delay) : 60000);
          } else
            this.retryAt =
              error instanceof HttpErrorResponse && error.status === 403
                ? Number.POSITIVE_INFINITY
                : Date.now() + 5000;
          this.state.set(this.token() ? 'AUTHENTICATED' : 'RESTORATION_UNAVAILABLE');
          this.schedule();
          return !!this.token();
        }
      }
      this.problem.set('Une autre fenêtre renouvelle la session. Réessayez.');
      this.state.set(this.token() ? 'AUTHENTICATED' : 'RESTORATION_UNAVAILABLE');
      return !!this.token();
    });
    this.flight = task.finally(() => {
      this.flight = undefined;
    });
    return this.flight;
  }
  clear() {
    ++this.epoch;
    clearTimeout(this.timer);
    this.session.set(null);
    this.state.set('ANONYMOUS');
    this.generation.update((n) => n + 1);
  }
  async logout() {
    this.markLogout('pending');
    this.localLogout = true;
    this.clear();
    this.coordinator.send({ kind: 'logout' });
    this.navigateLogin();
    await this.coordinator.run(() => this.finishLogout());
  }
  expire() {
    this.clear();
    this.navigateLogin();
  }
  private async finishLogout() {
    try {
      await firstValueFrom(this.api.logout().pipe(timeout(10000)));
      // Keep the non-secret tombstone until explicit login, rejecting late broadcasts.
      this.markLogout('done');
    } catch {
      this.markLogout('pending');
    }
  }
  private decode(response: AccessToken): MemorySession {
    const value = {
      accessToken: response.accessToken ?? '',
      sessionId: response.sessionId ?? '',
      expiresAt: Date.now() + (response.expiresIn ?? 0) * 1000,
    };
    if (response.tokenType !== 'Bearer' || !validSession(value))
      throw new Error('Réponse de session invalide.');
    return value;
  }
  private adopt(value: MemorySession, broadcast: boolean, login = false) {
    if (this.session()?.sessionId !== value.sessionId) {
      ++this.epoch;
      this.generation.update((n) => n + 1);
    }
    this.session.set(value);
    this.state.set('AUTHENTICATED');
    this.problem.set('');
    this.retryAt = 0;
    if (broadcast) this.coordinator.send({ kind: 'session', session: value, login });
    this.schedule();
  }
  private logoutRequested() {
    return this.localLogout || this.persistedLogout();
  }
  private persistedLogout() {
    try {
      return !!this.window?.localStorage.getItem(LOGOUT);
    } catch {
      return false;
    }
  }
  private markLogout(value: string | null) {
    try {
      if (value) this.window?.localStorage.setItem(LOGOUT, value);
      else this.window?.localStorage.removeItem(LOGOUT);
    } catch {
      /* Memory fallback. */
    }
  }
  private navigateLogin() {
    if (this.router.url.startsWith('/app'))
      void this.router.navigate(['/connexion'], { queryParams: { returnUrl: this.router.url } });
  }
  private checkActive() {
    if (
      this.document.visibilityState === 'hidden' ||
      Date.now() - this.lastActivity > 300000 ||
      this.logoutRequested()
    )
      return;
    const session = this.session();
    if (session && session.expiresAt - Date.now() <= this.ahead && Date.now() >= this.retryAt)
      void this.refresh();
  }
  private schedule() {
    clearTimeout(this.timer);
    const value = this.session();
    if (!value || !Number.isFinite(this.retryAt)) return;
    this.timer = setTimeout(
      () => this.checkActive(),
      Math.min(
        2147483647,
        Math.max(1000, value.expiresAt - Date.now() - this.ahead, this.retryAt - Date.now()),
      ),
    );
  }
}
