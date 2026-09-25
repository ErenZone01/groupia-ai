import { Component } from '@angular/core';
@Component({
  selector: 'g-campaign-art',
  template: `<svg
    viewBox="0 0 230 180"
    role="img"
    aria-label="Illustration de chemises sur un portant"
  >
    <path class="rail" d="M17 25h196M28 25v138m174-138v138" />
    <path
      class="hanger"
      d="M67 26q0-14 9-7t-3 13l-34 18h66L73 32M117 26q0-14 9-7t-3 13l-34 18h66l-32-18M167 26q0-14 9-7t-3 13l-34 18h66l-32-18"
    />
    <path class="shirt back" d="m147 46-25 13 10 32 11-4v70h63V87l10 4 9-32-24-13-27 10Z" />
    <path class="shirt middle" d="m97 46-25 13 10 32 11-4v70h63V87l10 4 9-32-24-13-27 10Z" />
    <path class="shirt front" d="m47 46-25 13 10 32 11-4v70h63V87l10 4 9-32-24-13-27 10Z" />
    <path class="seam" d="m47 46 12 23 15-13 14 13 13-23M74 57v99M53 89h13v16H53" />
    <path class="seam" d="M78 80h1m-1 18h1m-1 18h1m-1 18h1" />
  </svg>`,
  styles: `
    :host {
      display: block;
      background: var(--surface-alt);
      border-radius: var(--radius-md);
      overflow: hidden;
    }
    svg {
      display: block;
      width: 100%;
      height: 100%;
    }
    .rail,
    .hanger {
      fill: none;
      stroke: var(--muted);
      stroke-width: 2;
    }
    .shirt {
      stroke: var(--border);
      stroke-width: 1;
    }
    .back {
      fill: var(--primary-strong);
    }
    .middle {
      fill: var(--muted);
    }
    .front {
      fill: var(--accent-soft);
    }
    .seam {
      stroke: var(--warning-text);
      stroke-width: 1;
      fill: none;
      opacity: 0.55;
    }
  `,
})
export class CampaignArt {}
