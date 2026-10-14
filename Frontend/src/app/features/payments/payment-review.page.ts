import { Component, effect, ElementRef, inject, signal, untracked, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Button } from '../../shared/ui/button';
import { Badge } from '../../shared/ui/badge';
import { PaymentsStore } from './payments.store';
import { money, paymentDate, paymentLabel } from './payment-display';
@Component({
  selector: 'g-payment-review-page',
  providers: [PaymentsStore],
  imports: [RouterLink, FormsModule, Button, Badge],
  templateUrl: './payment-review.page.html',
  styleUrl: './payments.scss',
})
export class PaymentReviewPage {
  readonly store = inject(PaymentsStore);
  private readonly params = toSignal(inject(ActivatedRoute).paramMap);
  readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('confirmation');
  readonly heading = viewChild<ElementRef<HTMLElement>>('pageHeading');
  readonly reference = signal('');
  readonly note = signal('');
  readonly validation = signal('');
  readonly money = money;
  readonly date = paymentDate;
  readonly label = paymentLabel;
  constructor() {
    effect(() => {
      const id = this.params()?.get('reviewId');
      if (id) untracked(() => this.store.showReview(id));
    });
    effect(() => {
      const state = this.store.vm();
      if (!state.review || state.resolutionState !== 'idle') {
        const dialog = this.dialog()?.nativeElement;
        const wasOpen = dialog?.open;
        dialog?.close();
        if (wasOpen && state.review) this.heading()?.nativeElement.focus();
        this.reference.set('');
        this.note.set('');
        this.validation.set('');
      }
    });
  }
  open() {
    if (this.store.vm().review?.canResolve !== true || this.store.vm().resolutionState !== 'idle')
      return;
    this.reference.set('');
    this.note.set('');
    this.validation.set('');
    this.dialog()?.nativeElement.showModal();
  }
  close(event?: Event) {
    if (this.store.vm().busy) {
      event?.preventDefault();
      return;
    }
    this.dialog()?.nativeElement.close();
  }
  cycleFocus(event: Event) {
    if (!(event instanceof KeyboardEvent) || event.key !== 'Tab') return;
    const dialog = this.dialog()?.nativeElement;
    if (!dialog?.open) return;
    const controls = [
      ...dialog.querySelectorAll<HTMLElement>(
        'input:not(:disabled), textarea:not(:disabled), button:not(:disabled)',
      ),
    ];
    const first = controls.at(0),
      last = controls.at(-1);
    if (!first || !last) {
      event.preventDefault();
      dialog.focus();
      return;
    }
    const active = dialog.ownerDocument.activeElement;
    if (event.shiftKey && (active === first || active === dialog)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (active === last || active === dialog)) {
      event.preventDefault();
      first.focus();
    }
  }
  submit() {
    if (
      !this.reference().trim() ||
      this.reference().trim().length > 160 ||
      this.note().trim().length > 1000
    ) {
      this.validation.set(
        'Indiquez une référence de 1 à 160 caractères et une note de 1 000 caractères maximum.',
      );
      return;
    }
    this.validation.set('');
    this.store.resolve(this.reference(), this.note());
  }
}
