import { TestBed } from '@angular/core/testing';
import { OrderTimeline } from './order-timeline';

describe('Progression cliente', () => {
  function render(status: string, orderStatus = 'CONFIRMED', fulfillment = 'NOT_READY') {
    const fixture = TestBed.createComponent(OrderTimeline);
    fixture.componentRef.setInput('order', {
      status: orderStatus,
      fulfillmentStatus: fulfillment,
      groupingProgress: {
        status,
        departedAt: '2026-01-01T09:00:00Z',
        arrivedAt: status === 'ARRIVED' ? '2026-01-20T09:00:00Z' : undefined,
      },
    });
    fixture.detectChanges();
    return fixture;
  }
  it.each([
    ['OPEN', 1],
    ['SUPPLIER_ORDERED', 2],
    ['IN_TRANSIT', 3],
    ['ARRIVED', 4],
  ])('état %s : progression réelle sans enum visible', (state, expected) => {
    const f = render(state as string);
    expect(f.componentInstance.stage()).toBe(expected);
    expect(f.nativeElement.querySelectorAll('[aria-current="step"]').length).toBe(1);
    expect(f.nativeElement.textContent).not.toContain(state);
  });
  it('arrivée ne signifie jamais prête à récupérer', () => {
    const f = render('ARRIVED');
    const last = f.nativeElement.querySelector('li:last-child');
    expect(last.textContent).toContain('En attente');
    expect(last.classList.contains('done')).toBe(false);
  });
  it('une commande impayée ne devient pas confirmée par la logistique du groupage', () => {
    const f = render('ARRIVED', 'PENDING_PAYMENT');
    expect(f.componentInstance.stage()).toBe(0);
    expect(f.nativeElement.querySelector('[aria-current]')).toBeNull();
  });
  it('le retrait dépend uniquement du statut de préparation réel', () => {
    expect(render('ARRIVED', 'CONFIRMED', 'READY_FOR_PICKUP').componentInstance.stage()).toBe(5);
  });
  it('groupage annulé ne montre aucune progression active', () => {
    const f = render('CANCELLED');
    expect(f.nativeElement.textContent).toContain('Le groupage a été annulé');
    expect(f.nativeElement.querySelector('[aria-current]')).toBeNull();
  });
});
