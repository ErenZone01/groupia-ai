import { Component, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * SasPay redirects here on a successful checkout.
 * Never trust that redirect as proof of payment. The destination tracking
 * page independently asks Groupia for the provider-verified status.
 * No order capability token is ever sent to SasPay in return_url.
 */
@Component({
  selector: 'g-saspay-return',
  imports: [RouterLink],
  template: `
    <main class="return-page">
      <h1>Retour du paiement sécurisé</h1>
      <p role="status">
        Nous vérifions votre paiement auprès de SasPay. Un retour sur cette page
        ne signifie pas encore que votre commande est payée.
      </p>
      <p>Si votre commande ne s'ouvre pas automatiquement, utilisez votre lien privé de suivi.</p>
      <a routerLink="/suivi">Retrouver mes commandes</a>
    </main>
  `,
  styles: [`
    .return-page {
      width: min(100% - 32px, 600px);
      margin: clamp(40px, 8vw, 96px) auto;
      padding: clamp(20px, 5vw, 36px);
      border: 1px solid var(--border);
      border-radius: 18px;
      background: var(--surface);
      display: grid;
      gap: 16px;
    }
    h1 { font-size: clamp(22px, 4vw, 32px); }
    p { color: var(--muted); line-height: 1.6; }
    a { font-weight: 700; color: var(--primary); }
  `],
})
export class SaspayReturnPage implements OnInit {
  ngOnInit(): void {
    let value: string | null = null;
    try {
      value = sessionStorage.getItem('groupia.saspay.return');
      sessionStorage.removeItem('groupia.saspay.return');
      if (
        value &&
        value.length <= 2048 &&
        value.startsWith('/') &&
        !value.startsWith('//') &&
        !value.startsWith('/paiement/retour')
      ) {
        const destination = new URL(value, window.location.origin);
        if (destination.origin === window.location.origin) {
          window.location.replace(destination.pathname + destination.search + destination.hash);
        }
      }
    } catch {
      // Browser storage is optional. The public recovery link remains available.
    }
  }
}
