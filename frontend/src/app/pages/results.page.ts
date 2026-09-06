import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { LowerCasePipe } from '@angular/common';
import { LiteracyScoreResponse, RiskProfileResponse } from '../core/api.types';
import { AssessmentService } from '../core/assessment.service';
import { describeProblem } from '../core/problem';

interface Slice { label: string; percent: number; offset: number; colour: string; }

@Component({
  selector: 'mc-results',
  imports: [LowerCasePipe],
  template: `
    <div class="mc-shell">
      @if (error()) {
        <p class="mc-error" role="alert">{{ error() }}</p>
      }

      @if (score(); as s) {
        <section class="headline">
          <p class="label mc-muted">Financial literacy</p>
          <p class="numeral">{{ s.total }}<span>/100</span></p>
        </section>

        <p class="summary">{{ s.narrative.summary }}</p>

        <h2>By area</h2>
        <dl class="bars">
          @for (row of breakdown(s); track row[0]) {
            <div>
              <dt>{{ row[0] | lowercase }}</dt>
              <dd>
                <span class="bar" [style.width.%]="row[1]"></span>
                <b>{{ row[1] }}</b>
              </dd>
            </div>
          }
        </dl>

        @if (s.narrative.strengths.length) {
          <h2>What you have down</h2>
          <ul>@for (item of s.narrative.strengths; track item) { <li>{{ item }}</li> }</ul>
        }
        @if (s.narrative.gaps.length) {
          <h2>What to look at</h2>
          <ul>@for (item of s.narrative.gaps; track item) { <li>{{ item }}</li> }</ul>
        }
        @if (s.narrative.nextSteps.length) {
          <h2>Where to start</h2>
          <ol>@for (item of s.narrative.nextSteps; track item) { <li>{{ item }}</li> }</ol>
        }
      }

      @if (risk(); as r) {
        <h2>Risk profile</h2>
        <p>
          Willingness {{ r.toleranceScore }}, ability {{ r.capacityScore }}.
          Your band is {{ r.riskBand | lowercase }}, because ability caps
          willingness and never the other way round.
        </p>

        <div class="allocation">
          <svg viewBox="0 0 42 42" role="img" [attr.aria-label]="allocationLabel(r)">
            @for (slice of slices(); track slice.label) {
              <circle class="ring" cx="21" cy="21" r="15.9155"
                      [attr.stroke]="slice.colour"
                      [attr.stroke-dasharray]="slice.percent + ' ' + (100 - slice.percent)"
                      [attr.stroke-dashoffset]="slice.offset" />
            }
          </svg>
          <ul class="legend">
            @for (slice of slices(); track slice.label) {
              <li>
                <i [style.background]="slice.colour"></i>
                {{ slice.label | lowercase }} <b>{{ slice.percent }}%</b>
              </li>
            }
          </ul>
        </div>

        <p>{{ r.narrative.rationale }}</p>

        @if (r.narrative.considerations.length) {
          <h3>Worth knowing</h3>
          <ul>@for (item of r.narrative.considerations; track item) { <li>{{ item }}</li> }</ul>
        }
        @if (r.narrative.avoid.length) {
          <h3>Avoid</h3>
          <ul>@for (item of r.narrative.avoid; track item) { <li>{{ item }}</li> }</ul>
        }
      }

      @if (score(); as s) {
        <p class="provenance mc-muted">
          Explanation written by {{ s.modelUsed }} via {{ s.providerUsed }}.
          The numbers above are computed in the application, not by the model.
        </p>
        <p class="mc-disclaimer">{{ s.disclaimer }}</p>
      }
    </div>
  `,
  styles: `
    .headline { margin-bottom: 1rem; }
    .label { font-size: 0.85rem; margin: 0; }
    .numeral {
      font-family: Newsreader, Georgia, serif;
      font-size: clamp(4rem, 16vw, 7rem);
      line-height: 1;
      margin: 0;
      color: var(--mc-teal);
    }
    .numeral span { font-size: 0.24em; color: var(--mc-muted); margin-left: 0.2rem; }
    .summary { font-size: 1.05rem; margin-bottom: 2rem; }

    .bars { margin: 0 0 2rem; display: grid; gap: 0.5rem; }
    .bars div { display: grid; grid-template-columns: 8rem 1fr; align-items: center; gap: 0.75rem; }
    .bars dt { font-size: 0.9rem; color: var(--mc-muted); }
    .bars dd { margin: 0; display: flex; align-items: center; gap: 0.6rem; }
    .bar { display: block; height: 6px; background: var(--mc-teal); border-radius: 3px; min-width: 2px; }
    .bars b { font-weight: 500; font-size: 0.85rem; font-variant-numeric: tabular-nums; }

    .allocation { display: flex; flex-wrap: wrap; align-items: center; gap: 2rem; margin: 1rem 0 2rem; }
    .allocation svg { width: 9rem; height: 9rem; transform: rotate(-90deg); }
    .ring { fill: none; stroke-width: 5; }
    .legend { list-style: none; padding: 0; margin: 0; display: grid; gap: 0.4rem; font-size: 0.92rem; }
    .legend li { display: flex; align-items: center; gap: 0.5rem; }
    .legend i { width: 10px; height: 10px; border-radius: 2px; display: inline-block; }

    ul, ol { max-width: var(--mc-measure); padding-left: 1.15rem; margin: 0 0 1.5rem; }
    li { margin-bottom: 0.35rem; }
    .provenance { font-size: 0.85rem; margin-top: 2rem; }
  `,
})
export class ResultsPage implements OnInit {

  readonly sessionId = input.required<string>();

  private readonly assessment = inject(AssessmentService);

  protected readonly score = signal<LiteracyScoreResponse | null>(null);
  protected readonly risk = signal<RiskProfileResponse | null>(null);
  protected readonly error = signal<string | null>(null);

  private readonly palette = ['#0E5C55', '#B4762A', '#4E7C8A', '#8FA69B', '#6B4F7C'];

  ngOnInit(): void {
    this.assessment.computeScore(this.sessionId()).subscribe({
      next: (s) => this.score.set(s),
      error: (err) => this.error.set(describeProblem(err)),
    });
    this.assessment.computeRisk(this.sessionId()).subscribe({
      next: (r) => this.risk.set(r),
      error: () => { /* the score error above is enough for one screen */ },
    });
  }

  protected breakdown(s: LiteracyScoreResponse): [string, number][] {
    return Object.entries(s.categoryBreakdown).sort((a, b) => b[1] - a[1]);
  }

  /**
   * Donut geometry: each slice is a circle with a dash pattern the length of its
   * own percentage, offset by everything drawn before it. Circumference is 100
   * because r is 15.9155, which keeps the arithmetic in percentage points.
   */
  protected readonly slices = computed<Slice[]>(() => {
    const r = this.risk();
    if (!r) return [];
    let running = 0;
    return Object.entries(r.allocationPercent).map(([label, percent], i) => {
      const slice: Slice = {
        label,
        percent,
        offset: running === 0 ? 0 : 100 - running,
        colour: this.palette[i % this.palette.length]!,
      };
      running += percent;
      return slice;
    });
  });

  protected allocationLabel(r: RiskProfileResponse): string {
    return Object.entries(r.allocationPercent)
      .map(([k, v]) => `${k} ${v} percent`)
      .join(', ');
  }
}
