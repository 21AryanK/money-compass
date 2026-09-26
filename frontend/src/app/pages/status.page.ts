import { Component, OnInit, inject, signal } from '@angular/core';
import { LowerCasePipe } from '@angular/common';
import { AiHealthResponse } from '../core/api.types';
import { AssessmentService } from '../core/assessment.service';
import { describeProblem } from '../core/problem';

/**
 * Which model is answering right now, and whether the circuit breaker has
 * tripped. This is the screen that makes the fallback visible rather than
 * merely claimed.
 */
@Component({
  selector: 'mc-status',
  imports: [LowerCasePipe],
  template: `
    <section class="stage">
      @if (error()) {
        <p class="app-error" role="alert">{{ error() }}</p>
      }
      @if (health(); as h) {
        <div class="glass card">
          <h3 style="margin-bottom:12px;">AI providers</h3>
          @for (row of [{ name: 'Primary', p: h.primary }, { name: 'Fallback', p: h.fallback }]; track row.name) {
            <div class="factor-row">
              <span><b>{{ row.name }}</b> · {{ row.p.provider }} · {{ row.p.model }}</span>
              <span class="d" [class.pos]="row.p.up" [class.neg]="!row.p.up">
                {{ row.p.up ? 'reachable' : 'not reachable' }}{{ row.p.probeMs !== null ? ' · ' + row.p.probeMs + 'ms' : '' }}
              </span>
            </div>
          }
          <p style="font-size:13px; color:var(--ink-3); margin-top:14px;">
            Circuit breaker: {{ h.circuitState | lowercase }}. Open means the primary is failing often enough that
            requests are going straight to the fallback. If no model answers, explanations and chat replies are
            written from your numbers by the app's rules and labelled as such — scores, risk bands and plans
            never depend on a model.
          </p>
        </div>
      } @else if (!error()) {
        <div class="glass card loading-card"><div class="orb"></div><div>Checking the models…</div></div>
      }
    </section>
  `,
})
export class StatusPage implements OnInit {
  private readonly assessment = inject(AssessmentService);

  protected readonly health = signal<AiHealthResponse | null>(null);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.assessment.aiHealth().subscribe({
      next: (h) => this.health.set(h),
      error: (err) => this.error.set(describeProblem(err)),
    });
  }
}
