import { Component, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';

@Component({
  selector: 'mc-root',
  imports: [RouterOutlet, RouterLink],
  template: `
    <header class="masthead">
      <a routerLink="/" class="wordmark">
        <svg viewBox="0 0 32 32" width="22" height="22" aria-hidden="true">
          <circle cx="16" cy="16" r="15" fill="currentColor" opacity="0.12" />
          <path d="M16 4 L20.5 16 L16 28 L11.5 16 Z" fill="currentColor" />
        </svg>
        Money Compass
      </a>

      @if (auth.isAuthenticated()) {
        <nav>
          <a routerLink="/status">Model status</a>
          <button type="button" (click)="auth.logout()">Sign out</button>
        </nav>
      }
    </header>

    <main>
      <router-outlet />
    </main>
  `,
  styles: `
    .masthead {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 1rem 1.25rem;
      border-bottom: 1px solid var(--mc-rule);
      max-width: 46rem;
      margin: 0 auto;
    }

    .wordmark {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      font-family: Newsreader, Georgia, serif;
      font-size: 1.05rem;
      color: var(--mc-teal);
      text-decoration: none;
    }

    nav {
      display: flex;
      align-items: center;
      gap: 1rem;
      font-size: 0.9rem;
    }

    button {
      background: none;
      border: none;
      padding: 0;
      font: inherit;
      color: var(--mc-muted);
      cursor: pointer;
      text-decoration: underline;
      text-underline-offset: 2px;
    }
  `,
})
export class App {
  protected readonly auth = inject(AuthService);
}
