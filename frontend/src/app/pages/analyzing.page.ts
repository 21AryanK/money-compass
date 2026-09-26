import { Component, OnDestroy, OnInit, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { LiteracyScoreResponse, RiskProfileResponse } from '../core/api.types';
import { AssessmentService } from '../core/assessment.service';
import { SessionStore } from '../core/session.store';
import { CATEGORY_META, bandLabel } from '../core/format';
import { describeProblem } from '../core/problem';

interface LogLine { html: string; }

/**
 * Between the last answer and the results. Scores the session and assesses
 * risk — each of which also has the model write its explanation — and
 * narrates what actually comes back: the real score, the real capacity
 * factors, which model wrote the explanation and how long it took.
 */
@Component({
  selector: 'mc-analyzing',
  template: `
    <section class="analyzing-wrap">
      <div class="glass analyzing-card">
        <div class="orb"></div>
        <h3 style="font-size:19px;">Putting your report together</h3>
        <div class="steps">
          @for (s of steps; track $index) {
            <div class="step" [class.active]="state()[$index] === 'active'" [class.done]="state()[$index] === 'done'">
              <span class="icon"></span><span>{{ $index === 2 ? 'Asking ' + model() + ' for your explanation' : s }}</span>
            </div>
          }
        </div>
        <div class="reason-log" aria-live="polite">
          @for (l of log(); track $index) { <div [innerHTML]="l.html"></div> }
        </div>
        @if (error()) {
          <p class="app-error" role="alert" style="margin-top:16px; text-align:left;">{{ error() }}
            <button type="button" class="btn btn-ghost btn-sm" (click)="run()">Try again</button></p>
        }
      </div>
    </section>
  `,
})
export class AnalyzingPage implements OnInit, OnDestroy {

  readonly sessionId = input.required<string>();

  private readonly api = inject(AssessmentService);
  private readonly store = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly steps = ['Scoring your answers', 'Assessing risk tolerance & capacity', '', 'Finishing up'];
  protected readonly state = signal<('idle' | 'active' | 'done')[]>(['idle', 'idle', 'idle', 'idle']);
  protected readonly log = signal<LogLine[]>([]);
  protected readonly model = signal('the model');
  protected readonly error = signal<string | null>(null);
  private timers: ReturnType<typeof setTimeout>[] = [];

  ngOnInit(): void {
    this.api.aiHealth().subscribe({ next: (h) => this.model.set(h.primary.model), error: () => {} });
    this.run();
  }

  ngOnDestroy(): void {
    this.timers.forEach(clearTimeout);
  }

  protected run(): void {
    this.error.set(null);
    this.log.set([]);
    this.store.useSession(this.sessionId());
    const answered = this.store.sessions()?.find((s) => s.sessionId === this.sessionId())?.answered;
    if (answered) this.say(`› Read <b>${answered} answers</b> from your assessment`);
    this.setState(['active', 'active', 'active', 'idle']);
    this.later(() => this.say(`› Prompting ${esc(this.model())} with your snapshot…`), 500);

    const started = performance.now();
    forkJoin({ score: this.store.loadScore(this.sessionId()), risk: this.store.loadRisk(this.sessionId()) }).subscribe({
      next: ({ score, risk }) => {
        this.setState(['done', 'done', 'done', 'active']);
        this.narrate(score, risk, performance.now() - started);
        this.later(() => {
          this.setState(['done', 'done', 'done', 'done']);
          void this.router.navigate(['/results', this.sessionId(), 'score'], { replaceUrl: true });
        }, 1400);
      },
      error: (err) => {
        this.setState(['idle', 'idle', 'idle', 'idle']);
        this.error.set(describeProblem(err));
      },
    });
  }

  private narrate(score: LiteracyScoreResponse, risk: RiskProfileResponse, elapsedMs: number): void {
    const assessed = Object.entries(score.categoryBreakdown).filter(([, v]) => v !== null) as [keyof typeof CATEGORY_META, number][];
    assessed.sort((a, b) => b[1] - a[1]);
    const best = assessed[0], worst = assessed[assessed.length - 1];
    const draft = score.drafts[score.drafts.length - 1];
    const drags = risk.capacityFactors.slice(1).filter((f) => f.delta !== 0).sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta));
    const lines = [
      `› Literacy score <b>${score.total}/100</b> · best ${CATEGORY_META[best[0]].label.toLowerCase()} ${best[1]}%, weakest ${CATEGORY_META[worst[0]].label.toLowerCase()} ${worst[1]}%`,
      score.unknownNote ? `› ${esc(score.unknownNote)}` : '',
      `› Risk tolerance <b>${risk.toleranceScore}</b>, capacity <b>${risk.capacityScore}</b>` +
        (drags[0] ? ` · biggest factor: ${esc(lowerFirst(drags[0].label))} (${drags[0].delta > 0 ? '+' : ''}${drags[0].delta})` : ''),
      `› Band = min(tolerance, capacity) → <b>${bandLabel(risk.riskBand)}</b>`,
      draft && draft.provider !== 'template'
        ? `› Explanation written by ${esc(draft.model)} in ${(draft.latencyMs / 1000).toFixed(1)}s${draft.tokens ? ` (${draft.tokens} tokens)` : ''}`
        : `› The model didn't answer — explanation written from your numbers instead`,
      `› Ready in ${(elapsedMs / 1000).toFixed(1)}s`,
    ].filter(Boolean);
    lines.forEach((l, i) => this.later(() => this.say(l), 120 + i * 180));
  }

  private say(html: string): void {
    this.log.update((l) => [...l, { html }]);
  }

  private setState(s: ('idle' | 'active' | 'done')[]): void {
    this.state.set(s);
  }

  private later(fn: () => void, ms: number): void {
    this.timers.push(setTimeout(fn, ms));
  }
}

function esc(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!));
}

function lowerFirst(s: string): string {
  return s ? s.charAt(0).toLowerCase() + s.slice(1) : s;
}
