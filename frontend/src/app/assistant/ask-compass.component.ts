import {
  Component, ElementRef, HostListener, OnDestroy, effect, inject, input, signal, untracked, viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ChatTurn } from '../core/api.types';
import { AssessmentService } from '../core/assessment.service';
import { SessionStore } from '../core/session.store';
import { providerLabel } from '../core/format';
import { describeProblem } from '../core/problem';
import { AskBus } from './ask-bus';

interface Message {
  who: 'user' | 'bot';
  text: string;
  streaming?: boolean;
  meta?: string;
}

/**
 * Ask Compass: a chat about this session's results. The server answers from
 * the user's own numbers — the model writes the reply, the figures in it are
 * computed by the application — and suggests follow-ups. The reply arrives
 * whole and is paced onto the screen like a stream.
 *
 * On phones the panel is a full-screen sheet sized to the visible viewport,
 * so the on-screen keyboard never covers the input.
 */
@Component({
  selector: 'mc-ask-compass',
  imports: [FormsModule],
  template: `
    <button type="button" class="ask-fab visible" (click)="toggle()" [attr.aria-expanded]="open()" aria-controls="ask-panel" aria-label="Ask Compass" #fab>
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3l1.8 4.9L19 9.7l-5.2 1.8L12 16.4l-1.8-4.9L5 9.7l5.2-1.8z"/><path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z"/></svg>
      <span class="ask-fab-label">Ask Compass</span>
    </button>
    <div class="glass ask-panel" id="ask-panel" role="dialog" aria-label="Ask Compass" [class.open]="open()" #panel>
      <div class="ask-head">
        <span class="orb-sm" aria-hidden="true"></span>
        <div><h4>Ask Compass</h4><div class="sub">{{ modelLabel() }} · grounded in your answers</div></div>
        <button type="button" class="ask-close" (click)="close()" aria-label="Close">×</button>
      </div>
      <div class="ask-log" aria-live="polite" #log>
        @for (m of messages(); track $index) {
          <div class="chat-bubble" [class.bot]="m.who === 'bot'" [class.user]="m.who === 'user'" [class.streaming-cursor]="m.streaming">{{ m.text }}</div>
          @if (m.meta) { <div class="chat-meta">{{ m.meta }}</div> }
        }
        @if (typing()) {
          <div class="typing" aria-label="Compass is writing"><span></span><span></span><span></span></div>
        }
        @if (chips().length && !busy()) {
          <div class="chat-chips">
            @for (c of chips(); track c) { <button type="button" class="chat-chip" (click)="ask(c)">{{ c }}</button> }
          </div>
        }
      </div>
      <form class="ask-form" (ngSubmit)="submit()" autocomplete="off">
        <input #input name="q" type="text" placeholder="Ask about your score, risk or plan…" aria-label="Your question" maxlength="300"
               [(ngModel)]="draft" [disabled]="!ready()" />
        <button type="submit" [disabled]="busy() || !ready() || !draft.trim()">Send</button>
      </form>
      <div class="ask-foot">Educational answers from your own numbers — not financial advice.</div>
    </div>
  `,
})
export class AskCompassComponent implements OnDestroy {

  readonly sessionId = input.required<string>();
  /** "score", "risk" or "invest": which screen the chat was opened from. */
  readonly screen = input<string>('score');

  private readonly api = inject(AssessmentService);
  private readonly store = inject(SessionStore);
  private readonly bus = inject(AskBus);
  private readonly panel = viewChild.required<ElementRef<HTMLElement>>('panel');
  private readonly logEl = viewChild.required<ElementRef<HTMLElement>>('log');
  private readonly inputEl = viewChild.required<ElementRef<HTMLInputElement>>('input');
  private readonly fab = viewChild.required<ElementRef<HTMLButtonElement>>('fab');

  protected readonly open = signal(false);
  protected readonly messages = signal<Message[]>([]);
  protected readonly chips = signal<string[]>([]);
  protected readonly typing = signal(false);
  protected readonly busy = signal(false);
  protected readonly ready = signal(false);
  protected readonly modelLabel = signal('connecting');
  protected draft = '';

  private conversationFor: string | null = null;
  private asked = new Set<string>();
  private pending: string | null = null;
  private streamTimer?: ReturnType<typeof setTimeout>;

  constructor() {
    // "Ask a follow-up →" on the AI cards.
    effect(() => {
      const req = this.bus.request();
      if (!req) return;
      untracked(() => {
        this.show();
        if (req.question) {
          if (this.ready()) this.ask(req.question);
          else this.pending = req.question;
        }
      });
    });
    // A different session (retake, history) starts a new conversation.
    effect(() => {
      const id = this.sessionId();
      untracked(() => {
        if (this.conversationFor && this.conversationFor !== id) this.reset();
      });
    });
    if (typeof window !== 'undefined' && window.visualViewport) {
      window.visualViewport.addEventListener('resize', this.fit);
      window.visualViewport.addEventListener('scroll', this.fit);
    }
  }

  ngOnDestroy(): void {
    document.body.classList.remove('ask-locked');
    if (this.streamTimer) clearTimeout(this.streamTimer);
    window.visualViewport?.removeEventListener('resize', this.fit);
    window.visualViewport?.removeEventListener('scroll', this.fit);
  }

  private isPhone(): boolean {
    return window.matchMedia('(max-width: 760px)').matches;
  }

  protected toggle(): void {
    if (this.open()) this.close(); else this.show();
  }

  private show(): void {
    this.open.set(true);
    // On phones the panel covers the page: stop it scrolling behind, and
    // don't pop the keyboard over the suggestions until the input is tapped.
    document.body.classList.toggle('ask-locked', this.isPhone());
    if (this.conversationFor !== this.sessionId()) this.start();
    setTimeout(() => {
      this.fit();
      if (!this.isPhone()) this.inputEl().nativeElement.focus();
      this.scroll();
    });
  }

  protected close(): void {
    this.open.set(false);
    document.body.classList.remove('ask-locked');
  }

  @HostListener('document:keydown.escape')
  protected escape(): void {
    if (this.open()) {
      this.close();
      this.fab().nativeElement.focus();
    }
  }

  /** Keeps the phone sheet within the visible viewport as the keyboard opens and closes. */
  private readonly fit = () => {
    const panel = this.panel()?.nativeElement;
    const vv = window.visualViewport;
    if (!panel) return;
    if (!this.open() || !this.isPhone() || !vv) {
      panel.style.height = '';
      panel.style.top = '';
      return;
    }
    panel.style.top = `${vv.offsetTop + 12}px`;
    panel.style.height = `${vv.height - 24}px`;
    this.scroll();
  };

  private reset(): void {
    this.messages.set([]);
    this.chips.set([]);
    this.asked.clear();
    this.conversationFor = null;
    this.ready.set(false);
  }

  private start(): void {
    this.conversationFor = this.sessionId();
    this.ready.set(false);
    this.typing.set(true);
    this.api.chatIntro(this.sessionId(), this.screen()).subscribe({
      next: (intro) => {
        this.typing.set(false);
        this.modelLabel.set(`${intro.provider} · ${intro.model}`);
        this.messages.set([{ who: 'bot', text: intro.greeting }]);
        this.chips.set(intro.suggestions);
        this.ready.set(true);
        if (this.pending) {
          const q = this.pending;
          this.pending = null;
          this.ask(q);
        }
      },
      error: (err) => {
        this.typing.set(false);
        this.conversationFor = null;
        this.messages.set([{ who: 'bot', text: `I couldn't load your results just now. ${describeProblem(err)}` }]);
      },
    });
  }

  protected submit(): void {
    const q = this.draft.trim();
    if (!q || this.busy()) return;
    this.draft = '';
    this.ask(q);
  }

  protected ask(question: string): void {
    if (this.busy() || !this.ready()) return;
    const history: ChatTurn[] = this.messages().slice(1)
      .map((m) => ({ role: m.who === 'user' ? 'user' as const : 'assistant' as const, content: m.text }));
    this.asked.add(question.toLowerCase());
    this.chips.set([]);
    this.messages.update((m) => [...m, { who: 'user', text: question }]);
    this.busy.set(true);
    this.typing.set(true);
    this.scroll();

    const plan = this.store.planInputs();
    const started = performance.now();
    this.api.chat(this.sessionId(), {
      message: question, history, screen: this.screen(),
      planMonthly: plan.monthly ?? undefined, planYears: plan.years ?? undefined,
    }).subscribe({
      next: (res) => {
        this.typing.set(false);
        const seconds = ((performance.now() - started) / 1000).toFixed(1);
        const meta = res.provider === 'rules'
          ? `${providerLabel(res.provider, res.model)} · ${seconds}s`
          : `${res.model} · ${seconds}s${res.tokens ? ` · ${res.tokens} tokens` : ''}`;
        this.streamReply(res.reply, meta, res.followUps);
      },
      error: (err) => {
        this.typing.set(false);
        this.busy.set(false);
        this.messages.update((m) => [...m, { who: 'bot', text: `Sorry — that didn't go through. ${describeProblem(err)}` }]);
        this.scroll();
      },
    });
  }

  /** Paces the reply onto the screen word by word, then shows its details and follow-ups. */
  private streamReply(text: string, meta: string, followUps: string[]): void {
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    const words = text.split(' ');
    let i = reduced ? words.length : 0;
    this.messages.update((m) => [...m, { who: 'bot', text: reduced ? text : '', streaming: !reduced }]);
    const finish = () => {
      this.messages.update((m) => {
        const copy = m.slice();
        copy[copy.length - 1] = { who: 'bot', text, meta };
        return copy;
      });
      this.chips.set(followUps.filter((c) => !this.asked.has(c.toLowerCase())).slice(0, 4));
      this.busy.set(false);
      this.scroll();
    };
    if (reduced) {
      finish();
      return;
    }
    const step = () => {
      i++;
      this.messages.update((m) => {
        const copy = m.slice();
        copy[copy.length - 1] = { who: 'bot', text: words.slice(0, i).join(' '), streaming: true };
        return copy;
      });
      this.scroll();
      if (i < words.length) this.streamTimer = setTimeout(step, 18 + Math.random() * 30);
      else finish();
    };
    step();
  }

  private scroll(): void {
    setTimeout(() => {
      const el = this.logEl()?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }
}
