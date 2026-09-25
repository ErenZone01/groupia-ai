import { Component, signal } from '@angular/core';
import { Card } from '../../shared/ui/card';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { IconButton } from '../../shared/ui/icon-button';
import { Tabs } from '../../shared/ui/tabs';
import { Toggle } from '../../shared/forms/toggle';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
import { SearchInput } from '../../shared/forms/search-input';
import { Skeleton } from '../../shared/feedback/skeleton';
import { EmptyState } from '../../shared/feedback/empty-state';
@Component({
  selector: 'g-ui-showcase',
  imports: [
    Card,
    Badge,
    Button,
    IconButton,
    Tabs,
    Toggle,
    Input,
    Select,
    SearchInput,
    Skeleton,
    EmptyState,
  ],
  template: `<div class="page-heading">
      <div>
        <h1>Atelier des composants</h1>
        <p>Aperçu de développement · états et interactions</p>
      </div>
    </div>
    <g-card
      ><div class="row">
        <button gButton>Principal</button><button gButton variant="secondary">Secondaire</button
        ><button gButton variant="accent">Accent</button
        ><button gButton [disabled]="true">Indisponible</button
        ><button gButton [loading]="true">Chargement</button
        ><g-icon-button
          label="Exemple de notification"
          icon="bell"
          (pressed)="message.set('Notification de démonstration')"
        />
      </div>
      <p role="status">{{ message() }}</p>
      <div class="row">
        <g-badge tone="positive">Payée</g-badge><g-badge tone="warning">En attente</g-badge
        ><g-badge tone="danger">Expirée</g-badge>
      </div>
      <div class="fields">
        <label>Nom<input gInput placeholder="Ma boutique" /></label
        ><label>Exemple désactivé<input gInput disabled value="Indisponible" /></label
        ><label
          >Choix<select gSelect>
            <option>Première option</option>
            <option>Deuxième option</option>
          </select></label
        ><g-search-input /><label class="row"
          >Notifications<g-toggle label="Notifications" [(checked)]="enabled"
        /></label>
      </div>
      <g-tabs id="showcase" [tabs]="tabs" [(active)]="active"
        ><p>
          {{ active() === 'overview' ? 'Contenu de l’aperçu' : 'Contenu des détails' }}
        </p></g-tabs
      ><g-skeleton /><g-empty-state title="Aucune activité" description="Un exemple d’état vide."
    /></g-card>`,
  styles: `
    .row {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--s3);
      margin-bottom: var(--s5);
    }
    .fields {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: var(--s5);
      margin-bottom: var(--s6);
    }
    label {
      display: grid;
      gap: var(--s2);
    }
    g-skeleton {
      margin-top: var(--s6);
      max-width: 240px;
    }
  `,
})
export class UiShowcasePage {
  readonly enabled = signal(false);
  readonly active = signal('overview');
  readonly message = signal('');
  readonly tabs = [
    { id: 'overview', label: 'Aperçu' },
    { id: 'details', label: 'Détails' },
    { id: 'disabled', label: 'Indisponible', disabled: true },
  ];
}
