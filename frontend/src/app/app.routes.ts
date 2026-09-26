import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

/**
 * Sign-in screens stand alone; everything else renders inside the shell.
 * Each shell route's data sets the top bar's eyebrow and title, and `screen`
 * tells the shell which rail link is current and where Ask Compass appears.
 */
export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in - Money Compass',
    data: { mode: 'login' },
    loadComponent: () => import('./pages/auth.page').then((m) => m.AuthPage),
  },
  {
    path: 'register',
    title: 'Create an account - Money Compass',
    data: { mode: 'register' },
    loadComponent: () => import('./pages/auth.page').then((m) => m.AuthPage),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./shell/shell.component').then((m) => m.ShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        title: 'Dashboard - Money Compass',
        data: { eyebrow: 'Overview', title: 'Dashboard', screen: 'dashboard' },
        loadComponent: () => import('./pages/dashboard.page').then((m) => m.DashboardPage),
      },
      {
        path: 'assessment/:sessionId',
        title: 'Assessment - Money Compass',
        data: { eyebrow: 'Assessment', title: 'Adaptive questionnaire', screen: 'assessment' },
        loadComponent: () => import('./pages/questionnaire.page').then((m) => m.QuestionnairePage),
      },
      {
        path: 'analyzing/:sessionId',
        title: 'Putting your report together - Money Compass',
        data: { eyebrow: 'Assessment', title: 'Adaptive questionnaire', screen: 'assessment' },
        loadComponent: () => import('./pages/analyzing.page').then((m) => m.AnalyzingPage),
      },
      {
        path: 'results/:sessionId/score',
        title: 'Your literacy score - Money Compass',
        data: { eyebrow: 'Results', title: 'Literacy score', screen: 'score' },
        loadComponent: () => import('./pages/score.page').then((m) => m.ScorePage),
      },
      {
        path: 'results/:sessionId/risk',
        title: 'Your risk profile - Money Compass',
        data: { eyebrow: 'Results', title: 'Risk profile', screen: 'risk' },
        loadComponent: () => import('./pages/risk.page').then((m) => m.RiskPage),
      },
      {
        path: 'results/:sessionId/plan',
        title: 'Your investment plan - Money Compass',
        data: { eyebrow: 'Plan', title: 'Investment plan', screen: 'invest' },
        loadComponent: () => import('./pages/plan.page').then((m) => m.PlanPage),
      },
      {
        path: 'status',
        title: 'Model status - Money Compass',
        data: { eyebrow: 'System', title: 'Model status', screen: 'status' },
        loadComponent: () => import('./pages/status.page').then((m) => m.StatusPage),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
