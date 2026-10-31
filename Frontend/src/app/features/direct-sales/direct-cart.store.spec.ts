import { TestBed } from '@angular/core/testing';
import { DirectCartStore } from './direct-cart.store';

describe('Panier vente directe : séparation des boutiques', () => {
  let cart: DirectCartStore;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [DirectCartStore] });
    cart = TestBed.inject(DirectCartStore);
  });

  it('garde les quantités dans une boutique et les efface en changeant de boutique', () => {
    cart.add('boutique-a', 'offre-1');
    cart.add('boutique-a', 'offre-1');
    expect(cart.count()).toBe(2);
    expect(cart.items()).toEqual([{ offerId: 'offre-1', quantity: 2 }]);
    cart.add('boutique-b', 'offre-2');
    expect(cart.shopId()).toBe('boutique-b');
    expect(cart.items()).toEqual([{ offerId: 'offre-2', quantity: 1 }]);
  });

  it('borne les quantités, empêche les doublons et supprime la ligne à zéro', () => {
    cart.add('a', 'x');
    cart.setQuantity('a', 'x', 200);
    expect(cart.items()).toEqual([{ offerId: 'x', quantity: 100 }]);
    cart.add('a', 'x');
    expect(cart.count()).toBe(100);
    cart.setQuantity('a', 'x', 0);
    expect(cart.items()).toEqual([]);
  });

  it('ne conserve aucun montant ni donnée personnelle dans le panier', () => {
    cart.add('a', 'x');
    expect(cart.items()).toEqual([{ offerId: 'x', quantity: 1 }]);
    cart.clear('a');
    expect(cart.count()).toBe(0);
  });

  it('limite le nombre de variantes distinctes', () => {
    for (let i = 0; i < 30; i++) cart.add('a', 'offre-' + i);
    expect(cart.items()).toHaveLength(20);
  });
});
