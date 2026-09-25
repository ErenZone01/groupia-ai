import { Component, input } from '@angular/core';
@Component({
  selector: 'g-sales-chart',
  template: `<figure aria-label="Ventes de démonstration par jour, en milliers de francs CFA">
    <div class="axis" aria-hidden="true">
      <span>150k</span><span>100k</span><span>50k</span><span>0</span>
    </div>
    <div class="plot">
      @for (value of values(); track $index) {
        <div class="column">
          <div
            class="bar"
            [style.height.%]="value"
            [attr.aria-label]="days[$index] + ': ' + value * 1.5 + ' milliers de francs CFA'"
          ></div>
          <span>{{ days[$index] }}</span>
        </div>
      }
    </div>
  </figure>`,
  styles: `
    figure {
      display: flex;
      height: 185px;
      margin: var(--s6) 0 0;
      gap: var(--s4);
    }
    .axis {
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      padding-bottom: 26px;
      color: var(--muted);
      font-size: var(--text-xs);
    }
    .plot {
      flex: 1;
      display: flex;
      align-items: flex-end;
      justify-content: space-around;
      gap: var(--s3);
      padding-bottom: 26px;
      background: repeating-linear-gradient(
        to top,
        transparent 0,
        transparent calc(33.33% - 1px),
        var(--border) calc(33.33% - 1px),
        var(--border) 33.33%
      );
    }
    .column {
      position: relative;
      height: 100%;
      display: flex;
      align-items: flex-end;
      justify-content: center;
      flex: 1;
    }
    .bar {
      width: min(100%, 28px);
      min-height: 2px;
      border-radius: 4px 4px 0 0;
      background: linear-gradient(to top, var(--primary-strong), var(--primary));
    }
    .column:last-child .bar {
      background: var(--primary);
    }
    .column span {
      position: absolute;
      bottom: -26px;
      font-size: var(--text-xs);
      color: var(--muted);
    }
  `,
})
export class SalesChart {
  readonly values = input.required<readonly number[]>();
  readonly days = ['Lun', 'Mar', 'Mer', 'Jeu', 'Ven', 'Sam', 'Dim'];
}
