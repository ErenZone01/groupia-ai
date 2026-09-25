import { Component, computed, input } from '@angular/core';
const paths: Record<string, string[]> = {
  home: ['m3 10 9-7 9 7', 'M5 9v12h5v-7h4v7h5V9'],
  bag: ['M5 7h14l2 14H3L5 7Z', 'M9 8V6a3 3 0 0 1 6 0v2'],
  group: ['m3 7 9-4 9 4-9 4-9-4Z', 'm3 12 9 4 9-4', 'm3 17 9 4 9-4'],
  box: ['m3 6 9-4 9 4v12l-9 4-9-4V6Z', 'm3 6 9 4 9-4', 'M12 10v12', 'm7 4 9 4'],
  orders: ['M7 4H5v18h14V4h-2', 'M9 2h6v4H9z', 'M8 11h8M8 16h5'],
  card: ['M3 5h18v14H3z', 'M3 10h18M7 15h3'],
  check: ['m5 12 4 4L19 6'],
  clock: ['M12 8v5l3 2', 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0'],
  close: ['m6 6 12 12M18 6 6 18'],
  arrow: ['M4 12h16m-6-6 6 6-6 6'],
  chevron: ['m9 5 7 7-7 7'],
  search: ['M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0', 'm15 15 6 6'],
  sun: [
    'M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
    'M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1 1m12 12 1 1M5 19l1-1M18 6l1-1',
  ],
  moon: ['M20 15A9 9 0 0 1 9 4a9 9 0 1 0 11 11'],
  bell: ['M5 17h14l-2-3V9a5 5 0 0 0-10 0v5l-2 3Z', 'M10 21h4'],
  store: [
    'M3 8h18l-2-5H5L3 8Z',
    'M4 8v3a3 3 0 0 0 5 2 3 3 0 0 0 6 0 3 3 0 0 0 5-2V8',
    'M5 14v7h14v-7M10 21v-5h4v5',
  ],
  plus: ['M12 5v14M5 12h14'],
  spark: ['m12 2 3 7 7 3-7 3-3 7-3-7-7-3 7-3 3-7Z'],
  link: [
    'm10 13 4-4',
    'M8 16l-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0',
    'm16 8 1-1a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0',
  ],
  logout: ['M9 3H3v18h6', 'M10 12h11m-4-4 4 4-4 4'],
  info: ['M12 11v6M12 7v1', 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0'],
  trend: ['m3 17 6-6 4 4 8-10', 'M15 5h6v6'],
  calendar: ['M3 5h18v16H3z', 'M7 2v6M17 2v6M3 10h18'],
};
@Component({
  selector: 'g-icon',
  template: `<svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    @for (path of paths(); track $index) {
      <path [attr.d]="path" />
    }
  </svg>`,
  styles: `
    :host {
      display: inline-flex;
      flex-shrink: 0;
      width: 20px;
      height: 20px;
    }
    svg {
      width: 100%;
      height: 100%;
    }
  `,
})
export class Icon {
  readonly name = input('bag');
  readonly paths = computed(() => paths[this.name()] ?? paths['bag']);
}
