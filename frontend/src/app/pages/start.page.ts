import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AssessmentService } from '../core/assessment.service';
import { AuthService } from '../core/auth.service';
import { describeProblem } from '../core/problem';

@Component({
  selector: 'mc-start',
  template: `
    <div class="mc-shell">
      <h1>Ready when you are</h1>
      <p>
        Around fifteen questions, no right answers to guess at. You will get a
        literacy score with an explanation, and a risk profile with an asset mix
        that fits your situation rather than your mood.
      </p>

      @if (error()) {
        <p class="mc-error" role="alert">{{ error() }}</p>
      }

      <button type="button" (click)="begin()" [disabled]="busy()">
        {{ busy() ? 'Starting' : 'Start' }}
      </button>

      <p class="mc-disclaimer">
        Money Compass provides educational content only and is not financial
        advice. Consult a SEBI registered investment adviser before making
        investment decisions.
      </p>
    </div>
  `,
  styles: `
    button {
      font: inherit;
      margin-top: 0.5rem;
      padding: 0.7rem 1.4rem;
      border: none;
      border-radius: 3px;
      background: var(--mc-teal);
      color: #fff;
      cursor: pointer;
    }
    button[disabled] { opacity: 0.6; cursor: default; }
  `,
})
export class StartPage {
  private readonly assessment = inject(AssessmentService);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  begin(): void {
    this.error.set(null);
    this.busy.set(true);

    this.assessment.start().subscribe({
      next: (res) => void this.router.navigate(['/questionnaire', res.sessionId]),
      error: (err) => {
        this.busy.set(false);
        this.error.set(describeProblem(err));
      },
    });
  }
}
