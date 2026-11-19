import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SaspayIncidentConsoleApi } from './saspay-incidents.api';
import { SaspayIncidentAccess } from '../../core/auth/saspay-incident-access';

describe('SaspayIncidentConsoleApi', () => {
  let api: SaspayIncidentConsoleApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(SaspayIncidentConsoleApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('uses bounded admin listing parameters and no finance POST', async () => {
    const result = api.list('OPEN', 'WEBHOOK_STUCK', 2);
    const request = http.expectOne(
      (req) =>
        req.url === '/api/platform/saspay/incidents' &&
        req.params.get('status') === 'OPEN' &&
        req.params.get('code') === 'WEBHOOK_STUCK' &&
        req.params.get('page') === '2' &&
        req.params.get('size') === '20',
    );
    expect(request.request.method).toBe('GET');
    request.flush({
      items: [], total: 0, page: 2, size: 20,
      open: 0, inReview: 0, closed: 0, checkpoint: null,
    });
    expect((await result).items).toEqual([]);
  });

  it('requires an explicit acknowledgment and sends only case decision fields', async () => {
    const submitted = api.decide(
      'd799c643-2e6f-437d-96e2-5f663893a10a',
      'REVIEW_STARTED',
      'DOSSIER-2026-001',
      'Revue administrative indépendante des états financiers.',
      5,
    );
    const request = http.expectOne((req) => req.url.endsWith('/decisions'));
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      action: 'REVIEW_STARTED',
      evidenceReference: 'DOSSIER-2026-001',
      reason: 'Revue administrative indépendante des états financiers.',
      expectedOccurrenceCount: 5,
      confirmed: true,
    });
    request.flush({
      incidentId: 'd799c643-2e6f-437d-96e2-5f663893a10a',
      decisionId: '058a6a9f-99eb-4b1b-8940-0e2549e9c03c',
      status: 'IN_REVIEW',
    });
    expect((await submitted).status).toBe('IN_REVIEW');
  });
});

describe('SaspayIncidentAccess', () => {
  let access: SaspayIncidentAccess;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    access = TestBed.inject(SaspayIncidentAccess);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('is hidden by default and allows only server-declared access', async () => {
    expect(access.allowed()).toBe(false);
    const pending = access.refresh();
    const request = http.expectOne('/api/platform/saspay/incidents/access');
    expect(request.request.method).toBe('GET');
    request.flush({ allowed: true });
    await pending;
    expect(access.allowed()).toBe(true);
    access.reset();
    expect(access.allowed()).toBe(false);
  });

  it('does not disclose the admin navigation after authorization failure', async () => {
    const pending = access.refresh();
    http.expectOne('/api/platform/saspay/incidents/access').flush(
      { code: 'FORBIDDEN' },
      { status: 403, statusText: 'Forbidden' },
    );
    await pending;
    expect(access.allowed()).toBe(false);
  });
});
