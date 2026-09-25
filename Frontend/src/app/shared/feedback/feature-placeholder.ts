import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Card } from '../ui/card';
import { EmptyState } from './empty-state';
import { Button } from '../ui/button';
@Component({
  selector: 'g-feature-placeholder',
  imports: [RouterLink, Card, EmptyState, Button],
  template: `<div class="page-heading">
      <div>
        <h1>{{ title() }}</h1>
        <p>{{ intro() }}</p>
      </div>
    </div>
    <g-card
      ><g-empty-state [title]="emptyTitle()" [description]="description()" [icon]="icon()"
        ><a gButton variant="secondary" routerLink="/accueil">Retour à l’accueil</a></g-empty-state
      ></g-card
    >`,
})
export class FeaturePlaceholder {
  readonly title = input.required<string>();
  readonly intro = input('');
  readonly emptyTitle = input('Votre espace se prépare');
  readonly description = input('Cette rubrique sera bientôt disponible.');
  readonly icon = input('box');
}
