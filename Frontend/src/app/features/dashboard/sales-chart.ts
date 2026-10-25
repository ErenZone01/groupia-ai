import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  signal,
} from '@angular/core';
import { MonthlyPoint } from '../../api/generated/models/monthly-point';
import { francs, monthLabel, quantity } from './business-analytics.model';

@Component({
  selector: 'g-sales-chart',
  templateUrl: './sales-chart.html',
  styleUrl: './sales-chart.scss',
})
export class SalesChart {
  readonly width = signal(800);
  constructor() {
    const element = inject(ElementRef<HTMLElement>).nativeElement;
    const destroy = inject(DestroyRef);
    afterNextRender(() => {
      if (typeof ResizeObserver === 'undefined') return;
      const observer = new ResizeObserver((entries) =>
        this.width.set(Math.max(200, entries[0].contentRect.width)),
      );
      observer.observe(element);
      destroy.onDestroy(() => observer.disconnect());
    });
  }
  readonly months = input.required<MonthlyPoint[]>();
  private readonly selection = signal<{ month: string | undefined; points: MonthlyPoint[] } | null>(
    null,
  );
  readonly selectedIndex = computed(() => {
    const selection = this.selection();
    const found =
      selection?.points === this.months()
        ? this.months().findIndex((p) => p.month === selection.month)
        : -1;
    return found >= 0 ? found : this.months().length - 1;
  });
  readonly selected = computed(() => this.months()[this.selectedIndex()]);
  readonly maximum = computed(() =>
    Math.max(
      1,
      ...this.months().flatMap((p) => [p.goodsSales ?? 0, p.previousYearGoodsSales ?? 0]),
    ),
  );
  readonly ticks = computed(() =>
    [0, 1, 2, 3, 4].map((i) => ({ y: 24 + i * 50, value: this.maximum() * (1 - i / 4) })),
  );
  readonly currentPath = computed(() => this.path('goodsSales'));
  readonly previousPath = computed(() => this.path('previousYearGoodsSales'));
  readonly area = computed(() => this.currentPath() + ` L${this.width() - 16},224 L48,224 Z`);
  readonly francs = francs;
  readonly quantity = quantity;
  readonly monthLabel = monthLabel;
  readonly axisLabel = (value: number) =>
    new Intl.NumberFormat('fr-FR', { notation: 'compact', maximumFractionDigits: 1 }).format(value);
  x(index: number) {
    return 48 + (index / Math.max(1, this.months().length - 1)) * (this.width() - 64);
  }
  y(value?: number) {
    return 224 - ((value ?? 0) / this.maximum()) * 200;
  }
  select(index: number) {
    const bounded = Math.max(0, Math.min(index, this.months().length - 1));
    if (bounded === this.selectedIndex()) return;
    const point = this.months()[bounded];
    this.selection.set({ month: point?.month, points: this.months() });
  }
  keydown(event: KeyboardEvent) {
    const index = {
      ArrowLeft: this.selectedIndex() - 1,
      ArrowRight: this.selectedIndex() + 1,
      Home: 0,
      End: this.months().length - 1,
    }[event.key];
    if (index !== undefined) {
      event.preventDefault();
      this.select(index);
    }
  }
  pointAt(event: PointerEvent) {
    if (event.type === 'pointermove' && event.pointerType !== 'mouse') return;
    const bounds = (event.currentTarget as SVGElement).getBoundingClientRect();
    this.select(
      Math.round(
        ((((event.clientX - bounds.left) / bounds.width) * this.width() - 48) /
          (this.width() - 64)) *
          (this.months().length - 1),
      ),
    );
  }
  private path(key: 'goodsSales' | 'previousYearGoodsSales') {
    // Straight segments preserve observed monthly totals, without spline overshoot.
    return this.months()
      .map((point, i) => `${i ? 'L' : 'M'}${this.x(i)},${this.y(point[key])}`)
      .join(' ');
  }
}
