import { Component, ElementRef, inject, input, model } from '@angular/core';
export interface TabItem {
  id: string;
  label: string;
  disabled?: boolean;
}
@Component({
  selector: 'g-tabs',
  template: `<div role="tablist" [attr.aria-label]="label()">
      @for (tab of tabs(); track tab.id) {
        <button
          type="button"
          role="tab"
          [id]="id() + '-' + tab.id"
          [attr.aria-controls]="id() + '-panel'"
          [attr.aria-selected]="active() === tab.id"
          [attr.tabindex]="active() === tab.id ? 0 : -1"
          [disabled]="tab.disabled"
          (click)="active.set(tab.id)"
          (keydown)="navigate($event, tab.id)"
        >
          {{ tab.label }}
        </button>
      }
    </div>
    <div
      role="tabpanel"
      [id]="id() + '-panel'"
      [attr.aria-labelledby]="id() + '-' + active()"
      tabindex="0"
    >
      <ng-content />
    </div>`,
  styles: `
    [role='tablist'] {
      display: flex;
      gap: var(--s4);
      overflow-x: auto;
      border-bottom: 1px solid var(--border);
    }
    button {
      flex-shrink: 0;
      padding: var(--s3) var(--s2);
      min-height: 44px;
      border: 0;
      border-bottom: 2px solid transparent;
      background: transparent;
      color: var(--muted);
    }
    button[aria-selected='true'] {
      color: var(--primary);
      border-bottom-color: var(--primary);
      font-weight: 500;
    }
    button:hover:not(:disabled) {
      background: var(--surface-alt);
    }
    @media (max-width: 599px) {
      [role='tablist'] {
        gap: var(--s1);
      }
      button {
        font-size: var(--text-xs);
        padding: var(--s3) var(--s1);
      }
    }
    [role='tabpanel'] {
      padding-top: var(--s4);
    }
  `,
})
export class Tabs {
  readonly id = input.required<string>();
  readonly label = input('Onglets');
  readonly tabs = input.required<readonly TabItem[]>();
  readonly active = model.required<string>();
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  navigate(e: KeyboardEvent, current: string) {
    const tabs = this.tabs().filter((t) => !t.disabled),
      index = tabs.findIndex((t) => t.id === current);
    let next: number;
    if (e.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = tabs.length - 1;
    else return;
    e.preventDefault();
    const tab = tabs[next];
    if (tab) {
      this.active.set(tab.id);
      this.host.nativeElement
        .querySelector<HTMLButtonElement>('[id="' + this.id() + '-' + tab.id + '"]')
        ?.focus();
    }
  }
}
