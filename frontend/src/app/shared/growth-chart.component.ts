import { Component, computed, input } from '@angular/core';
import { YearPoint } from '../core/api.types';
import { compactINR, formatINR } from '../core/format';

const W = 640, H = 220, PAD_L = 54, PAD_R = 14, PAD_T = 36, PAD_B = 28;

/**
 * Projected value (gold, solid) against the amount invested (grey, dashed),
 * with the low-to-high range as a band behind them. Colours come from the
 * theme tokens through style bindings, so the chart follows light/dark mode.
 */
@Component({
  selector: 'mc-growth-chart',
  template: `
    <svg width="100%" height="220" [attr.viewBox]="'0 0 ' + W + ' ' + H" preserveAspectRatio="none" role="img"
         aria-label="Projected portfolio value versus amount invested, over time">
      <defs>
        <linearGradient id="growthFill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" style="stop-color: var(--gold-bright); stop-opacity: 0.35" />
          <stop offset="100%" style="stop-color: var(--gold-bright); stop-opacity: 0" />
        </linearGradient>
      </defs>
      @for (g of grid(); track g.y) {
        <line [attr.x1]="PAD_L" [attr.x2]="W - PAD_R" [attr.y1]="g.y" [attr.y2]="g.y" style="stroke: var(--glass-edge)" stroke-width="1" />
        <text [attr.x]="PAD_L - 10" [attr.y]="g.y + 4" text-anchor="end" font-size="10.5" style="fill: var(--ink-3); font-family: var(--font-mono)">{{ g.label }}</text>
      }
      @for (t of xTicks(); track t.i) {
        <text [attr.x]="t.x" [attr.y]="H - 8" [attr.text-anchor]="t.anchor" font-size="10.5" style="fill: var(--ink-3)">Yr {{ t.year }}</text>
      }
      <path [attr.d]="area()" fill="url(#growthFill)" stroke="none" />
      <path [attr.d]="band()" fill="rgba(240,197,105,0.14)" stroke="rgba(240,197,105,0.3)" stroke-width="0.75" />
      <path [attr.d]="line('invested')" fill="none" style="stroke: var(--ink-3)" stroke-width="2" stroke-dasharray="5,5" />
      <path [attr.d]="line('value')" fill="none" style="stroke: var(--gold-bright)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
      @if (end(); as e) {
        <circle [attr.cx]="e.x" [attr.cy]="e.y" r="4" style="fill: var(--gold-bright)" />
        <text [attr.x]="e.labelX" [attr.y]="e.y - 10" font-size="11.5" font-weight="600" style="fill: var(--ink-1); font-family: var(--font-mono)">{{ e.label }}</text>
      }
    </svg>
  `,
})
export class GrowthChartComponent {
  readonly series = input.required<YearPoint[]>();

  protected readonly W = W;
  protected readonly H = H;
  protected readonly PAD_L = PAD_L;
  protected readonly PAD_R = PAD_R;

  private readonly max = computed(() => Math.max(...this.series().map((p) => p.high || p.value), 1));

  private x(i: number): number {
    const n = this.series().length - 1 || 1;
    return PAD_L + (i / n) * (W - PAD_L - PAD_R);
  }

  private y(v: number): number {
    return H - PAD_B - (v / this.max()) * (H - PAD_T - PAD_B);
  }

  protected readonly grid = computed(() => [0, 0.5, 1].map((f) => ({ y: this.y(this.max() * f), label: compactINR(this.max() * f) })));

  protected readonly xTicks = computed(() => {
    const s = this.series();
    const last = s.length - 1;
    const idx = [...new Set([0, Math.round(last / 2), last])];
    return idx.map((i) => ({ i, x: this.x(i), year: s[i].year, anchor: i === 0 ? 'start' : i === last ? 'end' : 'middle' }));
  });

  protected line(key: 'value' | 'invested' | 'low' | 'high'): string {
    return this.series().map((p, i) => `${i === 0 ? 'M' : 'L'}${this.x(i).toFixed(1)},${this.y(p[key]).toFixed(1)}`).join(' ');
  }

  protected readonly area = computed(() => {
    const last = this.series().length - 1;
    return `${this.line('value')} L${this.x(last).toFixed(1)},${this.y(0).toFixed(1)} L${this.x(0).toFixed(1)},${this.y(0).toFixed(1)} Z`;
  });

  protected readonly band = computed(() => {
    const s = this.series();
    const back = s.slice().reverse().map((p, j) => `L${this.x(s.length - 1 - j).toFixed(1)},${this.y(p.low).toFixed(1)}`).join(' ');
    return `${this.line('high')} ${back} Z`;
  });

  protected readonly end = computed(() => {
    const s = this.series();
    if (!s.length) return null;
    const i = s.length - 1;
    return { x: this.x(i), y: this.y(s[i].value), labelX: Math.min(this.x(i), W - PAD_R - 90), label: formatINR(s[i].value) };
  });
}
