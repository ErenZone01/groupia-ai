import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { Button } from './button';
import { IconButton } from './icon-button';
import { Card } from './card';
import { Badge } from './badge';
import { Avatar } from './avatar';
import { StatCard } from './stat-card';
import { Tabs } from './tabs';
import { Input } from '../forms/input';
import { Select } from '../forms/select';
import { Toggle } from '../forms/toggle';
import { SearchInput } from '../forms/search-input';
import { Skeleton } from '../feedback/skeleton';
import { EmptyState } from '../feedback/empty-state';
@Component({
  imports: [
    Button,
    IconButton,
    Card,
    Badge,
    Avatar,
    StatCard,
    Tabs,
    Input,
    Select,
    Toggle,
    SearchInput,
    Skeleton,
    EmptyState,
    FormsModule,
  ],
  template: `<button
      gButton
      [loading]="loading()"
      [disabled]="disabled()"
      (click)="clicks = clicks + 1"
    >
      Continuer</button
    ><a gButton href="/test" [disabled]="true">Lien indisponible</a
    ><g-icon-button label="Notifications" (pressed)="clicks = clicks + 1" /><g-card
      ><h2>Contenu</h2></g-card
    ><g-badge tone="positive">Payée</g-badge><g-avatar name="Awa Diop" /><g-stat-card
      label="Commandes"
      [value]="38"
    /><label>Nom<input gInput [(ngModel)]="name" /></label
    ><label
      >Choix<select gSelect [(ngModel)]="choice">
        <option value="one">Un</option>
        <option value="two">Deux</option>
      </select></label
    ><g-toggle label="Notifications actives" [(checked)]="enabled" /><g-search-input
      [(value)]="query"
    /><g-tabs id="test-tabs" [tabs]="tabs" [(active)]="active"
      ><p>{{ active() }}</p></g-tabs
    ><g-skeleton /><g-empty-state title="Aucun résultat" description="Réessayez"
      ><span>Action</span></g-empty-state
    >`,
})
class Host {
  loading = signal(false);
  disabled = signal(false);
  clicks = 0;
  name = '';
  choice = 'one';
  enabled = signal(false);
  query = signal('');
  active = signal('one');
  tabs = [
    { id: 'one', label: 'Un' },
    { id: 'disabled', label: 'Indisponible', disabled: true },
    { id: 'two', label: 'Deux' },
  ];
}
describe('Composants fondamentaux', () => {
  const setup = () => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return { fixture, host: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  };
  it('Button déclenche l’action', () => {
    const { host, el } = setup();
    el.querySelector<HTMLButtonElement>('button')!.click();
    expect(host.clicks).toBe(1);
  });
  it('Button désactivé bloque le clic', () => {
    const { fixture, host, el } = setup();
    host.disabled.set(true);
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('button')!.click();
    expect(host.clicks).toBe(0);
  });
  it('Button loading indique busy et bloque le clic', () => {
    const { fixture, host, el } = setup();
    host.loading.set(true);
    fixture.detectChanges();
    const button = el.querySelector<HTMLButtonElement>('button')!;
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.disabled).toBe(true);
  });
  it('un lien désactivé ne navigue pas', () => {
    const { el } = setup();
    const event = new MouseEvent('click', { bubbles: true, cancelable: true });
    expect(el.querySelector('a')!.dispatchEvent(event)).toBe(false);
    expect(el.querySelector('a')!.tabIndex).toBe(-1);
  });
  it('IconButton a un nom accessible et émet une action', () => {
    const { host, el } = setup();
    const button = el.querySelector<HTMLButtonElement>('g-icon-button button')!;
    expect(button.getAttribute('aria-label')).toBe('Notifications');
    button.click();
    expect(host.clicks).toBe(1);
  });
  it('Card projette son contenu', () =>
    expect(setup().el.querySelector('g-card h2')?.textContent).toBe('Contenu'));
  it('Badge expose son statut en texte', () => {
    const badge = setup().el.querySelector('g-badge')!;
    expect(badge.textContent).toBe('Payée');
    expect(badge.getAttribute('data-tone')).toBe('positive');
  });
  it('Avatar calcule des initiales avec un nom accessible', () => {
    const avatar = setup().el.querySelector('g-avatar span')!;
    expect(avatar.textContent).toBe('AD');
    expect(avatar.getAttribute('aria-label')).toBe('Awa Diop');
  });
  it('StatCard affiche le libellé et la valeur', () => {
    const stat = setup().el.querySelector('g-stat-card')!;
    expect(stat.textContent).toContain('38');
    expect(stat.textContent).toContain('Commandes');
  });
  it('Input conserve le binding Angular Forms natif', async () => {
    const { fixture, host, el } = setup();
    await fixture.whenStable();
    const input = el.querySelector<HTMLInputElement>('input[gInput]')!;
    input.value = 'Boutique';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(host.name).toBe('Boutique');
  });
  it('Select conserve le binding Angular Forms natif', async () => {
    const { fixture, host, el } = setup();
    await fixture.whenStable();
    const select = el.querySelector<HTMLSelectElement>('select')!;
    select.value = 'two';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(host.choice).toBe('two');
  });
  it('Toggle publie son état accessible', () => {
    const { fixture, host, el } = setup();
    const toggle = el.querySelector<HTMLButtonElement>('[role=switch]')!;
    toggle.click();
    fixture.detectChanges();
    expect(host.enabled()).toBe(true);
    expect(toggle.getAttribute('aria-checked')).toBe('true');
  });
  it('SearchInput transmet la saisie', () => {
    const { host, el } = setup();
    const input = el.querySelector<HTMLInputElement>('input[type=search]')!;
    input.value = 'chemise';
    input.dispatchEvent(new Event('input'));
    expect(host.query()).toBe('chemise');
  });
  it('Tabs navigue au clavier en sautant les onglets désactivés', () => {
    const { fixture, host, el } = setup();
    const first = el.querySelector<HTMLButtonElement>('[role=tab]')!;
    first.focus();
    first.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    fixture.detectChanges();
    expect(host.active()).toBe('two');
    expect(document.activeElement?.id).toBe('test-tabs-two');
    expect(el.querySelector('[role=tabpanel]')!.getAttribute('aria-labelledby')).toBe(
      'test-tabs-two',
    );
  });
  it('Tabs gère Home et End', () => {
    const { host, el } = setup();
    const first = el.querySelector<HTMLButtonElement>('[role=tab]')!;
    first.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    expect(host.active()).toBe('two');
    first.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    expect(host.active()).toBe('one');
  });
  it('Skeleton annonce le chargement', () =>
    expect(setup().el.querySelector('g-skeleton [role=status]')?.textContent).toContain(
      'Chargement',
    ));
  it('EmptyState affiche titre et action projetée', () => {
    const empty = setup().el.querySelector('g-empty-state')!;
    expect(empty.textContent).toContain('Aucun résultat');
    expect(empty.textContent).toContain('Action');
  });
});
