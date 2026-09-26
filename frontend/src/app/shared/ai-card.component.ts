import { Component, OnDestroy, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Draft, RiskBand } from '../core/api.types';
import { AssessmentService } from '../core/assessment.service';
import { providerLabel } from '../core/format';
import { StreamedText } from '../core/stream-text';
import { AskBus } from '../assistant/ask-bus';

export interface CardColumn {
  title: string;
  color: string;
  className: string;
  items: string[];
}

/**
 * An AI-written card: the score's "What this means" or the risk screen's
 * "Why this allocation". Owns everything that makes it behave like a model's
 * answer: Regenerate (a real new draft from the server), the ‹ 2 / 3 › draft
 * history, text streaming in, feedback stored against the exact draft, Copy,
 * and "Ask a follow-up →". The parent supplies the drafts and how to lay a
 * draft's content out into a summary and columns.
 */
@Component({
  selector: 'mc-ai-card',
  template: `
    <div class="glass card" [class.regenerating]="regenerating()" style="margin-top:16px;">
      <div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px;">
        <h3>{{ title() }}</h3>
        <div class="provenance-row">
          @if (drafts().length > 1) {
            <span class="draft-nav">
              <button type="button" (click)="go(-1)" [disabled]="index() === 0 || regenerating()" aria-label="Previous draft">‹</button>
              <span>{{ index() + 1 }} / {{ drafts().length }}</span>
              <button type="button" (click)="go(1)" [disabled]="index() === drafts().length - 1 || regenerating()" aria-label="Next draft">›</button>
            </span>
          }
          <span class="provenance" [class.thinking]="regenerating() || summary.streaming()">
            <span class="dot" [style.background]="isFallback() ? 'var(--status-warn)' : null"></span>
            <span>{{ provenance() }}</span>
          </span>
          <button type="button" class="regen-btn" [class.spinning]="regenerating()" [disabled]="regenerating()"
                  (click)="regenerate.emit()" title="Ask the model to write this again">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M20 11A8 8 0 105.5 16.5M4 4v5h5"/></svg>
            {{ regenerating() ? 'Writing…' : 'Regenerate' }}
          </button>
        </div>
      </div>

      <p class="narrative-summary" [class.streaming-cursor]="regenerating() || summary.streaming()">{{ summary.text() }}</p>

      @if (showColumns()) {
        <div class="narrative-cols" [class.narrative-cols-2]="columns().length === 2">
          @for (col of columns(); track col.title) {
            <div class="narrative-col" [class]="col.className">
              <h4 [style.color]="col.color">{{ col.title }}</h4>
              <ul>
                @for (item of col.items; track $index) {
                  <li class="reveal-item" [style.animation-delay.ms]="$index * 110 + col.items.length * 0">{{ item }}</li>
                }
              </ul>
            </div>
          }
        </div>
      }

      @if (isFallback()) {
        <p class="provider-note"><b>Model unavailable.</b> This draft was assembled from your own numbers by the app's rules, not written by a model. Regenerate to try the model again.</p>
      }

      <div class="response-actions">
        <button type="button" class="icon-btn" [class.selected]="rating() === 'UP'" [attr.aria-pressed]="rating() === 'UP'"
                (click)="rate('UP')" aria-label="Helpful" title="Helpful">👍</button>
        <button type="button" class="icon-btn" [class.selected]="rating() === 'DOWN'" [attr.aria-pressed]="rating() === 'DOWN'"
                (click)="rate('DOWN')" aria-label="Not helpful" title="Not helpful">👎</button>
        <button type="button" class="icon-btn" (click)="copy()" aria-label="Copy text">Copy</button>
        <span class="fb-note" role="status">{{ note() }}</span>
        <button type="button" class="icon-btn ask-link" (click)="ask.open(askQuestion())">Ask a follow-up →</button>
      </div>
      <hr class="hairline" />
      <p class="disclaimer">{{ disclaimer() }}</p>
    </div>
  `,
})
export class AiCardComponent implements OnDestroy {

  readonly title = input.required<string>();
  readonly target = input.required<'SCORE' | 'RISK'>();
  readonly sessionId = input.required<string>();
  /** For RISK feedback: the band the rationale was written for. */
  readonly band = input<RiskBand | null>(null);
  readonly drafts = input.required<Draft<Record<string, unknown>>[]>();
  /** Which content key holds the lead paragraph: "summary" or "rationale". */
  readonly summaryKey = input.required<string>();
  /** Lays a draft's content out into columns. */
  readonly layout = input.required<(content: Record<string, unknown>) => CardColumn[]>();
  readonly askQuestion = input.required<string>();
  readonly disclaimer = input.required<string>();
  readonly regenerating = input(false);
  readonly regenerate = output<void>();

  private readonly api = inject(AssessmentService);
  protected readonly ask = inject(AskBus);

  protected readonly summary = new StreamedText();
  protected readonly index = signal(0);
  protected readonly showColumns = signal(false);
  protected readonly note = signal('');
  /** Ratings per draft index, for this card's current band. */
  private readonly ratings = signal<Record<string, 'UP' | 'DOWN'>>({});
  private seenCount = 0;
  private seenBand: RiskBand | null = null;

  protected readonly current = computed(() => this.drafts()[this.index()] ?? null);
  protected readonly columns = computed(() => {
    const d = this.current();
    return d ? this.layout()(d.content) : [];
  });
  protected readonly isFallback = computed(() => this.current()?.provider === 'template');
  protected readonly provenance = computed(() => {
    if (this.regenerating()) return 'writing a new draft…';
    const d = this.current();
    if (!d) return '';
    const base = providerLabel(d.provider, d.model);
    if (d.provider === 'template') return base;
    return `${base} · ${(d.latencyMs / 1000).toFixed(1)}s${d.tokens ? ` · ${d.tokens} tokens` : ''}`;
  });
  protected readonly rating = computed(() => this.ratings()[this.ratingKey(this.index())] ?? null);

  constructor() {
    // A new draft (first load, a regenerate, or a band change) streams in;
    // paging back through the history shows a draft at once.
    effect(() => {
      const drafts = this.drafts();
      const band = this.band();
      untracked(() => {
        const bandChanged = band !== this.seenBand;
        if (drafts.length === 0) return;
        if (drafts.length > this.seenCount || bandChanged) {
          this.seenCount = drafts.length;
          this.seenBand = band;
          this.index.set(drafts.length - 1);
          this.playCurrent();
        }
      });
    });
    effect(() => {
      if (this.regenerating()) {
        untracked(() => {
          this.summary.thinking();
          this.note.set('');
        });
      }
    });
  }

  ngOnDestroy(): void {
    this.summary.stop();
  }

  protected go(dir: number): void {
    const next = this.index() + dir;
    if (next < 0 || next >= this.drafts().length) return;
    this.index.set(next);
    this.note.set('');
    this.showColumns.set(true);
    this.summary.show(this.leadText());
  }

  private playCurrent(): void {
    this.showColumns.set(false);
    this.note.set('');
    this.summary.play(this.leadText(), () => this.showColumns.set(true));
  }

  private leadText(): string {
    const d = this.current();
    return d ? String(d.content[this.summaryKey()] ?? '') : '';
  }

  private ratingKey(i: number): string {
    return `${this.band() ?? ''}:${i}`;
  }

  protected rate(r: 'UP' | 'DOWN'): void {
    const i = this.index();
    const key = this.ratingKey(i);
    const next = this.ratings()[key] === r ? null : r;
    this.ratings.update((all) => {
      const copy = { ...all };
      if (next) copy[key] = next; else delete copy[key];
      return copy;
    });
    this.note.set(next === null ? '' : next === 'UP' ? 'Thanks — noted.' : 'Thanks — try Regenerate for a different take, or ask a follow-up.');
    this.api.feedback(this.sessionId(), this.target(), this.band(), i, next).subscribe({
      error: () => this.note.set("Couldn't save that — try again."),
    });
  }

  protected copy(): void {
    const d = this.current();
    if (!d) return;
    const text = [this.leadText(), ...this.columns().map((c) => `\n${c.title}:\n- ${c.items.join('\n- ')}`)].join('\n');
    if (!navigator.clipboard?.writeText) {
      this.note.set("Couldn't copy here.");
      return;
    }
    navigator.clipboard.writeText(text).then(
      () => this.note.set('Copied to clipboard.'),
      () => this.note.set("Couldn't copy here."));
  }
}
