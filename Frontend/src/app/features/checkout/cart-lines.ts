import { Component, inject } from '@angular/core';
import { CartStore } from './cart.store';
import { Button } from '../../shared/ui/button';
import { money, variantLabel } from '../../shared/utils/display';

@Component({
  selector: 'g-cart-lines',
  imports: [Button],
  templateUrl: './cart-lines.html',
  styleUrl: './cart-lines.scss',
})
export class CartLines {
  readonly cart = inject(CartStore);
  readonly money = money;
  readonly variantLabel = variantLabel;
}
