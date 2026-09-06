import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  AiHealthResponse, AnswerResponse, AnswerValue, LiteracyScoreResponse,
  NextQuestionResponse, RiskProfileResponse, StartSessionResponse,
} from './api.types';

/**
 * One client for the questionnaire, score, risk and health endpoints. They are
 * a single flow for the user, so splitting them across four services would only
 * add imports.
 *
 * Endpoints come from section 5 of the specification. The questionnaire, score
 * and risk calls return 404 until Phases 2, 4 and 6 are implemented.
 */
@Injectable({ providedIn: 'root' })
export class AssessmentService {

  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  start(): Observable<StartSessionResponse> {
    return this.http.post<StartSessionResponse>(`${this.base}/questionnaire/start`, {});
  }

  next(sessionId: string): Observable<NextQuestionResponse> {
    return this.http.get<NextQuestionResponse>(`${this.base}/questionnaire/${sessionId}/next`);
  }

  answer(sessionId: string, questionCode: string, value: AnswerValue): Observable<AnswerResponse> {
    return this.http.post<AnswerResponse>(
      `${this.base}/questionnaire/${sessionId}/answer`, { questionCode, value });
  }

  complete(sessionId: string): Observable<{ sessionId: string; status: string }> {
    return this.http.post<{ sessionId: string; status: string }>(
      `${this.base}/questionnaire/${sessionId}/complete`, {});
  }

  computeScore(sessionId: string): Observable<LiteracyScoreResponse> {
    return this.http.post<LiteracyScoreResponse>(`${this.base}/score/${sessionId}`, {});
  }

  getScore(sessionId: string): Observable<LiteracyScoreResponse> {
    return this.http.get<LiteracyScoreResponse>(`${this.base}/score/${sessionId}`);
  }

  computeRisk(sessionId: string): Observable<RiskProfileResponse> {
    return this.http.post<RiskProfileResponse>(`${this.base}/risk/${sessionId}`, {});
  }

  getRisk(sessionId: string): Observable<RiskProfileResponse> {
    return this.http.get<RiskProfileResponse>(`${this.base}/risk/${sessionId}`);
  }

  aiHealth(): Observable<AiHealthResponse> {
    return this.http.get<AiHealthResponse>(`${this.base}/health/ai`);
  }
}
