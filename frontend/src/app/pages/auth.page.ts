import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ProfileType } from '../core/api.types';
import { describeProblem } from '../core/problem';
import { ProfilePickerComponent } from '../shared/profile-picker.component';
import { ThemeToggleComponent } from '../shell/theme-toggle.component';
import { AiStatusComponent } from '../shell/ai-status.component';

/**
 * Sign in and create an account, in one card like the prototype. Registering
 * asks which profile you are in the same step, because the backend needs it
 * to create the account: it decides which questions you get and how the plan
 * is weighted. Signing in goes straight to the dashboard.
 */
@Component({
  selector: 'mc-auth',
  imports: [FormsModule, RouterLink, ProfilePickerComponent, ThemeToggleComponent, AiStatusComponent],
  template: `
    <div class="auth-wrap">
      <mc-theme-toggle />
      <div class="glass auth-card" [class.auth-card--wide]="isRegister()">
        <div class="auth-logo">
          <svg width="30" height="30" viewBox="0 0 32 32" fill="none" aria-hidden="true"><circle cx="16" cy="16" r="14.5" stroke="#d9a54a" stroke-width="1.5"/><path d="M21 11L14.5 14.5L11 21L17.5 17.5L21 11Z" fill="#d9a54a"/><circle cx="16" cy="16" r="1.6" fill="#0a1210"/></svg>
          <span>Money Compass</span>
        </div>

        @if (isRegister()) {
          <h2>Create your account</h2>
          <p class="auth-sub">Two minutes, then straight into your adaptive assessment. First: which of these are you right now? It changes which questions you get and how we suggest you invest.</p>
        } @else {
          <h2>Welcome back</h2>
          <p class="auth-sub">Sign in to pick up your financial literacy assessment.</p>
        }

        @if (error()) {
          <p class="app-error" role="alert" style="margin-bottom:14px;">{{ error() }}</p>
        }

        <form class="auth-form" (ngSubmit)="submit()">
          @if (isRegister()) {
            <mc-profile-picker [(value)]="profileType" [disabled]="busy()" />
          }
          <div class="field">
            <label for="auth-email">Email</label>
            <input id="auth-email" name="email" type="email" autocomplete="email" placeholder="you@example.com"
                   [(ngModel)]="email" required [disabled]="busy()" />
          </div>
          <div class="field">
            <label for="auth-password">Password</label>
            <input id="auth-password" name="password" type="password"
                   [attr.autocomplete]="isRegister() ? 'new-password' : 'current-password'"
                   [placeholder]="isRegister() ? 'At least 12 characters' : '••••••••••••'"
                   [(ngModel)]="password" required [disabled]="busy()" />
          </div>
          <button type="submit" class="btn btn-primary btn-block" [disabled]="busy() || (isRegister() && !profileType())">
            {{ busy() ? (isRegister() ? 'Creating your account…' : 'Signing in…') : (isRegister() ? 'Create account' : 'Sign in') }}
          </button>
        </form>

        <div class="auth-toggle">
          @if (isRegister()) {
            <span>Already have one?</span> <a routerLink="/login">Sign in</a>
          } @else {
            <span>New here?</span> <a routerLink="/register">Create an account</a>
          }
        </div>

        <div class="auth-status"><mc-ai-status /></div>
      </div>
      <footer class="site-footer">Built with <span role="img" aria-label="love">❤️</span> in India by <a href="https://www.linkedin.com/in/aryan-kumar-3369041aa/" target="_blank" rel="noopener noreferrer" aria-label="@Aryan on LinkedIn (opens in a new tab)">@Aryan</a></footer>
    </div>
  `,
})
export class AuthPage implements OnInit {

  /** "login" or "register", from the route. */
  readonly mode = input<'login' | 'register'>('login');

  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly isRegister = computed(() => this.mode() === 'register');
  protected email = '';
  protected password = '';
  protected readonly profileType = signal<ProfileType | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    if (this.auth.isAuthenticated()) void this.router.navigate(['/dashboard']);
  }

  protected submit(): void {
    this.error.set(null);
    this.busy.set(true);
    const request = this.isRegister()
      ? this.auth.register(this.email, this.password, this.profileType()!)
      : this.auth.login(this.email, this.password);
    request.subscribe({
      next: () => {
        const next = this.route.snapshot.queryParamMap.get('next');
        void this.router.navigateByUrl(next && next.startsWith('/') ? next : '/dashboard');
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(describeProblem(err));
      },
    });
  }
}
