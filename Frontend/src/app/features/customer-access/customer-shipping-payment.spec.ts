import { ShippingSettlement } from '../../shared/ui/shipping-settlement';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import {
  CustomerShippingPayment,
  SHIPPING_CHECKOUT_NAVIGATION,
  safeBictorysCheckout,
} from './customer-shipping-payment';
import { CustomerAccessService } from './customer-access.service';
import { ShippingSettlementView } from '../../api/generated/models';
const due: ShippingSettlementView = {
  status: 'BALANCE_DUE',
  finalShippingAmount: 8000,
  initialShippingPaidAmount: 3000,
  additionalShippingPaidAmount: 0,
  totalShippingPaidAmount: 3000,
  amountDue: 5000,
  creditAmount: 0,
  currency: 'XOF',
  currentShippingCalculationId: 'c1',
  revision: 1,
  paymentInProgress: false,
};
describe('Paiement du transport', () => {
  const api = { payShipping: vi.fn(), quoteShipping: vi.fn(), shippingPaymentOptions: vi.fn() };
  const navigate = vi.fn();
  beforeEach(() => {
    sessionStorage.clear();
    api.quoteShipping.mockReset();
    api.shippingPaymentOptions.mockResolvedValue({ availableProviders: ['BICTORYS'] });
    api.payShipping.mockReset();
    navigate.mockReset();
    TestBed.configureTestingModule({
      providers: [
        { provide: CustomerAccessService, useValue: api },
        { provide: SHIPPING_CHECKOUT_NAVIGATION, useValue: navigate },
      ],
    });
  });
  function setup(value: ShippingSettlementView = due) {
    const f = TestBed.createComponent(CustomerShippingPayment);
    f.componentRef.setInput('orderNumber', 'GRP-1');
    f.componentRef.setInput('value', value);
    f.detectChanges();
    return f;
  }
  it('SasPay hébergé : les frais sont gérés par le PSP sans devis opérateur dans Groupia', async () => {
    api.shippingPaymentOptions.mockResolvedValue({ availableProviders: ['SASPAY'] });
    api.payShipping.mockResolvedValue({
      status: 'PENDING', reconciliationRequired: false, checkoutUrl: null,
    });
    const f = setup();
    await vi.waitFor(() => expect(f.componentInstance.providers()).toEqual(['SASPAY']));
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Payer avec SasPay');
    expect(f.nativeElement.textContent).toContain('Des frais supplémentaires peuvent être appliqués');
    expect(f.nativeElement.textContent).toContain('Vérifiez le montant final affiché dans Wave ou Orange Money avant de confirmer.');
    expect(api.quoteShipping).not.toHaveBeenCalled();
    expect(api.payShipping).not.toHaveBeenCalled();
    f.componentInstance.payerEmail.set('transport@example.com');
    await f.componentInstance.pay(undefined, true);
    expect(api.payShipping).toHaveBeenCalledWith(
      'GRP-1', expect.any(String), 'SASPAY', 'transport@example.com',
    );
    expect(f.componentInstance.waiting()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
    expect(f.componentInstance.value()?.status).toBe('BALANCE_DUE');
    f.destroy();
  });
  it('reprend le checkout SasPay autorisé sans nouvelle charge après rechargement', async () => {
    api.shippingPaymentOptions.mockResolvedValue({
      availableProviders: [],
      latestAttempt: {
        provider: 'SASPAY',
        attempt: {
          status: 'PENDING',
          expectedAmount: 5000,
          currency: 'XOF',
          checkoutUrl: 'https://pay.saspay.me/shipping',
        },
        quote: null,
      },
    });
    const f = setup({ ...due, paymentInProgress: true });
    await vi.waitFor(() =>
      expect(f.componentInstance.checkoutUrl()).toBe('https://pay.saspay.me/shipping'),
    );
    expect(f.componentInstance.acceptedQuote()).toBeNull();
    await f.componentInstance.pay();
    expect(api.payShipping).not.toHaveBeenCalled();
    f.destroy();
  });
  for (const [status, label] of [
    ['BALANCE_DUE', 'Reste à payer'],
    ['SETTLED', 'Transport réglé'],
    ['CREDIT_DUE', 'Crédit en votre faveur'],
    ['NOT_FINALIZED', 'Aucun paiement transport'],
  ] as const) {
    it('présente ' + status, () => {
      const f = setup({ ...due, status, creditAmount: 500 });
      expect(f.nativeElement.textContent).toContain(label);
      expect(f.nativeElement.querySelector('button') !== null).toBe(status === 'BALANCE_DUE');
      expect(f.nativeElement.textContent).not.toContain('Remboursement en cours');
      f.destroy();
    });
  }
  it('bloque les paiements simultanés et redirige seulement avec la réponse serveur', async () => {
    let resolve!: (v: unknown) => void;
    api.payShipping.mockImplementation(() => new Promise((r) => (resolve = r)));
    const f = setup();
    const first = f.componentInstance.pay();
    void f.componentInstance.pay();
    expect(api.payShipping).toHaveBeenCalledTimes(1);
    expect(api.payShipping.mock.calls[0][0]).toBe('GRP-1');
    expect(api.payShipping.mock.calls[0][1]).toMatch(/^[0-9a-f-]{36}$/);
    expect(navigate).not.toHaveBeenCalled();
    resolve({ status: 'PENDING', checkoutUrl: 'https://pay.bictorys.com/checkout' });
    await first;
    expect(navigate).toHaveBeenCalledExactlyOnceWith('https://pay.bictorys.com/checkout');
    expect(f.componentInstance.waiting()).toBe(true);
    f.destroy();
  });
  it('conserve la même clé après erreur réseau et ne suppose jamais le succès', async () => {
    api.payShipping.mockRejectedValue(new HttpErrorResponse({ status: 0 }));
    const f = setup();
    await f.componentInstance.pay();
    await f.componentInstance.pay();
    expect(api.payShipping.mock.calls[0][1]).toBe(api.payShipping.mock.calls[1][1]);
    expect(navigate).not.toHaveBeenCalled();
    expect(f.componentInstance.value()?.status).toBe('BALANCE_DUE');
    expect(f.componentInstance.error()).toContain('Connexion');
    f.destroy();
  });
  it('affiche pending sans permettre une nouvelle tentative', async () => {
    const f = setup({ ...due, paymentInProgress: true });
    expect(f.nativeElement.textContent).toContain('Paiement en cours de confirmation');
    await f.componentInstance.pay();
    expect(api.payShipping).not.toHaveBeenCalled();
    f.destroy();
  });
  it('ne redirige pas une tentative incertaine même avec URL', async () => {
    api.payShipping.mockResolvedValue({
      status: 'PENDING',
      reconciliationRequired: true,
      checkoutUrl: 'https://pay.bictorys.com/checkout',
    });
    const f = setup();
    await f.componentInstance.pay();
    expect(navigate).not.toHaveBeenCalled();
    expect(f.componentInstance.message()).toContain('Vérification');
    f.destroy();
  });
  it('rafraîchit au retour sans interpréter les paramètres URL puis attend le serveur', async () => {
    const f = setup({ ...due, paymentInProgress: true });
    const refresh = vi.fn();
    f.componentInstance.refresh.subscribe(refresh);
    window.dispatchEvent(new Event('focus'));
    expect(refresh).toHaveBeenCalledOnce();
    expect(f.componentInstance.value()?.status).toBe('BALANCE_DUE');
    f.componentRef.setInput('value', {
      ...due,
      status: 'SETTLED',
      paymentInProgress: false,
      amountDue: 0,
      totalShippingPaidAmount: 8000,
    });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Transport réglé');
    expect(f.componentInstance.checkoutUrl()).toBeNull();
    f.destroy();
  });
  it('réactive le paiement après une tentative terminée confirmée par le serveur', () => {
    const f = setup({ ...due, paymentInProgress: true });
    f.componentRef.setInput('value', { ...due });
    f.detectChanges();
    expect(f.nativeElement.querySelector('button')).not.toBeNull();
    expect(f.componentInstance.waiting()).toBe(false);
    f.destroy();
  });
  it('présente un crédit cliente dans la vue gérante', () => {
    const f = TestBed.createComponent(ShippingSettlement);
    f.componentRef.setInput('value', { ...due, status: 'CREDIT_DUE', creditAmount: 500 });
    f.componentRef.setInput('manager', true);
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Crédit cliente');
    expect(f.nativeElement.textContent).not.toContain('Crédit en votre faveur');
    f.destroy();
  });
  it('refuse les URL non Bictorys ou dangereuses', () => {
    for (const url of [
      'javascript:alert(1)',
      'http://pay.bictorys.com',
      'https://evil.test',
      'https://pay.bictorys.com.evil.test',
      'https://a@pay.bictorys.com',
      'https://pay.bictorys.com:444/',
      'https://pay.bictorys.com/#secret',
    ])
      expect(safeBictorysCheckout(url)).toBeNull();
    expect(safeBictorysCheckout('https://pay.test.bictorys.com/checkout')).toBe(
      'https://pay.test.bictorys.com/checkout',
    );
  });
  it('ne propose pas un montant impossible à présenter exactement', () => {
    const f = setup({ ...due, amountDue: Number.MAX_SAFE_INTEGER + 1 });
    expect(f.nativeElement.querySelector('button').disabled).toBe(true);
    f.destroy();
  });
});
