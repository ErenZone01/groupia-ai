import { Component } from '@angular/core';
import { OrdersPanel } from './orders-panel';
@Component({
  selector: 'g-orders-page',
  imports: [OrdersPanel],
  template: `<div class="page-heading">
      <div>
        <p class="eyebrow">Votre boutique</p>
        <h1>Commandes</h1>
        <p>Retrouvez les achats de vos clientes et leur statut.</p>
      </div>
    </div>
    <g-orders-panel />`,
})
export class OrdersPage {}
