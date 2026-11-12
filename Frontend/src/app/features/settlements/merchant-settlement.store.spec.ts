import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import type { MerchantBalance, PayoutDestinationView } from '../../api/generated/models';
import { MerchantSettlementControllerService } from '../../api/generated/services';
import { AuthService } from '../../core/auth/auth.service';
import { MerchantSettlementStore } from './merchant-settlement.store';

const direct = {
  directOrderId: 'direct-order-1',
  orderNumber: 'DIR-000000000001',
  status: 'COMPLETED',
  currency: 'XOF',
  provider: 'SIMULATED',
  accountReference: 'hash',
  grossMerchantCollections: 20000,
  eligibleBalance: 20000,
  merchantPayouts: 0,
  reservedForPayout: 0,
  availableForPayout: 20000,
  deficit: 0,
};

describe('Versements unifiés - vente directe', () => {
  const session = signal(1);
  let api: {
    merchantBalance: ReturnType<typeof vi.fn>;
    merchantPayoutDestination: ReturnType<typeof vi.fn>;
    merchantPayoutHistory: ReturnType<typeof vi.fn>;
  };
  let http: HttpTestingController;
  let store: MerchantSettlementStore;

  beforeEach(() => {
    session.set(1);
    const balance = {
      currency: 'XOF',
      campaigns: [],
      directOrders: [direct],
      canManageSettlements: true,
      canManageDestination: true,
      providerAvailable: true,
    } as unknown as MerchantBalance;
    const destination = {
      id: 'destination-1',
      method: 'WAVE',
      maskedPhone: '+221 ••• •• 4567',
      holderName: 'Vendeuse',
    } as PayoutDestinationView;
    api = {
      merchantBalance: vi.fn(() => of(balance)),
      merchantPayoutDestination: vi.fn(() => of(destination)),
      merchantPayoutHistory: vi.fn(() => of([])),
    };
    TestBed.configureTestingModule({
      providers: [
        MerchantSettlementStore,
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { generation: session } },
        { provide: MerchantSettlementControllerService, useValue: api },
      ],
    });
    store = TestBed.inject(MerchantSettlementStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uses the shared payout destination, idempotency and direct-order endpoint', async () => {
    await store.load('shop-1');
    expect(store.directOrders()).toHaveLength(1);
    const action = store.requestDirect('direct-order-1');
    const pending = http.expectOne(
      '/api/organizations/shop-1/direct-orders/direct-order-1/merchant-payouts',
    );
    expect(pending.request.method).toBe('POST');
    expect(pending.request.body).toEqual({
      destinationId: 'destination-1',
      confirmed: true,
    });
    expect(pending.request.headers.get('Idempotency-Key')).toMatch(
      /^[0-9a-f-]{36}$/i,
    );
    pending.flush({ id: 'payout-1', status: 'PENDING' });
    await action;
    expect(api.merchantBalance).toHaveBeenCalledTimes(2);
    expect(store.error()).toBe('');
  });

  it('does not initiate a payout if no direct funds are withdrawable', async () => {
    await store.load('shop-1');
    store.directOrders.set([{ ...direct, availableForPayout: 0 }]);
    await store.requestDirect('direct-order-1');
    http.expectNone(
      '/api/organizations/shop-1/direct-orders/direct-order-1/merchant-payouts',
    );
  });

  it('does not expose a previous shops direct-sale positions after logout', async () => {
    await store.load('shop-1');
    expect(store.directOrders()).toHaveLength(1);
    store.clear();
    expect(store.directOrders()).toHaveLength(0);
    expect(store.balance()).toBeNull();
  });
});
