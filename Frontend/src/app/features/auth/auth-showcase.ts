import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, inject, input, signal } from '@angular/core';
import { Icon } from '../../shared/ui/icon';

const slides = [
  {
    kicker: 'Groupages simplifiés',
    title: 'Des commandes groupées, plus simples à gérer.',
    description: 'Préparez vos offres, ouvrez votre campagne et suivez les commandes de vos clientes.',
    icon: 'group',
    label: 'Organisez vos groupages',
  },
  {
    kicker: 'Vente directe',
    title: 'Vos produits, en vente directe.',
    description: 'Publiez vos articles et suivez les achats de vos clientes, du règlement à la remise.',
    icon: 'store',
    label: 'Vente directe',
  },
  {
    kicker: 'Un lien public',
    title: 'Partagez un lien. Vos clientes commandent.',
    description: 'La cliente ouvre votre page de groupage, choisit ses produits et commande sans créer de compte.',
    icon: 'link',
    label: 'Commande sans compte',
  },
] as const;

@Component({
  selector: 'g-auth-showcase',
  imports: [Icon],
  templateUrl: './auth-showcase.html',
  styleUrl: './auth-showcase.scss',
})
export class AuthShowcase {
  readonly registerMode = input(false);
  readonly slides = slides;
  readonly active = signal(0);
  private readonly doc = inject(DOCUMENT);
  private readonly win = this.doc.defaultView;
  private paused = false;

  constructor() {
    const timer = this.win?.setInterval(() => {
      if (this.paused || this.doc.hidden || this.win?.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches) return;
      this.next();
    }, 6500);
    inject(DestroyRef).onDestroy(() => {
      if (timer !== undefined) this.win?.clearInterval(timer);
    });
  }

  setPaused(value: boolean) { this.paused = value; }
  goTo(index: number) { this.active.set((index + this.slides.length) % this.slides.length); }
  next() { this.goTo(this.active() + 1); }
  previous() { this.goTo(this.active() - 1); }
}
