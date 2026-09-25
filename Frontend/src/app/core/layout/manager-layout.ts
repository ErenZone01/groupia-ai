import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Sidebar } from './sidebar';
import { Topbar } from './topbar';
import { MobileBottomNav } from './mobile-bottom-nav';
import { BoutiqueGate } from './boutique-gate';
@Component({
  selector: 'g-manager-layout',
  imports: [RouterOutlet, Sidebar, Topbar, MobileBottomNav, BoutiqueGate],
  template: `<a class="skip" href="#main-content">Aller au contenu</a><g-sidebar />
    <div class="workspace">
      <g-topbar />
      <main id="main-content" tabindex="-1">
        <g-boutique-gate><router-outlet /></g-boutique-gate>
      </main>
      <footer>Groupia<span>Les groupages qui rapprochent les opportunités.</span></footer>
    </div>
    <g-mobile-bottom-nav />`,
  styles: `
    .workspace {
      margin-left: var(--sidebar-width);
    }
    main {
      padding: var(--s8);
      max-width: 1380px;
      margin: auto;
      min-height: calc(100dvh - 150px);
      outline: none;
    }
    .preview {
      display: flex;
      align-items: center;
      gap: var(--s2);
      font-size: var(--text-xs);
      color: var(--muted);
      margin-bottom: var(--s5);
    }
    .dot {
      width: 6px;
      height: 6px;
      background: var(--accent);
      border-radius: var(--pill);
    }
    footer {
      max-width: 1380px;
      margin: auto;
      padding: var(--s4) var(--s8) var(--s6);
      display: flex;
      justify-content: space-between;
      gap: var(--s3);
      color: var(--muted);
      font-size: var(--text-xs);
    }
    .skip {
      position: fixed;
      top: var(--s3);
      left: var(--s3);
      transform: translateY(-150%);
      z-index: 100;
      padding: var(--s3);
      background: var(--surface);
      color: var(--text);
      border-radius: var(--radius-sm);
    }
    .skip:focus {
      transform: translateY(0);
    }
    @media (max-width: 1023px) {
      g-sidebar {
        display: none;
      }
      .workspace {
        margin-left: 0;
        padding-bottom: calc(var(--bottom-nav-height) + env(safe-area-inset-bottom));
      }
      main {
        padding: var(--s6) var(--s5);
      }
      footer {
        padding: var(--s4) var(--s5);
        font-size: var(--text-xs);
      }
      footer span {
        max-width: 25ch;
        text-align: right;
      }
    }
  `,
})
export class ManagerLayout {}
