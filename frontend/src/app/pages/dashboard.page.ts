import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ProfileType, BANDS, SessionSummary } from '../core/api.types';
import { AssessmentService } from '../core/assessment.service';
import { AuthService } from '../core/auth.service';
import { SessionStore } from '../core/session.store';
import { PROFILE_LABELS, PROFILE_NOUNS, bandLabel, capitalize, shortDate } from '../core/format';
import { describeProblem } from '../core/problem';
import { ProfilePickerComponent } from '../shared/profile-picker.component';

const ALLOCATIONS: Record<string, [number, number, number, number]> = {
  CONSERVATIVE: [15, 60, 15, 10], MODERATE: [30, 50, 10, 10], BALANCED: [45, 35, 10, 10],
  GROWTH: [65, 20, 10, 5], AGGRESSIVE: [80, 10, 5, 5],
};

function scoreTitle(total: number): string {
  if (total < 40) return 'Just getting started';
  if (total < 60) return 'Building the basics';
  if (total < 80) return 'Solid foundation';
  return 'Strong literacy';
}

/**
 * The overview: start or resume an assessment, see the latest score and band
 * with the change since the previous one, and change profile.
 */
@Component({
  selector: 'mc-dashboard',
  imports: [ProfilePickerComponent],
  template: `
    <section class="stage">
      <div class="glass hero-card">
        <div>
          <h2>Know where your money habits actually stand.</h2>
          <p>A short adaptive assessment, a deterministic literacy score, and an AI-written explanation of what it means — with a risk profile and suggested allocation to follow.</p>
          <p class="dash-profile-line">Your questions are set up for a <b>{{ profileNoun() }}</b>.</p>
        </div>
        <div class="hero-actions">
          <button class="btn btn-primary" (click)="startNew()" [disabled]="busy()">Start assessment</button>
          <button class="btn btn-ghost" type="button" (click)="openPicker()">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M17 3l4 4-4 4"/><path d="M21 7H9a4 4 0 00-4 4"/><path d="M7 21l-4-4 4-4"/><path d="M3 17h12a4 4 0 004-4"/></svg>
            Change profile
          </button>
        </div>
      </div>

      @if (error()) {
        <p class="app-error" role="alert" style="margin-top:16px;">{{ error() }}</p>
      }

      @if (picking()) {
        <div class="glass card" style="margin-top:16px;">
          <h3 style="margin-bottom:6px;">Which of these are you right now?</h3>
          <p style="font-size:13px; color:var(--ink-3); margin-bottom:12px;">
            Your next assessment will use this profile's questions and plan. Earlier results stay in your history as the profile they were taken as.
          </p>
          <mc-profile-picker [(value)]="pickedProfile" [disabled]="busy()" />
          <div class="resume-actions" style="margin-top:14px;">
            <button type="button" class="btn btn-primary btn-sm" (click)="saveProfile()" [disabled]="busy() || !pickedProfile()">Save profile</button>
            <button type="button" class="btn btn-ghost btn-sm" (click)="picking.set(false)">Keep my current profile</button>
          </div>
        </div>
      }

      @if (unfinished(); as u) {
        <div class="glass card resume-banner" style="margin-top:16px;">
          <div>
            <h3>Finish your assessment</h3>
            <p>You've answered {{ u.answered }} question{{ u.answered === 1 ? '' : 's' }} (started {{ date(u.startedAt) }}). Carry on from where you stopped.</p>
          </div>
          <div class="resume-actions">
            <button type="button" class="btn btn-primary btn-sm" (click)="resume(u.sessionId)">Resume</button>
            <button type="button" class="btn btn-ghost btn-sm" (click)="startNew()" [disabled]="busy()">Start over</button>
          </div>
        </div>
      }

      <div class="section-title">Your last session</div>
      @if (store.sessions() === null) {
        <div class="glass card loading-card"><div class="orb"></div><div>Loading your history…</div></div>
      } @else if (latest(); as l) {
        <div class="grid grid-3">
          <div class="glass card stat-tile" role="link" tabindex="0" style="cursor:pointer;" (click)="openResults(l.sessionId, 'score')" (keydown.enter)="openResults(l.sessionId, 'score')">
            <span class="sample-tag">{{ tag(l) }}</span>
            <span class="label">Literacy score</span>
            <span class="value">{{ l.total }}<span style="font-size:16px; color:var(--ink-3);">/100</span></span>
            <div class="meter-track"><div class="meter-fill" [style.width.%]="l.total"></div></div>
            <p class="dash-note" [class.up]="delta() !== null && delta()! > 0" [class.down]="delta() !== null && delta()! < 0" [class.same]="delta() === 0">{{ scoreNote(l) }}</p>
          </div>
          <div class="glass card stat-tile" role="link" tabindex="0" style="cursor:pointer;" (click)="openResults(l.sessionId, 'risk')" (keydown.enter)="openResults(l.sessionId, 'risk')">
            <span class="sample-tag">{{ tag(l) }}</span>
            <span class="label">Risk band</span>
            <span class="value" style="font-size:26px;">{{ band(l.band) }}</span>
            <div class="meter-track"><div class="meter-fill" [style.width.%]="bandWidth(l)" style="background: linear-gradient(90deg, #3987e5, #c98500);"></div></div>
            <p class="dash-note">{{ bandNote(l) }}</p>
          </div>
          <div class="glass card stat-tile">
            <span class="sample-tag">All time</span>
            <span class="label">Assessments completed</span>
            <span class="value">{{ completed().length }}</span>
            <div class="meter-track"><div class="meter-fill" [style.width.%]="countWidth()"></div></div>
            <p class="dash-note">{{ countNote() }}</p>
          </div>
        </div>
      } @else {
        <div class="glass card dash-empty">
          <div class="dash-empty-icon" aria-hidden="true">
            <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11"/></svg>
          </div>
          <div>
            <h3>{{ unfinished() ? 'Finish your assessment to see your results' : "You haven't taken the assessment yet" }}</h3>
            <p>{{ unfinished() ? emptyUnfinished : emptyFresh }}</p>
          </div>
          @if (!unfinished()) {
            <button class="btn btn-primary" (click)="startNew()" [disabled]="busy()">Take the assessment</button>
          }
        </div>
      }

      <div class="section-title">How scoring works here</div>
      <div class="grid grid-2">
        <div class="glass card">
          <h3>Deterministic core</h3>
          <p style="color:var(--ink-2); font-size:13.5px;">Your 0–100 score, risk band and investment plan are computed from your answers alone, in plain code — the same inputs always produce the same numbers. No model is involved in them.</p>
        </div>
        <div class="glass card">
          <h3>AI at the edges</h3>
          <p style="color:var(--ink-2); font-size:13.5px;">A language model writes the plain-language explanation on top of your numbers — your strengths, gaps and next steps — and answers your questions in Ask Compass, using only figures computed from your answers.</p>
        </div>
      </div>
    </section>
  `,
})
export class DashboardPage implements OnInit {

  protected readonly store = inject(SessionStore);
  private readonly api = inject(AssessmentService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly picking = signal(false);
  protected readonly pickedProfile = signal<ProfileType | null>(null);

  protected readonly profileNoun = computed(() => {
    const p = this.auth.user()?.profileType;
    return p ? PROFILE_NOUNS[p] : '…';
  });

  /** Finished sessions with a score, newest first. */
  protected readonly completed = computed(() =>
    (this.store.sessions() ?? []).filter((s) => s.status === 'COMPLETED' && s.total !== null));

  /** The latest result for the current profile, else the latest of any. */
  protected readonly latest = computed(() => {
    const profile = this.auth.user()?.profileType;
    const done = this.completed();
    return done.find((s) => s.profileType === profile) ?? done[0] ?? null;
  });

  protected readonly unfinished = computed(() => {
    const u = this.store.inProgress();
    return u && u.profileType === this.auth.user()?.profileType ? u : null;
  });

  /** Change since the previous result taken as the same profile. */
  protected readonly delta = computed(() => {
    const l = this.latest();
    if (!l) return null;
    const previous = this.completed().filter((s) => s.profileType === l.profileType && s.sessionId !== l.sessionId)
      .find((s) => new Date(s.startedAt) < new Date(l.startedAt));
    return previous ? l.total! - previous.total! : null;
  });

  ngOnInit(): void {
    this.store.refreshSessions().subscribe({ error: (err) => this.error.set(describeProblem(err)) });
  }

  protected date(iso: string): string {
    return shortDate(iso);
  }

  protected band(b: string | null): string {
    return bandLabel(b as never);
  }

  protected tag(l: SessionSummary): string {
    return l.profileType !== this.auth.user()?.profileType
      ? `As ${PROFILE_LABELS[l.profileType].toLowerCase()}`
      : `Latest · ${shortDate(l.completedAt ?? l.startedAt)}`;
  }

  protected scoreNote(l: SessionSummary): string {
    const d = this.delta();
    if (d !== null) return d === 0 ? 'Same as your previous assessment' : `${d > 0 ? '▲' : '▼'} ${Math.abs(d)} since your previous assessment`;
    return l.profileType !== this.auth.user()?.profileType ? `Taken on ${shortDate(l.completedAt ?? l.startedAt)}` : scoreTitle(l.total!);
  }

  protected bandWidth(l: SessionSummary): number {
    return l.band ? ((BANDS.indexOf(l.band) + 1) / BANDS.length) * 100 : 0;
  }

  protected bandNote(l: SessionSummary): string {
    if (!l.band) return 'Open your results to assess risk';
    const [e, d, g, c] = ALLOCATIONS[l.band];
    return `Equity ${e}% · debt ${d}% · gold ${g}% · cash ${c}%`;
  }

  protected countWidth(): number {
    return Math.min(100, this.completed().length * 20);
  }

  protected countNote(): string {
    const done = this.completed();
    if (done.length <= 1) return 'Retake it every few months to track your progress';
    return `Since ${shortDate(done[done.length - 1].startedAt)}`;
  }

  protected startNew(): void {
    this.busy.set(true);
    this.api.start().subscribe({
      next: (res) => {
        this.busy.set(false);
        this.store.refreshSessions().subscribe();
        void this.router.navigate(['/assessment', res.sessionId]);
      },
      error: (err) => { this.busy.set(false); this.error.set(describeProblem(err)); },
    });
  }

  protected resume(sessionId: string): void {
    void this.router.navigate(['/assessment', sessionId]);
  }

  protected openResults(sessionId: string, screen: 'score' | 'risk'): void {
    this.store.useSession(sessionId);
    void this.router.navigate(['/results', sessionId, screen]);
  }

  protected openPicker(): void {
    this.pickedProfile.set(this.auth.user()?.profileType ?? null);
    this.picking.set(true);
  }

  protected saveProfile(): void {
    const p = this.pickedProfile();
    if (!p) return;
    if (p === this.auth.user()?.profileType) {
      this.picking.set(false);
      return;
    }
    this.busy.set(true);
    this.auth.changeProfile(p).subscribe({
      next: () => { this.busy.set(false); this.picking.set(false); },
      error: (err) => { this.busy.set(false); this.error.set(describeProblem(err)); },
    });
  }

  protected readonly capitalize = capitalize;
  protected readonly emptyUnfinished =
    "Your score, risk profile and investment plan will appear here once you've answered the remaining questions.";
  protected readonly emptyFresh =
    'Answer a few questions — about 25, and "I don\'t know" is always an option — to see your literacy score, your risk profile and a personal investment plan here.';
}
