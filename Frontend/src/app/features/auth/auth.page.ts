import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Brand } from '../../shared/ui/brand';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { environment } from '../../core/config/environment';
@Component({
  selector: 'g-auth-page',
  imports: [RouterLink, Brand, Button, Input],
  template: `<main>
    <section class="welcome">
      <g-brand />
      <p class="eyebrow">Bienvenue chez vous</p>
      <h1>Votre boutique.<br />Vos groupages.<br /><em>De belles opportunités.</em></h1>
      <p>Un espace simple pour rapprocher vos clientes et faire grandir votre activité.</p>
      <span class="seal">Ensemble, on va plus loin.</span>
    </section>
    <section class="form">
      <div>
        <h2>Ravie de vous retrouver</h2>
        <p class="muted">Connectez-vous à votre espace Groupia.</p>
        <form (submit)="$event.preventDefault()">
          <label for="phone">Numéro de téléphone</label
          ><input
            gInput
            id="phone"
            type="tel"
            autocomplete="username"
            placeholder="+221 77 123 45 67"
          /><label for="password">Mot de passe</label
          ><input
            gInput
            id="password"
            type="password"
            autocomplete="current-password"
            placeholder="Votre mot de passe"
          /><button gButton type="submit" [disabled]="true" aria-describedby="availability">
            Se connecter
          </button>
          <p id="availability" class="muted">La connexion n’est pas encore disponible.</p>
        </form>
        @if (preview) {
          <a gButton variant="secondary" routerLink="/accueil">Ouvrir l’aperçu de développement</a>
        }
      </div>
    </section>
  </main>`,
  styles: `
    main {
      min-height: 100dvh;
      display: grid;
      grid-template-columns: 1fr 1fr;
    }
    .welcome {
      background: var(--sidebar);
      color: var(--sidebar-text);
      padding: var(--s12);
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: var(--s6);
    }
    .welcome g-brand {
      margin-bottom: var(--s10);
    }
    .welcome .eyebrow {
      color: var(--sidebar-muted);
    }
    .welcome h1 {
      font-size: clamp(2rem, 4vw, 3.6rem);
      line-height: 1.25;
    }
    .welcome em {
      font-style: normal;
      color: var(--accent);
    }
    .welcome > p:not(.eyebrow) {
      color: var(--sidebar-muted);
      max-width: 40ch;
    }
    .seal {
      font-size: var(--text-xs);
      margin-top: var(--s8);
    }
    .form {
      padding: var(--s8);
      display: grid;
      place-items: center;
    }
    .form > div {
      max-width: 360px;
      width: 100%;
    }
    .form h2 {
      font-size: var(--text-xl);
      margin-bottom: var(--s2);
    }
    form {
      display: grid;
      gap: var(--s3);
      margin: var(--s8) 0;
    }
    form label:not(:first-child) {
      margin-top: var(--s3);
    }
    form button {
      margin-top: var(--s4);
    }
    form p {
      font-size: var(--text-xs);
    }
    @media (max-width: 767px) {
      main {
        grid-template-columns: 1fr;
      }
      .welcome {
        padding: var(--s8);
        gap: var(--s3);
      }
      .welcome g-brand {
        margin-bottom: var(--s4);
      }
      .welcome h1 {
        font-size: var(--text-2xl);
      }
      .seal {
        display: none;
      }
      .form {
        padding: var(--s8) var(--s5);
      }
    }
  `,
})
export class AuthPage {
  readonly preview = environment.preview;
}
