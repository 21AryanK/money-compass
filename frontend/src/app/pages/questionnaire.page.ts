import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AssessmentService } from '../core/assessment.service';
import { AnswerValue, Progress, Question } from '../core/api.types';
import { describeProblem } from '../core/problem';

/**
 * One question at a time. Back is deliberately unavailable: the branching rules
 * mean an earlier answer can change which questions come next, so allowing
 * navigation backwards would need a rollback of everything served since.
 */
@Component({
  selector: 'mc-questionnaire',
  imports: [FormsModule],
  template: `
    <div class="mc-shell">
      @if (progress(); as p) {
        <div class="progress" role="progressbar"
             [attr.aria-valuenow]="p.answered"
             [attr.aria-valuemin]="0"
             [attr.aria-valuemax]="p.estimatedTotal">
          <span [style.width.%]="percent(p)"></span>
        </div>
        <p class="counter mc-muted">Question {{ p.answered + 1 }} of about {{ p.estimatedTotal }}</p>
      }

      @if (error()) {
        <p class="mc-error" role="alert">{{ error() }}</p>
      }

      @if (question(); as q) {
        <h1>{{ q.text }}</h1>

        @switch (q.type) {
          @case ('SINGLE') {
            <fieldset>
              <legend class="sr-only">Choose one</legend>
              @for (opt of entries(q.options); track opt[0]) {
                <label class="choice">
                  <input type="radio" name="answer" [value]="opt[0]" [(ngModel)]="single" />
                  <span>{{ opt[1] }}</span>
                </label>
              }
            </fieldset>
          }
          @case ('MULTI') {
            <fieldset>
              <legend class="sr-only">Choose all that apply</legend>
              @for (opt of entries(q.options); track opt[0]) {
                <label class="choice">
                  <input type="checkbox" [value]="opt[0]"
                         [checked]="multi().includes(opt[0])"
                         (change)="toggle(opt[0])" />
                  <span>{{ opt[1] }}</span>
                </label>
              }
            </fieldset>
          }
          @case ('NUMBER') {
            <label for="num">Enter a number</label>
            <input id="num" type="number" [(ngModel)]="numberValue" />
          }
          @case ('SCALE') {
            <fieldset class="scale">
              <legend class="sr-only">Rate from 1 to 5</legend>
              @for (n of scalePoints; track n) {
                <label class="point">
                  <input type="radio" name="scale" [value]="n" [(ngModel)]="scaleValue" />
                  <span>{{ n }}</span>
                </label>
              }
            </fieldset>
            <p class="mc-muted endpoints"><span>Not at all</span><span>Completely</span></p>
          }
        }

        <button type="button" (click)="submit()" [disabled]="busy() || !hasAnswer()">
          {{ busy() ? 'Saving' : 'Next' }}
        </button>
      } @else if (!loading()) {
        <h1>That is everything</h1>
        <p>Working out your score.</p>
      }
    </div>
  `,
  styles: `
    .progress { height: 2px; background: var(--mc-rule); margin-bottom: 0.5rem; }
    .progress span { display: block; height: 100%; background: var(--mc-brass); transition: width 200ms; }
    .counter { font-size: 0.82rem; margin-bottom: 1.5rem; }
    fieldset { border: none; padding: 0; margin: 0 0 1.5rem; display: grid; gap: 0.5rem; }
    .choice {
      display: flex; gap: 0.7rem; align-items: center;
      padding: 0.7rem 0.8rem; border: 1px solid var(--mc-rule);
      border-radius: 3px; cursor: pointer;
    }
    .choice:has(input:checked) { border-color: var(--mc-teal); background: var(--mc-surface-alt); }
    fieldset.scale { grid-auto-flow: column; gap: 0.4rem; }
    .point { display: grid; justify-items: center; gap: 0.3rem; padding: 0.6rem 0; border: 1px solid var(--mc-rule); border-radius: 3px; cursor: pointer; }
    .point:has(input:checked) { border-color: var(--mc-teal); background: var(--mc-surface-alt); }
    .endpoints { display: flex; justify-content: space-between; font-size: 0.8rem; margin-top: -1rem; }
    input[type='number'] {
      font: inherit; padding: 0.6rem 0.7rem; border: 1px solid var(--mc-rule);
      border-radius: 3px; background: var(--mc-surface); color: var(--mc-ink);
      display: block; margin-bottom: 1.5rem; width: 8rem;
    }
    label[for='num'] { font-size: 0.85rem; color: var(--mc-muted); }
    button {
      font: inherit; padding: 0.65rem 1.4rem; border: none; border-radius: 3px;
      background: var(--mc-teal); color: #fff; cursor: pointer;
    }
    button[disabled] { opacity: 0.5; cursor: default; }
    .sr-only {
      position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
      overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
    }
  `,
})
export class QuestionnairePage implements OnInit {

  /** Bound from the route by withComponentInputBinding(). */
  readonly sessionId = input.required<string>();

  private readonly assessment = inject(AssessmentService);
  private readonly router = inject(Router);

  protected readonly question = signal<Question | null>(null);
  protected readonly progress = signal<Progress | null>(null);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly scalePoints = [1, 2, 3, 4, 5];

  single: string | null = null;
  numberValue: number | null = null;
  scaleValue: number | null = null;
  protected readonly multi = signal<string[]>([]);

  ngOnInit(): void {
    this.assessment.next(this.sessionId()).subscribe({
      next: (res) => {
        this.loading.set(false);
        this.progress.set(res.progress);
        this.receive(res.question);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(describeProblem(err));
      },
    });
  }

  protected entries(options: Record<string, string> | undefined): [string, string][] {
    return Object.entries(options ?? {});
  }

  protected percent(p: Progress): number {
    return p.estimatedTotal === 0 ? 0 : Math.round((p.answered / p.estimatedTotal) * 100);
  }

  protected toggle(code: string): void {
    const current = this.multi();
    this.multi.set(
      current.includes(code) ? current.filter((c) => c !== code) : [...current, code]);
  }

  protected hasAnswer(): boolean {
    const q = this.question();
    if (!q) return false;
    switch (q.type) {
      case 'SINGLE': return this.single !== null;
      case 'MULTI':  return this.multi().length > 0;
      case 'NUMBER': return this.numberValue !== null;
      case 'SCALE':  return this.scaleValue !== null;
    }
  }

  protected submit(): void {
    const q = this.question();
    if (!q) return;

    this.error.set(null);
    this.busy.set(true);

    this.assessment.answer(this.sessionId(), q.code, this.collect(q)).subscribe({
      next: (res) => {
        this.busy.set(false);
        const p = this.progress();
        if (p) {
          this.progress.set({ ...p, answered: p.answered + 1 });
        }
        this.receive(res.nextQuestion);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(describeProblem(err));
      },
    });
  }

  private collect(q: Question): AnswerValue {
    switch (q.type) {
      case 'SINGLE': return { selected: this.single ?? '' };
      case 'MULTI':  return { selected: this.multi() };
      case 'NUMBER': return { number: this.numberValue ?? 0 };
      case 'SCALE':  return { scale: this.scaleValue ?? 0 };
    }
  }

  /** A null question means the questionnaire is finished. */
  private receive(next: Question | null): void {
    this.reset();
    this.question.set(next);

    if (next === null) {
      this.assessment.complete(this.sessionId()).subscribe({
        next: () => void this.router.navigate(['/results', this.sessionId()]),
        error: (err) => this.error.set(describeProblem(err)),
      });
    }
  }

  private reset(): void {
    this.single = null;
    this.numberValue = null;
    this.scaleValue = null;
    this.multi.set([]);
  }
}
