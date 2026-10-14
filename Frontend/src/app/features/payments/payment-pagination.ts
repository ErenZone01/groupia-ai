import { Component, input, output } from '@angular/core';
import { Button } from '../../shared/ui/button';
@Component({
  selector: 'g-payment-pagination',
  imports: [Button],
  styleUrl: './payments.scss',
  template: `<nav [attr.aria-label]="label()" class="pagination">
    <button
      gButton
      variant="secondary"
      [disabled]="loading() || page() === 0"
      (click)="changed.emit(page() - 1)"
    >
      Précédent
    </button>
    <span>Page {{ page() + 1 }} / {{ pages() || 1 }}</span>
    <button
      gButton
      variant="secondary"
      [disabled]="loading() || page() + 1 >= pages()"
      (click)="changed.emit(page() + 1)"
    >
      Suivant
    </button>
  </nav>`,
})
export class PaymentPagination {
  readonly page = input(0);
  readonly pages = input(0);
  readonly loading = input(false);
  readonly label = input('Pagination');
  readonly changed = output<number>();
}
