import { Injectable, signal } from '@angular/core';

const KEY = 'money-compass-theme';

/**
 * Light / dark. Dark is the default; the choice is remembered in this
 * browser. index.html applies the saved theme before first paint, so this
 * service only reads what's already on <html> and flips it.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {

  readonly theme = signal<'dark' | 'light'>(
    document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark');

  toggle(): void {
    const next = this.theme() === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    this.theme.set(next);
    try {
      localStorage.setItem(KEY, next);
    } catch {
      // Storage unavailable (private window, blocked site data): the theme still applies for this visit.
    }
  }
}
