import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

/** Menu hint only. Backend independently authorizes every administrative request. */
@Injectable({ providedIn: 'root' })
export class SaspayIncidentAccess {
  private readonly http = inject(HttpClient);
  readonly allowed = signal(false);
  private sequence = 0;

  async refresh(): Promise<void> {
    const sequence = ++this.sequence;
    this.allowed.set(false);
    try {
      const result = await firstValueFrom(
        this.http
          .get<{ allowed: boolean }>('/api/platform/saspay/incidents/access')
          .pipe(timeout(8000)),
      );
      if (sequence === this.sequence) this.allowed.set(result.allowed === true);
    } catch {
      if (sequence === this.sequence) this.allowed.set(false);
    }
  }

  reset() {
    ++this.sequence;
    this.allowed.set(false);
  }
}
