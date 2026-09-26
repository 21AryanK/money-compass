import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { AssessmentService } from '../core/assessment.service';
import { SessionStore } from '../core/session.store';
import { PROFILE_LABELS } from '../core/format';
import { describeProblem } from '../core/problem';
import { AiStatusComponent } from './ai-status.component';
import { ThemeToggleComponent } from './theme-toggle.component';
import { AskCompassComponent } from '../assistant/ask-compass.component';

/**
 * The signed-in layout: the rail (bottom bar on phones), the top bar with the
 * screen's title, theme toggle and profile, and Ask Compass on the results
 * screens. Titles come from each route's data.
 */
@Component({
  selector: 'mc-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, AiStatusComponent, ThemeToggleComponent, AskCompassComponent],
  template: `
    <div class="app">
      <nav class="rail glass" aria-label="Main">
        <div class="rail-brand">
          <svg width="26" height="26" viewBox="0 0 32 32" fill="none" aria-hidden="true"><circle cx="16" cy="16" r="14.5" stroke="#d9a54a" stroke-width="1.5"/><path d="M21 11L14.5 14.5L11 21L17.5 17.5L21 11Z" fill="#d9a54a"/><circle cx="16" cy="16" r="1.6" fill="#0a1210"/></svg>
          <span>Money Compass</span>
        </div>
        <div class="rail-nav">
          <a class="rail-link" routerLink="/dashboard" routerLinkActive #dash="routerLinkActive" [attr.aria-current]="dash.isActive ? 'page' : null">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="3.5" y="3.5" width="7.5" height="7.5" rx="1.6"/><rect x="13" y="3.5" width="7.5" height="7.5" rx="1.6"/><rect x="3.5" y="13" width="7.5" height="7.5" rx="1.6"/><rect x="13" y="13" width="7.5" height="7.5" rx="1.6"/></svg>
            <span><span class="full">Dashboard</span><span class="short">Home</span></span>
          </a>
          <button type="button" class="rail-link" (click)="openAssessment()" [attr.aria-current]="screen() === 'assessment' ? 'page' : null" [disabled]="starting()">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M5 6.5h14M5 12h14M5 17.5h9"/><circle cx="19.2" cy="17.5" r="1.4" fill="currentColor" stroke="none"/></svg>
            <span><span class="full">Assessment</span><span class="short">Assess</span></span>
          </button>
          <button type="button" class="rail-link" (click)="openResults('score')" [disabled]="!resultsId()" [attr.aria-current]="screen() === 'score' ? 'page' : null">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M4 20V11M11 20V4M18 20v-7"/></svg>
            <span><span class="full">Literacy score</span><span class="short">Score</span></span>
          </button>
          <button type="button" class="rail-link" (click)="openResults('risk')" [disabled]="!resultsId()" [attr.aria-current]="screen() === 'risk' ? 'page' : null">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M12 3l7 3.2v5.4c0 4.6-3 7.9-7 9.4-4-1.5-7-4.8-7-9.4V6.2L12 3z"/></svg>
            <span><span class="full">Risk profile</span><span class="short">Risk</span></span>
          </button>
          <button type="button" class="rail-link" (click)="openResults('plan')" [disabled]="!resultsId()" [attr.aria-current]="screen() === 'invest' ? 'page' : null">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M4 17L9.5 10.5L14 14.5L20 6.5"/><path d="M14.5 6.5H20V12"/></svg>
            <span><span class="full">Investment plan</span><span class="short">Invest</span></span>
          </button>
        </div>
        <div class="rail-spacer"></div>
        <mc-ai-status />
      </nav>

      <main class="main">
        <div class="topbar">
          <div class="eyebrow">
            <span class="eyebrow-label">{{ eyebrow() }}</span>
            <h1>{{ title() }}</h1>
          </div>
          <div class="topbar-actions">
            <mc-theme-toggle />
            <div class="glass profile-chip" [title]="auth.user()?.email ?? ''">
              <span class="avatar">{{ profileLabel().charAt(0) }}</span>
              <span>{{ profileLabel() }}</span>
            </div>
            <button type="button" class="glass theme-toggle" (click)="signOut()" aria-label="Sign out" title="Sign out">
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9"/></svg>
            </button>
          </div>
        </div>

        @if (error()) {
          <p class="app-error" role="alert" style="margin-bottom:16px;">{{ error() }}</p>
        }

        <router-outlet />

        @if (chatSessionId(); as sid) {
          <mc-ask-compass [sessionId]="sid" [screen]="screen()" />
        }

        <footer class="site-footer">Built with <span role="img" aria-label="love">❤️</span> in India by <a href="https://www.linkedin.com/in/aryan-kumar-3369041aa/" target="_blank" rel="noopener noreferrer" aria-label="@Aryan on LinkedIn (opens in a new tab)">@Aryan</a></footer>
      </main>
    </div>
  `,
})
export class ShellComponent implements OnInit {

  protected readonly auth = inject(AuthService);
  private readonly api = inject(AssessmentService);
  private readonly store = inject(SessionStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly eyebrow = signal('Overview');
  protected readonly title = signal('Dashboard');
  protected readonly screen = signal<string>('dashboard');
  private readonly routeSessionId = signal<string | null>(null);
  protected readonly starting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly profileLabel = computed(() => {
    const p = this.auth.user()?.profileType;
    return p ? PROFILE_LABELS[p] : '…';
  });

  /** The session the result links open: the one on screen, else the latest finished one. */
  protected readonly resultsId = computed(() => this.store.resultsSessionId() ?? this.store.latestCompleted()?.sessionId ?? null);

  /** Ask Compass lives on the results screens only. */
  protected readonly chatSessionId = computed(() =>
    ['score', 'risk', 'invest'].includes(this.screen()) ? this.routeSessionId() : null);

  ngOnInit(): void {
    this.auth.loadMe().subscribe({ error: () => { /* the interceptor handles a 401 */ } });
    this.store.refreshSessions().subscribe({ error: () => { /* the dashboard shows its own error */ } });
    this.syncFromRoute();
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => this.syncFromRoute());
  }

  private syncFromRoute(): void {
    let r = this.route;
    while (r.firstChild) r = r.firstChild;
    const data = r.snapshot.data;
    this.eyebrow.set(data['eyebrow'] ?? 'Overview');
    this.title.set(data['title'] ?? 'Dashboard');
    this.screen.set(data['screen'] ?? 'dashboard');
    this.routeSessionId.set(r.snapshot.paramMap.get('sessionId'));
    this.error.set(null);
  }

  /** Resumes an unfinished assessment, or starts a new one. */
  protected openAssessment(): void {
    const unfinished = this.store.inProgress();
    if (unfinished) {
      void this.router.navigate(['/assessment', unfinished.sessionId]);
      return;
    }
    this.starting.set(true);
    this.api.start().subscribe({
      next: (res) => {
        this.starting.set(false);
        this.store.refreshSessions().subscribe();
        void this.router.navigate(['/assessment', res.sessionId]);
      },
      error: (err) => { this.starting.set(false); this.error.set(describeProblem(err)); },
    });
  }

  protected openResults(screen: 'score' | 'risk' | 'plan'): void {
    const id = this.resultsId();
    if (id) void this.router.navigate(['/results', id, screen]);
  }

  protected signOut(): void {
    this.store.clear();
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
