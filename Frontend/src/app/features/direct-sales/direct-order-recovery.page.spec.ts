import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { CustomerAccessService } from '../customer-access/customer-access.service';
import { DirectOrderRecoveryPage } from './direct-order-recovery.page';

const shop = '11111111-1111-4111-8111-111111111111';
const own = 'DIR-000000000001';
describe('Vente directe : retrouver une commande par OTP', () => {
  let access: {
    session: ReturnType<typeof vi.fn>;
    request: ReturnType<typeof vi.fn>;
    verify: ReturnType<typeof vi.fn>;
    directOrders: ReturnType<typeof vi.fn>;
    rotateDirectLink: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    access = {
      session: vi.fn(async () => ({ scope: 'PHONE_VERIFIED' })),
      request: vi.fn(async () => ({
        challengeId: '11111111-1111-4111-8111-111111111111',
        message: 'Si ce numéro est joignable, un code a été envoyé.',
      })),
      verify: vi.fn(async () => ({ scope: 'PHONE_VERIFIED' })),
      directOrders: vi.fn(async () => ({ items: [{
        orderNumber: own, publicIdentifier: shop, status: 'PENDING_PAYMENT_INTEGRATION',
        fulfillmentMethod: 'PICKUP', currency: 'XOF',
        acceptedTotalXof: 9300, placedAt: '2026-11-02T14:00:00Z',
      }], total: 1, page: 0, size: 20 })),
      rotateDirectLink: vi.fn(async () => ({
        orderNumber: own, publicIdentifier: shop, token: 'A'.repeat(43),
      })),
      logout: vi.fn(async () => undefined),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: CustomerAccessService, useValue: access },
        { provide: ActivatedRoute, useValue: {
          snapshot: { paramMap: convertToParamMap({ publicIdentifier: shop }) },
        } },
      ],
    });
  });

  it('n’affiche aucun secret ni commande avant vérification OTP', async () => {
    access.session.mockRejectedValue(new Error('no session'));
    const page = TestBed.runInInjectionContext(() => new DirectOrderRecoveryPage());
    await vi.waitFor(() => expect(page.loading()).toBe(false));
    expect(page.verified()).toBe(false);
    expect(page.orders()).toBeNull();
    expect(access.directOrders).not.toHaveBeenCalled();
    page.phone = '+221771234567';
    await page.requestCode();
    page.code = '123456';
    await page.verifyCode();
    expect(access.request).toHaveBeenCalledWith('+221771234567');
    expect(access.verify).toHaveBeenCalledWith(
      '11111111-1111-4111-8111-111111111111', '123456');
    expect(page.orders()?.total).toBe(1);
    expect(page.verified()).toBe(true);
  });

  it('refuse une session dédiée à un seul lien de groupage', async () => {
    access.session.mockResolvedValue({ scope: 'ORDER_ACCESS' });
    const page = TestBed.runInInjectionContext(() => new DirectOrderRecoveryPage());
    await vi.waitFor(() => expect(page.loading()).toBe(false));
    expect(page.verified()).toBe(false);
    expect(access.directOrders).not.toHaveBeenCalled();
  });

  it('remplace le lien uniquement pour la commande sélectionnée', async () => {
    const page = TestBed.runInInjectionContext(() => new DirectOrderRecoveryPage());
    await vi.waitFor(() => expect(page.orders()?.total).toBe(1));
    await page.rotate(own);
    expect(access.rotateDirectLink).toHaveBeenCalledWith(own);
    expect(page.newLink()?.token).toBe('A'.repeat(43));
    await page.logout();
    expect(page.newLink()).toBeNull();
    expect(page.orders()).toBeNull();
    expect(page.verified()).toBe(false);
  });
});
