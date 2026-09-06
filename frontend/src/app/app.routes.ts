import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'login' },
  {
    path: 'register',
    title: 'Create an account - Money Compass',
    loadComponent: () => import('./pages/register.page').then((m) => m.RegisterPage),
  },
  {
    path: 'login',
    title: 'Sign in - Money Compass',
    loadComponent: () => import('./pages/login.page').then((m) => m.LoginPage),
  },
  {
    path: 'start',
    title: 'Start - Money Compass',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/start.page').then((m) => m.StartPage),
  },
  {
    path: 'questionnaire/:sessionId',
    title: 'Questions - Money Compass',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/questionnaire.page').then((m) => m.QuestionnairePage),
  },
  {
    path: 'results/:sessionId',
    title: 'Your results - Money Compass',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/results.page').then((m) => m.ResultsPage),
  },
  {
    path: 'status',
    title: 'Model status - Money Compass',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/status.page').then((m) => m.StatusPage),
  },
  { path: '**', redirectTo: 'login' },
];
