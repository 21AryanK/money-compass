import { Component, ElementRef, OnInit, computed, inject, input, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { AnswerValue, Progress, Question } from '../core/api.types';
import { AssessmentService } from '../core/assessment.service';
import { SessionStore } from '../core/session.store';
import { CATEGORY_META } from '../core/format';
import { describeProblem } from '../core/problem';

/**
 * One question at a time. The server decides what comes next — clarifiers
 * after "I don't know", follow-ups triggered by an answer, advanced questions
 * dropped — so this page only renders what it's given and says, above the
 * question, why the session just changed shape.
 */
@Component({
  selector: 'mc-questionnaire',
  template: `
    <section class="stage-narrow">
      <div class="q-progress-row">
        <div class="q-progress-track" role="progressbar" aria-label="Assessment progress" aria-valuemin="0" aria-valuemax="100"
             [attr.aria-valuenow]="percent()" [attr.aria-valuetext]="progressText()">
          <div class="q-progress-fill" [style.width.%]="percent()"></div>
        </div>
        <span class="q-progress-label">{{ progressLabel() }}</span>
      </div>

      @if (error()) {
        <p class="app-error" role="alert" style="margin-bottom:14px;">{{ error() }}
          <button type="button" class="btn btn-ghost btn-sm" (click)="reload()">Try again</button></p>
      }

      @if (question(); as q) {
        <div class="glass q-card">
          @if (notes()) {
            <div class="q-adapt" aria-live="polite">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 12h12M12 6l6 6-6 6"/></svg>
              <span>{{ notes() }}</span>
            </div>
          }
          <div class="q-head">
            <div class="q-category"><span class="dot" [style.background]="meta(q).color"></span><span>{{ meta(q).label }}</span></div>
            @if (q.wikiUrl) {
              <a class="q-learn" [href]="q.wikiUrl" target="_blank" rel="noopener noreferrer"
                 [attr.aria-label]="'Learn about ' + q.wikiTitle + ' on Wikipedia (opens in a new tab)'">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 4h6a4 4 0 014 4v13a3 3 0 00-3-3H2zM22 4h-6a4 4 0 00-4 4v13a3 3 0 013-3h7z"/></svg>
                <span>Learn about {{ wikiTopic(q) }} on Wikipedia</span>
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M7 17L17 7M8 7h9v9"/></svg>
              </a>
            }
          </div>
          <div class="q-body">
            <h2 class="q-text" tabindex="-1" #questionText>{{ q.text }}</h2>
            @if (q.hint) { <p class="q-hint">{{ q.hint }}</p> }

            @if (q.type === 'SINGLE' || q.type === 'MULTI') {
              <div class="options" role="group" [attr.aria-label]="q.text">
                @for (opt of options(q); track opt[0]) {
                  <button type="button" class="option" [class.selected]="isChosen(opt[0])" [attr.aria-pressed]="isChosen(opt[0])"
                          (click)="choose(q, opt[0])" [disabled]="busy()">
                    <span class="badge">{{ opt[0] }}</span><span>{{ opt[1] }}</span>
                    <span class="check"><svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.5L6.2 11.5L13 4.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
                  </button>
                }
              </div>
            }

            @if (q.type === 'NUMBER') {
              <div>
                <div class="number-input-wrap">
                  <button type="button" class="number-step" (click)="step(q, -1)" aria-label="Decrease" [disabled]="busy()">−</button>
                  <div class="number-field">
                    <input type="number" inputmode="decimal" [attr.min]="q.min ?? 0" [attr.max]="q.max" [placeholder]="q.min ?? 0"
                           [value]="numberText()" (input)="typed(q, $any($event.target).value)" [attr.aria-label]="q.text" [disabled]="busy()" />
                  </div>
                  <button type="button" class="number-step" (click)="step(q, 1)" aria-label="Increase" [disabled]="busy()">+</button>
                </div>
                <div class="number-unit">{{ q.unit }}</div>
              </div>
            }

            @if (q.type === 'SCALE') {
              <div>
                <div class="scale-row" role="group" [attr.aria-label]="q.text">
                  @for (v of [1, 2, 3, 4, 5]; track v) {
                    <button type="button" class="scale-btn" [class.selected]="value()?.scale === v" [attr.aria-pressed]="value()?.scale === v"
                            (click)="value.set({ scale: v })" [disabled]="busy()">{{ v }}</button>
                  }
                </div>
                <div class="scale-labels"><span>{{ q.scaleLabels?.[0] ?? 'Low' }}</span><span>{{ q.scaleLabels?.[1] ?? 'High' }}</span></div>
              </div>
            }

            <button type="button" class="option option-unknown" [class.selected]="value()?.unknown" [attr.aria-pressed]="!!value()?.unknown"
                    (click)="setUnknown()" [disabled]="busy()">
              <span class="badge">?</span>
              <span class="unknown-text"><span>I don't know about this</span><span class="unknown-sub">{{ q.unknownConsequence }}</span></span>
              <span class="check"><svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.5L6.2 11.5L13 4.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
            </button>
          </div>
          <div class="q-footer">
            <button class="btn btn-ghost btn-sm" (click)="back()" [disabled]="busy() || (progress()?.answered ?? 0) === 0">Back</button>
            <button class="btn btn-primary" (click)="next()" [disabled]="busy() || !valid()">
              {{ busy() ? 'Saving…' : (q.last && !value()?.unknown ? 'Finish' : 'Next question') }}
            </button>
          </div>
        </div>
      } @else if (!error()) {
        <div class="glass q-card loading-card"><div class="orb"></div><div>{{ finishing() ? 'Wrapping up your answers…' : 'Loading your next question…' }}</div></div>
      }
    </section>
  `,
})
export class QuestionnairePage implements OnInit {

  readonly sessionId = input.required<string>();

  private readonly api = inject(AssessmentService);
  private readonly store = inject(SessionStore);
  private readonly router = inject(Router);
  private readonly questionText = viewChild<ElementRef<HTMLElement>>('questionText');

  protected readonly question = signal<Question | null>(null);
  protected readonly progress = signal<Progress | null>(null);
  protected readonly value = signal<AnswerValue | null>(null);
  protected readonly numberText = signal('');
  protected readonly busy = signal(false);
  protected readonly finishing = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Skipped-advanced count before this question, to say "skipped N" when it grows. */
  private skippedBefore: number | null = null;
  protected readonly notes = signal('');

  protected readonly percent = computed(() => {
    const p = this.progress();
    return p && p.estimatedTotal > 0 ? Math.round((p.answered / p.estimatedTotal) * 100) : 0;
  });
  protected readonly progressLabel = computed(() => {
    const p = this.progress();
    return p ? `Question ${p.answered + 1} of ~${p.estimatedTotal}` : 'Starting…';
  });
  protected readonly progressText = computed(() => {
    const p = this.progress();
    return p ? `Question ${p.answered + 1} of about ${p.estimatedTotal}` : '';
  });

  protected readonly valid = computed(() => {
    const v = this.value();
    const q = this.question();
    if (!v || !q) return false;
    if (v.unknown) return true;
    if (q.type === 'MULTI') return Array.isArray(v.selected) && v.selected.length > 0;
    return true;
  });

  ngOnInit(): void {
    this.reload();
  }

  protected reload(): void {
    this.error.set(null);
    this.api.next(this.sessionId()).subscribe({
      next: (res) => this.show(res.question, res.progress, null),
      error: (err) => this.error.set(describeProblem(err)),
    });
  }

  protected meta(q: Question) {
    return CATEGORY_META[q.category];
  }

  protected wikiTopic(q: Question): string {
    return (q.wikiTitle ?? '').replace(/\s*\(.*\)$/, '');
  }

  protected options(q: Question): [string, string][] {
    return Object.entries(q.options ?? {});
  }

  protected isChosen(code: string): boolean {
    const s = this.value()?.selected;
    return Array.isArray(s) ? s.includes(code) : s === code;
  }

  protected choose(q: Question, code: string): void {
    if (q.type === 'SINGLE') {
      this.value.set({ selected: code });
      return;
    }
    const current = this.value()?.selected;
    const list = Array.isArray(current) ? current : [];
    this.value.set({ selected: list.includes(code) ? list.filter((c) => c !== code) : [...list, code] });
  }

  protected typed(q: Question, raw: string): void {
    this.numberText.set(raw);
    const v = parseFloat(raw);
    const tooLow = v < (q.min ?? 0);
    const tooHigh = q.max !== null && v > q.max;
    this.value.set(Number.isNaN(v) || tooLow || tooHigh ? null : { number: v });
  }

  protected step(q: Question, delta: number): void {
    const current = parseFloat(this.numberText()) || 0;
    const next = Math.max(q.min ?? 0, current + delta);
    this.typed(q, String(q.max !== null ? Math.min(q.max, next) : next));
  }

  protected setUnknown(): void {
    this.numberText.set('');
    this.value.set({ unknown: true });
  }

  protected next(): void {
    const q = this.question();
    const v = this.value();
    if (!q || !v || !this.valid()) return;
    this.busy.set(true);
    this.api.answer(this.sessionId(), q.code, v).subscribe({
      next: (res) => {
        this.busy.set(false);
        if (res.nextQuestion) {
          this.show(res.nextQuestion, res.progress, null);
        } else {
          this.progress.set(res.progress);
          this.finish();
        }
      },
      error: (err) => { this.busy.set(false); this.error.set(describeProblem(err)); },
    });
  }

  protected back(): void {
    this.busy.set(true);
    this.api.back(this.sessionId()).subscribe({
      next: (res) => { this.busy.set(false); this.show(res.question, res.progress, res.previousValue); },
      error: (err) => { this.busy.set(false); this.error.set(describeProblem(err)); },
    });
  }

  private finish(): void {
    this.question.set(null);
    this.finishing.set(true);
    this.api.complete(this.sessionId()).subscribe({
      next: () => {
        this.store.refreshSessions().subscribe();
        void this.router.navigate(['/analyzing', this.sessionId()]);
      },
      error: (err) => { this.finishing.set(false); this.error.set(describeProblem(err)); },
    });
  }

  private show(q: Question | null, progress: Progress, restore: AnswerValue | null): void {
    this.progress.set(progress);
    if (!q) {
      this.finish();
      return;
    }
    // Say why the session just changed shape: a follow-up, or advanced questions dropped.
    const notes: string[] = [];
    if (q.followUpNote) notes.push(q.followUpNote);
    if (this.skippedBefore !== null && progress.skippedAdvanced > this.skippedBefore) {
      const n = progress.skippedAdvanced - this.skippedBefore;
      notes.push(`Skipped ${n} advanced question${n > 1 ? 's' : ''} — we'll stick to the basics there.`);
    }
    this.skippedBefore = progress.skippedAdvanced;
    this.notes.set(notes.join(' '));

    this.question.set(q);
    this.value.set(restore);
    this.numberText.set(restore?.number !== undefined ? String(restore.number) : '');
    // Move focus to the new question so keyboard and screen-reader users start there.
    if (progress.answered > 0 || restore) {
      setTimeout(() => this.questionText()?.nativeElement.focus({ preventScroll: true }));
    }
  }
}
