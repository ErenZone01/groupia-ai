import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RecentOrders } from './recent-orders';

describe('Commandes récentes du tableau de bord', () => {
  function setup() {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    return TestBed.createComponent(RecentOrders);
  }
  it('conserve les montants, statuts, ordre et liens reçus du backend', () => {
    const fixture = setup();
    fixture.componentRef.setInput('orders', [
      {
        id: 'first',
        orderNumber: 'GRP-002',
        initialAmountDue: 12000,
        currency: 'XOF',
        status: 'PENDING_PAYMENT',
        placedAt: '2026-10-20T10:00:00Z',
      },
      {
        id: 'second',
        orderNumber: 'GRP-001',
        initialAmountDue: 5000,
        currency: 'XOF',
        status: 'CONFIRMED',
      },
    ]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(Array.from(el.querySelectorAll('.reference strong')).map((n) => n.textContent)).toEqual([
      'GRP-002',
      'GRP-001',
    ]);
    expect(Array.from(el.querySelectorAll('g-badge')).map((n) => n.textContent)).toEqual([
      'En attente',
      'Payée',
    ]);
    expect(el.querySelector('.summary strong')?.textContent?.replace(/\s/g, '')).toBe(
      '12000F CFA'.replace(/\s/g, ''),
    );
    expect(el.querySelector('a')?.getAttribute('href')).toBe('/app/orders/first');
    expect(el.querySelector('a')?.getAttribute('aria-label')).toBe('Voir la commande GRP-002');
  });
  it('ne fabrique ni montant ni lien lorsque les données sont absentes', () => {
    const fixture = setup();
    fixture.componentRef.setInput('orders', [{}]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.summary strong')?.textContent).toBe('—');
    expect(el.textContent).toContain('Statut indisponible');
    expect(el.querySelector('a')).toBeNull();
  });
});
