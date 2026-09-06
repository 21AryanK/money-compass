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
    <div class="mc-shell">
      <h1>Model status</h1>

      @if (error()) {
        <p class="mc-error" role="alert">{{ error() }}</p>
      }

      @if (health(); as h) {
        <table>
          <caption class="sr-only">Primary and fallback model availability</caption>
          <tbody>
            <tr>
              <th scope="row">Primary</th>
              <td>{{ h.primary.provider }}</td>
              <td>{{ h.primary.model }}</td>
              <td [class.down]="!h.primary.up">{{ h.primary.up ? 'reachable' : 'not reachable' }}</td>
            </tr>
            <tr>
              <th scope="row">Fallback</th>
              <td>{{ h.fallback.provider }}</td>
              <td>{{ h.fallback.model }}</td>
              <td [class.down]="!h.fallback.up">{{ h.fallback.up ? 'reachable' : 'not reachable' }}</td>
            </tr>
          </tbody>
        </table>

        <p class="mc-muted">
          Circuit breaker: {{ h.circuitState | lowercase }}. Open means the
          primary is failing often enough that requests are going straight to
          the fallback.
        </p>
      }
    </div>
  `,
  styles: `
    table { border-collapse: collapse; margin-bottom: 1.5rem; width: 100%; max-width: 34rem; }
    th, td { text-align: left; padding: 0.6rem 0.75rem 0.6rem 0; border-bottom: 1px solid var(--mc-rule); font-weight: 400; }
    th { color: var(--mc-muted); font-size: 0.85rem; width: 6rem; }
    .down { color: var(--mc-danger); }
    .sr-only {
      position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
      overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
    }
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
