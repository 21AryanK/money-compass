import { Component, ElementRef, HostListener, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { AiHealthResponse } from '../core/api.types';
import { AssessmentService } from '../core/assessment.service';

/**
 * The rail's model-status pill: which model writes the explanations, and a
 * live probe of whether it's reachable. Opening the popover probes again, so
 * the latency and "last checked" shown are real.
 */
@Component({
  selector: 'mc-ai-status',
  template: `
    <div style="position:relative;">
      <button type="button" class="ai-pill" style="width:100%; cursor:pointer;" (click)="toggle()"
              [attr.aria-expanded]="open()" aria-controls="ai-popover">
        <span class="dot" [class.dot-checking]="checking()" [style.background]="dotColour()"></span>
        <span class="pill-text">{{ pillText() }}</span>
      </button>
      <div class="glass ai-popover" id="ai-popover" [class.open]="open()">
        <h4>Provider health</h4>
        @if (health(); as h) {
          <div class="ai-popover-row"><span>Primary</span><b>{{ h.primary.provider }} · {{ h.primary.up ? 'up' : 'down' }}</b></div>
          <div class="ai-popover-row"><span>Model</span><b>{{ h.primary.model }}</b></div>
          <div class="ai-popover-row"><span>Fallback</span><b>{{ h.fallback.provider }}{{ h.fallback.provider === 'none' ? '' : ' · ' + (h.fallback.up ? 'up' : 'down') }}</b></div>
          <div class="ai-popover-row"><span>Circuit</span><b>{{ h.circuitState.toLowerCase() }}</b></div>
          <div class="ai-popover-row"><span>Probe latency</span><b>{{ h.primary.probeMs !== null ? h.primary.probeMs + 'ms' : '—' }}</b></div>
          <div class="ai-popover-row"><span>Last checked</span><b>{{ checkedAgo() }}</b></div>
          @if (!h.primary.up) {
            <p style="font-size:11.5px; color:var(--ink-3); margin-top:8px;">
              Scores and plans don't need the model. Explanations are written from your numbers until it's back.
            </p>
          }
        } @else {
          <div class="ai-popover-row"><span>Status</span><b>{{ error() ? 'unreachable' : 'checking…' }}</b></div>
        }
      </div>
    </div>
  `,
})
export class AiStatusComponent implements OnInit, OnDestroy {

  private readonly api = inject(AssessmentService);
  private readonly host = inject(ElementRef<HTMLElement>);

  protected readonly health = signal<AiHealthResponse | null>(null);
  protected readonly error = signal(false);
  protected readonly checking = signal(true);
  protected readonly open = signal(false);
  private readonly now = signal(Date.now());
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.probe();
    // Re-probe every minute; the pill should notice a model going down.
    this.timer = setInterval(() => {
      this.now.set(Date.now());
      if (this.now() - this.lastProbe > 60_000) this.probe();
    }, 5_000);
  }

  ngOnDestroy(): void {
    if (this.timer) clearInterval(this.timer);
  }

  private lastProbe = 0;

  private probe(): void {
    this.lastProbe = Date.now();
    this.checking.set(true);
    this.api.aiHealth().subscribe({
      next: (h) => { this.health.set(h); this.error.set(false); this.checking.set(false); this.now.set(Date.now()); },
      error: () => { this.error.set(true); this.checking.set(false); },
    });
  }

  protected toggle(): void {
    const opening = !this.open();
    this.open.set(opening);
    if (opening) this.probe();
  }

  @HostListener('document:click', ['$event'])
  protected outside(e: MouseEvent): void {
    if (!this.host.nativeElement.contains(e.target as Node)) this.open.set(false);
  }

  @HostListener('document:keydown.escape')
  protected escape(): void {
    this.open.set(false);
  }

  protected pillText(): string {
    const h = this.health();
    if (!h) return this.error() ? 'AI status unknown' : 'Checking the model…';
    return `${capitalise(h.primary.provider)} · ${h.primary.model}${h.primary.up ? '' : ' (offline)'}`;
  }

  protected dotColour(): string | null {
    const h = this.health();
    if (!h || this.checking()) return null;
    return h.primary.up ? null : 'var(--status-warn)';
  }

  protected checkedAgo(): string {
    const h = this.health();
    if (!h) return '—';
    const secs = Math.round((this.now() - new Date(h.checkedAt).getTime()) / 1000);
    return secs < 2 ? 'just now' : `${secs}s ago`;
  }
}

function capitalise(s: string): string {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}
