import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { describeProblem } from '../core/problem';

@Component({
  selector: 'mc-login',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="mc-shell">
      <h1>Sign in</h1>
      <p class="mc-muted">Pick up where you left off, or see a past result.</p>

      @if (error()) {
        <p class="mc-error" role="alert">{{ error() }}</p>
      }

      <form (ngSubmit)="submit()">
        <label for="email">Email</label>
        <input id="email" name="email" type="email" autocomplete="email"
               [(ngModel)]="email" required />

        <label for="password">Password</label>
        <input id="password" name="password" type="password" autocomplete="current-password"
               [(ngModel)]="password" required />

        <button type="submit" [disabled]="busy()">
          {{ busy() ? 'Signing in' : 'Sign in' }}
        </button>
      </form>

      <p class="mc-muted">
        No account yet? <a routerLink="/register">Create one</a>.
      </p>
    </div>
  `,
  styles: `
    form { display: grid; gap: 0.35rem; max-width: 22rem; margin-bottom: 1.5rem; }
    label { font-size: 0.85rem; color: var(--mc-muted); margin-top: 0.75rem; }
    input {
      font: inherit;
      padding: 0.6rem 0.7rem;
      border: 1px solid var(--mc-rule);
      border-radius: 3px;
      background: var(--mc-surface);
      color: var(--mc-ink);
    }
    button[type='submit'] {
      margin-top: 1.25rem;
      font: inherit;
      padding: 0.65rem 1.2rem;
      border: none;
      border-radius: 3px;
      background: var(--mc-teal);
      color: #fff;
      cursor: pointer;
    }
    button[disabled] { opacity: 0.6; cursor: default; }
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  email = '';
  password = '';
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  submit(): void {
    this.error.set(null);
    this.busy.set(true);

    this.auth.login(this.email, this.password).subscribe({
      next: () => {
        const next = this.route.snapshot.queryParamMap.get('next') ?? '/start';
        void this.router.navigateByUrl(next);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(describeProblem(err));
      },
    });
  }
}
