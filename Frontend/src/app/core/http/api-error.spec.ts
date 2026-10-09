import { HttpErrorResponse } from '@angular/common/http';
import { apiMessage } from './api-error';
describe('Messages API sûrs', () => {
  it('explique le refus métier pour un essai expiré', () =>
    expect(
      apiMessage(
        new HttpErrorResponse({
          status: 403,
          error: { code: 'SUBSCRIPTION_REQUIRED' },
        }),
      ),
    ).toContain('abonnement actif'));
  it('traduit un conflit de version', () =>
    expect(
      apiMessage(
        new HttpErrorResponse({
          status: 409,
          error: { code: 'GROUPING_CAMPAIGN_VERSION_CONFLICT' },
        }),
      ),
    ).toContain('actualisé'));
  it.each([400, 401, 403, 404, 409, 500])('ne révèle aucun détail serveur pour %s', (status) => {
    const text = apiMessage(
      new HttpErrorResponse({
        status,
        error: { code: 'UNKNOWN', detail: 'SQL password secret Java UUID' },
      }),
    );
    expect(text).not.toMatch(/SQL|password|secret|Java|UUID/);
  });
});
