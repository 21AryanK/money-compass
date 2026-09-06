import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ProfileType } from '../core/api.types';
import { describeProblem } from '../core/problem';

/**
 * Register and profile selection are one screen rather than two. The backend
 * requires profileType at registration, so a separate /profile-select route
 * would only be a second page asking for a field the first page already needs.
 */
@Component({
  selector: 'mc-register',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="mc-shell">
      <h1>Where are you starting from?</h1>
      <p class="mc-muted">
        The questions you see depend on this. A student gets asked about first
        investments; someone near retirement gets asked about withdrawal rates.
      </p>

      @if (error()) {
        <p class="mc-error" role="alert">{{ error() }}</p>
      }

      <form (ngSubmit)="submit()">
        <fieldset>
          <legend>Your situation</legend>
          @for (option of profiles; track option.value) {
            <label class="choice">
              <input type="radio" name="profileType" [value]="option.value"
                     [(ngModel)]="profileType" required />
              <span>
                <strong>{{ option.label }}</strong>
                <em>{{ option.hint }}</em>
              </span>
            </label>
          }
        </fieldset>

        <label for="email">Email</label>
        <input id="email" name="email" type="email" autocomplete="email"
               [(ngModel)]="email" required />

        <label for="password">Password</label>
        <input id="password" name="password" type="password" autocomplete="new-password"
               [(ngModel)]="password" required />
        <small class="mc-muted">At least 12 characters.</small>

        <button type="submit" [disabled]="busy()">
          {{ busy() ? 'Creating account' : 'Create account' }}
        </button>
      </form>

      <p class="mc-muted">
        Already have an account? <a routerLink="/login">Sign in</a>.
      </p>
    </div>
  `,
  styles: `
    form { display: grid; gap: 0.35rem; max-width: 26rem; margin-bottom: 1.5rem; }
    fieldset { border: none; padding: 0; margin: 0 0 1rem; display: grid; gap: 0.5rem; }
    legend { font-size: 0.85rem; color: var(--mc-muted); padding: 0; margin-bottom: 0.5rem; }
    .choice {
      display: flex;
      gap: 0.7rem;
      align-items: flex-start;
      padding: 0.7rem 0.8rem;
      border: 1px solid var(--mc-rule);
      border-radius: 3px;
      cursor: pointer;
    }
    .choice:has(input:checked) { border-color: var(--mc-teal); background: var(--mc-surface-alt); }
    .choice span { display: grid; }
    .choice em { font-style: normal; font-size: 0.85rem; color: var(--mc-muted); }
    label { font-size: 0.85rem; color: var(--mc-muted); margin-top: 0.75rem; }
    label.choice { font-size: 1rem; color: inherit; margin-top: 0; }
    input[type='email'], input[type='password'] {
      font: inherit;
      padding: 0.6rem 0.7rem;
      border: 1px solid var(--mc-rule);
      border-radius: 3px;
      background: var(--mc-surface);
      color: var(--mc-ink);
    }
    small { font-size: 0.8rem; }
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
export class RegisterPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  email = '';
  password = '';
  profileType: ProfileType = 'STUDENT';

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly profiles: { value: ProfileType; label: string; hint: string }[] = [
    { value: 'STUDENT', label: 'Studying', hint: 'Little or no regular income yet' },
    { value: 'PROFESSIONAL', label: 'Working', hint: 'Earning, with EPF or PPF in the picture' },
    { value: 'RETIREE', label: 'Retired or close to it', hint: 'Living off savings, or about to' },
  ];

  submit(): void {
    this.error.set(null);
    this.busy.set(true);

    this.auth.register(this.email, this.password, this.profileType).subscribe({
      next: () => void this.router.navigate(['/start']),
      error: (err) => {
        this.busy.set(false);
        this.error.set(describeProblem(err));
      },
    });
  }
}
