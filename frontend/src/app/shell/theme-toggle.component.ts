import { Component, computed, inject } from '@angular/core';
import { ThemeService } from '../core/theme.service';

@Component({
  selector: 'mc-theme-toggle',
  template: `
    <button type="button" class="glass theme-toggle" (click)="theme.toggle()" [attr.aria-label]="label()" [title]="label()">
      <svg class="icon-sun" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="4.2"/><path d="M12 2.5v2.2M12 19.3v2.2M4.6 4.6l1.6 1.6M17.8 17.8l1.6 1.6M2.5 12h2.2M19.3 12h2.2M4.6 19.4l1.6-1.6M17.8 6.2l1.6-1.6"/></svg>
      <svg class="icon-moon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20.5 14.2A8.5 8.5 0 019.8 3.5a8.5 8.5 0 1010.7 10.7z"/></svg>
    </button>
  `,
})
export class ThemeToggleComponent {
  protected readonly theme = inject(ThemeService);
  protected readonly label = computed(() => this.theme.theme() === 'dark' ? 'Switch to light mode' : 'Switch to dark mode');
}
