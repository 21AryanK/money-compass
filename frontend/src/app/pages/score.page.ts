import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Category } from '../core/api.types';
import { AssessmentService } from '../core/assessment.service';
import { SessionStore } from '../core/session.store';
import { CATEGORY_META } from '../core/format';
import { SNAPSHOT_NO_INCOME_NOTE, snapshotTiles } from '../core/snapshot-tiles';
import { describeProblem } from '../core/problem';
import { AiCardComponent, CardColumn } from '../shared/ai-card.component';

@Component({
  selector: 'mc-score',
  imports: [AiCardComponent],
  template: `
    <section class="stage">
      @if (error()) {
        <p class="app-error" role="alert">{{ error() }} <button type="button" class="btn btn-ghost btn-sm" (click)="load()">Try again</button></p>
      }
      @if (score(); as s) {
        <div class="glass score-hero">
          <div class="ring-wrap">
            <svg width="148" height="148" viewBox="0 0 148 148" aria-hidden="true">
              <circle cx="74" cy="74" r="64" fill="none" style="stroke: var(--well-strong);" stroke-width="12"/>
              <circle cx="74" cy="74" r="64" fill="none" stroke="url(#ringGrad)" stroke-width="12" stroke-linecap="round"
                      [attr.stroke-dasharray]="circumference" [attr.stroke-dashoffset]="ringOffset()"
                      style="transition: stroke-dashoffset 1.2s cubic-bezier(0.22,1,0.36,1);"/>
              <defs><linearGradient id="ringGrad" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#34ab84"/><stop offset="100%" stop-color="#f0c569"/></linearGradient></defs>
            </svg>
            <div class="ring-value"><span class="num">{{ shown() }}</span><span class="of100">out of 100</span></div>
          </div>
          <div class="score-hero-text">
            <h2>{{ s.bandTitle }}</h2>
            <p>{{ s.bandSub }}</p>
            @if (s.unknownNote) { <p class="unknown-note">{{ s.unknownNote }}</p> }
          </div>
        </div>

        <div class="glass card" style="margin-top:16px;">
          <h3 style="margin-bottom:16px;">Category breakdown</h3>
          @for (row of breakdown(); track row.cat) {
            <div class="breakdown-row" [title]="row.value === null ? 'No questions in this area for your profile' : ''">
              <span class="cat-label"><span class="dot" [style.background]="row.color"></span>{{ row.label }}</span>
              <div class="breakdown-track"><div class="breakdown-fill" [style.background]="row.color" [style.width.%]="animated() ? (row.value ?? 0) : 0"></div></div>
              <span class="breakdown-pct">{{ row.value === null ? 'n/a' : row.value + '%' }}</span>
            </div>
          }
        </div>

        <div class="glass card" style="margin-top:16px;">
          <h3 style="margin-bottom:4px;">Your money snapshot</h3>
          <p style="font-size:13px; color:var(--ink-3);">Worked out from your answers. Spending is estimated as income minus what you save.</p>
          <div class="plan-stats">
            @for (t of tiles(); track t.l) {
              <div class="plan-stat"><div class="l">{{ t.l }}</div><div class="n">{{ t.n }}</div>@if (t.hint) {<div class="hint">{{ t.hint }}</div>}</div>
            }
          </div>
          @if (s.snapshot.income === null) { <p class="snapshot-note">{{ noIncomeNote }}</p> }
        </div>

        <mc-ai-card title="What this means" target="SCORE" [sessionId]="sessionId()" [drafts]="$any(s.drafts)"
                    summaryKey="summary" [layout]="layout" askQuestion="What should I do first?"
                    [disclaimer]="s.disclaimer" [regenerating]="regenerating()" (regenerate)="regenerate()" />

        <div class="risk-cta-row">
          <button class="btn btn-primary" (click)="go('risk')">See your risk profile →</button>
          <button class="btn btn-ghost" (click)="retake()">Retake assessment</button>
        </div>
      } @else if (!error()) {
        <div class="glass card loading-card"><div class="orb"></div><div>Loading your results…</div></div>
      }
    </section>
  `,
})
export class ScorePage implements OnInit {

  readonly sessionId = input.required<string>();

  private readonly api = inject(AssessmentService);
  private readonly store = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly score = this.store.score;
  protected readonly error = signal<string | null>(null);
  protected readonly regenerating = signal(false);
  protected readonly animated = signal(false);
  protected readonly shown = signal(0);
  protected readonly noIncomeNote = SNAPSHOT_NO_INCOME_NOTE;
  protected readonly circumference = 2 * Math.PI * 64;

  protected readonly ringOffset = computed(() =>
    this.animated() && this.score() ? this.circumference * (1 - this.score()!.total / 100) : this.circumference);

  protected readonly breakdown = computed(() => {
    const s = this.score();
    if (!s) return [];
    return (Object.keys(CATEGORY_META) as Category[]).map((cat) => ({
      cat, label: CATEGORY_META[cat].label, color: CATEGORY_META[cat].color, value: s.categoryBreakdown[cat] ?? null,
    }));
  });

  protected readonly tiles = computed(() => this.score() ? snapshotTiles(this.score()!.snapshot) : []);

  protected readonly layout = (c: Record<string, unknown>): CardColumn[] => [
    { title: 'Strengths', color: 'var(--status-good)', className: 'col-strengths', items: (c['strengths'] as string[]) ?? [] },
    { title: 'Gaps', color: 'var(--status-warn)', className: 'col-gaps', items: (c['gaps'] as string[]) ?? [] },
    { title: 'Next steps', color: 'var(--gold-bright)', className: 'col-next', items: (c['nextSteps'] as string[]) ?? [] },
  ];

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.error.set(null);
    this.store.loadScore(this.sessionId()).subscribe({
      next: (s) => this.animateIn(s.total),
      error: (err) => this.error.set(describeProblem(err)),
    });
  }

  private animateIn(total: number): void {
    requestAnimationFrame(() => this.animated.set(true));
    const start = performance.now();
    const step = (now: number) => {
      const p = Math.min((now - start) / 1200, 1);
      this.shown.set(Math.round(total * (1 - Math.pow(1 - p, 3))));
      if (p < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  protected regenerate(): void {
    this.regenerating.set(true);
    this.api.regenerateScore(this.sessionId()).subscribe({
      next: (s) => { this.store.score.set(s); this.regenerating.set(false); },
      error: (err) => { this.regenerating.set(false); this.error.set(describeProblem(err)); },
    });
  }

  protected go(screen: 'risk' | 'plan'): void {
    void this.router.navigate(['/results', this.sessionId(), screen]);
  }

  protected retake(): void {
    this.api.start().subscribe({
      next: (res) => { this.store.refreshSessions().subscribe(); void this.router.navigate(['/assessment', res.sessionId]); },
      error: (err) => this.error.set(describeProblem(err)),
    });
  }
}
