import { HttpErrorResponse } from '@angular/common/http';
import { accessError } from './access-errors';
describe('Customer access errors', () => {
  for (const status of [0, 400, 401, 403, 404, 429, 500, 503]) {
    it('hides raw provider and server details for ' + status, () => {
      expect(
        accessError(new HttpErrorResponse({ status, error: { detail: 'private-phone-secret' } })),
      ).not.toContain('private-phone-secret');
    });
  }
  it('explains wrong or expired OTP without phone enumeration', () => {
    expect(
      accessError(new HttpErrorResponse({ status: 400, error: { code: 'OTP_INVALID' } })),
    ).toContain('Code incorrect ou expiré');
  });
});
