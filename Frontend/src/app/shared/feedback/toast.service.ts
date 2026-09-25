import { Injectable, signal } from '@angular/core';
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly message = signal('');
  readonly tone = signal<'success' | 'error'>('success');
  private timer?: ReturnType<typeof setTimeout>;
  show(message: string, tone: 'success' | 'error' = 'success') {
    clearTimeout(this.timer);
    this.tone.set(tone);
    this.message.set(message);
    this.timer = setTimeout(() => this.message.set(''), 7000);
  }
  dismiss() {
    clearTimeout(this.timer);
    this.message.set('');
  }
}
