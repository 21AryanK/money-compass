import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { BANDS, RiskBand } from '../core/api.types';
import { AssessmentService } from '../core/assessment.service';
import { SessionStore } from '../core/session.store';
import { bandLabel } from '../core/format';
import { describeProblem } from '../core/problem';
import { AiCardComponent, CardColumn } from '../shared/ai-card.component';

/**
 * Tolerance, capacity and how capacity was reached; the band they produce;
 * the option to plan around a different band; and the AI-written rationale
 * for whichever band is active.
 */
@Component({
  selector: 'mc-risk',
  imports: [AiCardComponent],
  template: `
    <section class="stage">
      @if (error()) {
        <p class="app-error" role="alert">{{ error() }} <button type="button" class="btn btn-ghost btn-sm" (click)="load()">Try again</button></p>
      }
      @if (risk(); as r) {
        <div class="grid grid-2">
          <div class="glass card stat-tile">
            <span class="label">Risk tolerance</span>
            <span class="value">{{ r.toleranceScore }}</span>
            <div class="meter-track"><div class="meter-fill" [style.width.%]="animated() ? r.toleranceScore : 0" style="background: linear-gradient(90deg, var(--teal), var(--teal-bright));"></div></div>
            <p style="font-size:12.5px; color:var(--ink-3);">How you say you'd react to volatility.</p>
          </div>
          <div class="glass card stat-tile">
            <span class="label">Risk capacity</span>
            <span class="value">{{ r.capacityScore }}</span>
            <div class="meter-track"><div class="meter-fill" [style.width.%]="animated() ? r.capacityScore : 0" style="background: linear-gradient(90deg, var(--gold), var(--gold-bright));"></div></div>
            <p style="font-size:12.5px; color:var(--ink-3);">Whether your finances can absorb a downturn.</p>
          </div>
        </div>

        <div class="glass card" style="margin-top:16px;">
          <h3 style="margin-bottom:4px;">How we worked out your capacity</h3>
          <p style="font-size:13px; color:var(--ink-3); margin-bottom:10px;">Each of your answers that affects how well you could ride out a downturn, and by how much.</p>
          @for (f of r.capacityFactors; track $index) {
            <div class="factor-row"><span>{{ f.label }}</span><span class="d" [class.pos]="$index > 0 && f.delta > 0" [class.neg]="$index > 0 && f.delta < 0">{{ delta($index, f.delta) }}</span></div>
          }
          <div class="factor-row total"><span>Risk capacity{{ factorSum() !== r.capacityScore ? ' (kept within 0–100)' : '' }}</span><span class="d">{{ r.capacityScore }}</span></div>
        </div>

        <div class="glass card" style="margin-top:16px;">
          <h3>Combined risk band</h3>
          <p style="font-size:13px; color:var(--ink-3);">The lower of tolerance and capacity — capacity only ever caps the band, never raises it.</p>
          <div class="band-meter"><div class="band-marker" [style.left]="markerLeft()"></div></div>
          <div class="band-labels"><span>Conservative</span><span>Moderate</span><span>Balanced</span><span>Growth</span><span>Aggressive</span></div>
          <p style="margin-top:14px; font-size:12.5px; color:var(--ink-3);">Calculated from your answers: <b style="color:var(--ink-1); font-weight:700;">{{ label(r.riskBand) }}</b></p>

          <hr class="hairline" style="margin:20px 0 16px;" />

          <label id="band-picker-label" style="font-size:12.5px; font-weight:700; color:var(--ink-2); display:block; margin-bottom:10px;">
            Prefer to plan around a different risk profile? Choose one — everything below, and your investment plan, will follow it.
          </label>
          <div class="horizon-row" role="group" aria-labelledby="band-picker-label">
            @for (b of bands; track b) {
              <button type="button" class="horizon-btn" [class.selected]="r.activeBand === b" [attr.aria-pressed]="r.activeBand === b"
                      (click)="choose(b)" [disabled]="changing()">{{ label(b) }}</button>
            }
          </div>
          <p style="margin-top:16px; font-family:var(--font-display); font-size:20px;">Planning around: {{ label(r.activeBand) }}
            @if (r.activeBand !== r.riskBand) {
              <button type="button" class="text-link" style="font-family:var(--font-body); font-size:12.5px; margin-left:8px;" (click)="choose(r.riskBand)" [disabled]="changing()">go back to {{ label(r.riskBand) }}</button>
            }
          </p>
        </div>

        <div class="glass card" style="margin-top:16px;">
          <h3 style="margin-bottom:16px;">Suggested allocation</h3>
          <div class="alloc-bar">
            @for (e of allocation(); track e.label) {
              <div class="alloc-seg" [style.background]="e.color" [style.width.%]="animated() ? e.value : 0">{{ e.value >= 12 ? e.value + '%' : '' }}</div>
            }
          </div>
          <div class="alloc-legend">
            @for (e of allocation(); track e.label) {
              <div class="alloc-legend-item"><span class="sw" [style.background]="e.color"></span>{{ e.label }} — {{ e.value }}%</div>
            }
          </div>
        </div>

        <mc-ai-card title="Why this allocation" target="RISK" [sessionId]="sessionId()" [band]="r.activeBand" [drafts]="$any(r.drafts)"
                    summaryKey="rationale" [layout]="layout" askQuestion="Why am I in this risk band?"
                    [disclaimer]="r.disclaimer + ' Not implemented, by design: real market data, product-level recommendations, tax computation.'"
                    [regenerating]="regenerating() || changing()" (regenerate)="regenerate()" />

        <div class="risk-cta-row">
          <button class="btn btn-primary" (click)="plan()">Plan your investments →</button>
          <button class="btn btn-ghost" (click)="back()">← Back to your score</button>
        </div>
      } @else if (!error()) {
        <div class="glass card loading-card"><div class="orb"></div><div>Assessing your risk profile…</div></div>
      }
    </section>
  `,
})
export class RiskPage implements OnInit {

  readonly sessionId = input.required<string>();

  private readonly api = inject(AssessmentService);
  private readonly store = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly risk = this.store.risk;
  protected readonly bands = BANDS;
  protected readonly error = signal<string | null>(null);
  protected readonly regenerating = signal(false);
  protected readonly changing = signal(false);
  protected readonly animated = signal(false);

  protected readonly factorSum = computed(() => (this.risk()?.capacityFactors ?? []).reduce((s, f) => s + f.delta, 0));
  protected readonly markerLeft = computed(() => {
    const r = this.risk();
    const pos = r ? (BANDS.indexOf(r.riskBand) / (BANDS.length - 1)) * 100 : 0;
    return `calc(${this.animated() ? pos : 0}% - 1.5px)`;
  });
  protected readonly allocation = computed(() => {
    const a = this.risk()?.allocationPercent;
    if (!a) return [];
    return [
      { label: 'Equity', color: 'var(--a-equity)', value: a.equity },
      { label: 'Debt', color: 'var(--a-debt)', value: a.debt },
      { label: 'Gold', color: 'var(--a-gold)', value: a.gold },
      { label: 'Cash', color: 'var(--a-cash)', value: a.cash },
    ];
  });

  protected readonly layout = (c: Record<string, unknown>): CardColumn[] => [
    { title: 'Worth considering', color: 'var(--teal-bright)', className: 'col-strengths', items: (c['considerations'] as string[]) ?? [] },
    { title: 'Worth avoiding', color: 'var(--status-warn)', className: 'col-gaps', items: (c['avoid'] as string[]) ?? [] },
  ];

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.error.set(null);
    this.store.loadRisk(this.sessionId()).subscribe({
      next: () => requestAnimationFrame(() => this.animated.set(true)),
      error: (err) => this.error.set(describeProblem(err)),
    });
  }

  protected label(b: RiskBand): string {
    return bandLabel(b);
  }

  protected delta(i: number, d: number): string {
    if (i === 0) return String(d);
    return d > 0 ? `+${d}` : d === 0 ? '±0' : `−${Math.abs(d)}`;
  }

  /** Plans around a band; the calculated band is kept, and the plan follows the choice. */
  protected choose(b: RiskBand): void {
    const r = this.risk();
    if (!r || b === r.activeBand) return;
    this.changing.set(true);
    this.api.selectBand(this.sessionId(), b === r.riskBand ? null : b).subscribe({
      next: (res) => { this.store.risk.set(res); this.changing.set(false); this.store.refreshSessions().subscribe(); },
      error: (err) => { this.changing.set(false); this.error.set(describeProblem(err)); },
    });
  }

  protected regenerate(): void {
    this.regenerating.set(true);
    this.api.regenerateRisk(this.sessionId()).subscribe({
      next: (res) => { this.store.risk.set(res); this.regenerating.set(false); },
      error: (err) => { this.regenerating.set(false); this.error.set(describeProblem(err)); },
    });
  }

  protected plan(): void {
    void this.router.navigate(['/results', this.sessionId(), 'plan']);
  }

  protected back(): void {
    void this.router.navigate(['/results', this.sessionId(), 'score']);
  }
}
