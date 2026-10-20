import { DOCUMENT } from '@angular/common';
import { DestroyRef, inject, Injectable } from '@angular/core';
export interface MemorySession {
  accessToken: string;
  expiresAt: number;
  sessionId: string;
}
export type SessionMessage =
  { kind: 'session'; session: MemorySession; login?: boolean } | { kind: 'logout' };
@Injectable({ providedIn: 'root' })
export class SessionCoordinator {
  private readonly window = inject(DOCUMENT).defaultView;
  private readonly channel =
    this.window && 'BroadcastChannel' in this.window
      ? new this.window.BroadcastChannel('groupia.auth')
      : null;
  receive: (message: SessionMessage) => void = () => {};
  constructor() {
    if (this.channel)
      this.channel.onmessage = ({ data }: MessageEvent<SessionMessage>) => {
        if (data?.kind === 'logout' || (data?.kind === 'session' && validSession(data.session)))
          this.receive(data);
      };
    inject(DestroyRef).onDestroy(() => this.channel?.close());
  }
  run<T>(operation: () => Promise<T>): Promise<T> {
    const locks = this.window?.navigator.locks;
    if (!locks) return operation();
    return locks.request('groupia.auth', async () => {
      try {
        return await operation();
      } finally {
        // BroadcastChannel delivery is asynchronous. Keep the Web Lock for one short
        // handoff window so a waiting tab can adopt a session broadcast by the current
        // holder before it is allowed to rotate the same refresh token again.
        if (this.channel)
          await new Promise<void>((resolve) => this.window!.setTimeout(resolve, 25));
      }
    });
  }
  send(message: SessionMessage) {
    this.channel?.postMessage(message);
  }
}
export function validSession(value: MemorySession): boolean {
  return (
    !!value &&
    typeof value.accessToken === 'string' &&
    value.accessToken.length > 0 &&
    typeof value.sessionId === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.sessionId) &&
    Number.isFinite(value.expiresAt) &&
    value.expiresAt > Date.now()
  );
}
