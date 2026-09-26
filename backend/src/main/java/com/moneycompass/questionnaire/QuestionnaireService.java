package com.moneycompass.questionnaire;

import com.moneycompass.common.BadRequestException;
import com.moneycompass.common.ConflictException;
import com.moneycompass.common.NotFoundException;
import com.moneycompass.config.MoneyCompassProperties;
import com.moneycompass.domain.*;
import com.moneycompass.engine.AssessmentEngine;
import com.moneycompass.engine.Answers;
import com.moneycompass.engine.SessionAnswers;
import com.moneycompass.questionnaire.dto.*;
import com.moneycompass.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * The adaptive questionnaire. "Adaptive" means: the pool is filtered by
 * profile type, wording is tailored per profile ({@code variants}), every
 * question accepts "I don't know", and the rules in {@link AssessmentEngine}
 * add clarifiers and follow-ups or drop advanced questions as answers come in.
 *
 * <p>Every method re-derives "what's next" from the answers actually stored,
 * rather than tracking a cursor, so a resumed session, a double-submitted
 * answer, a step back, or a change to the question bank all resolve
 * correctly without migrating any in-flight state.
 */
@Service
public class QuestionnaireService {

    private final AssessmentSessionRepository sessionRepository;
    private final AnswerRepository answerRepository;
    private final UserRepository userRepository;
    private final AssessmentEngine engine;
    private final SessionAnswers sessionAnswers;
    private final int maxQuestions;

    public QuestionnaireService(
            AssessmentSessionRepository sessionRepository,
            AnswerRepository answerRepository,
            UserRepository userRepository,
            AssessmentEngine engine,
            SessionAnswers sessionAnswers,
            MoneyCompassProperties properties) {
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.userRepository = userRepository;
        this.engine = engine;
        this.sessionAnswers = sessionAnswers;
        this.maxQuestions = properties.questionnaire().maxQuestions();
    }

    @Transactional
    public StartSessionResponse start(UUID userId) {
        User user = requireUser(userId);
        AssessmentSession session = new AssessmentSession(UUID.randomUUID(), userId, user.getProfileType());
        sessionRepository.save(session);

        Map<String, Map<String, Object>> raw = Map.of();
        Question first = nextEligible(session, raw, user.getProfileType());
        return new StartSessionResponse(session.getId(), toDto(first, raw, user.getProfileType()),
                progress(raw, user.getProfileType()));
    }

    @Transactional(readOnly = true)
    public NextQuestionResponse next(UUID sessionId, UUID userId) {
        AssessmentSession session = requireOwnedSession(sessionId, userId);
        ProfileType profile = session.getProfileType();
        Map<String, Map<String, Object>> raw = sessionAnswers.of(sessionId);
        Question next = nextEligible(session, raw, profile);
        return new NextQuestionResponse(toDto(next, raw, profile), progress(raw, profile));
    }

    @Transactional
    public AnswerResponse answer(UUID sessionId, UUID userId, AnswerRequest request) {
        AssessmentSession session = requireOwnedSession(sessionId, userId);
        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            throw new ConflictException("This session is already completed");
        }
        ProfileType profile = session.getProfileType();

        Question question = Optional.ofNullable(engine.bank().byCode(request.questionCode()))
                .filter(Question::isActive)
                .filter(q -> q.appliesTo(profile))
                .orElseThrow(() -> new NotFoundException("Unknown question code: " + request.questionCode()));

        Map<String, Object> value = validateValue(question, profile, request.value());

        // Upsert: a resubmitted answer for the same question replaces the
        // old value rather than violating answers_session_question_uk.
        Answer existing = answerRepository.findBySessionIdAndQuestionCode(sessionId, question.getCode()).orElse(null);
        if (existing != null) {
            existing.setValue(value);
            answerRepository.save(existing);
        } else {
            answerRepository.save(new Answer(sessionId, question.getCode(), value));
        }
        answerRepository.flush();

        Map<String, Map<String, Object>> raw = sessionAnswers.of(sessionId);
        Question next = nextEligible(session, raw, profile);
        return new AnswerResponse(toDto(next, raw, profile), progress(raw, profile));
    }

    /**
     * Steps back one question: removes the most recent answer and serves its
     * question again, with the removed value so the client can pre-fill it.
     */
    @Transactional
    public BackResponse back(UUID sessionId, UUID userId) {
        AssessmentSession session = requireOwnedSession(sessionId, userId);
        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            throw new ConflictException("This session is already completed");
        }
        ProfileType profile = session.getProfileType();
        List<Answer> given = answerRepository.findBySessionIdOrderByAnsweredAtAscIdAsc(sessionId);
        if (given.isEmpty()) {
            throw new BadRequestException("There is no earlier question to go back to");
        }
        Answer last = given.getLast();
        answerRepository.delete(last);
        answerRepository.flush();

        Map<String, Map<String, Object>> raw = sessionAnswers.of(sessionId);
        Question question = engine.bank().byCode(last.getQuestionCode());
        return new BackResponse(toDto(question, raw, profile), last.getValue(), progress(raw, profile));
    }

    @Transactional
    public CompleteResponse complete(UUID sessionId, UUID userId) {
        AssessmentSession session = requireOwnedSession(sessionId, userId);
        // Idempotent: completing an already-completed session just reports
        // its status rather than failing the second click.
        if (session.getStatus() == SessionStatus.IN_PROGRESS) {
            session.complete(Instant.now());
            sessionRepository.save(session);
        }
        return new CompleteResponse(session.getId(), session.getStatus().name());
    }

    // -------------------------------------------------------------------

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User no longer exists"));
    }

    private AssessmentSession requireOwnedSession(UUID sessionId, UUID userId) {
        AssessmentSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Session not found"));
        if (!session.getUserId().equals(userId)) {
            // 404, not 403: a caller who does not own this session should not
            // be able to tell the id exists at all.
            throw new NotFoundException("Session not found");
        }
        return session;
    }

    private Question nextEligible(AssessmentSession session, Map<String, Map<String, Object>> raw, ProfileType profile) {
        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            return null;
        }
        List<Question> upcoming = engine.upcoming(raw, profile, maxQuestions);
        return upcoming.isEmpty() ? null : upcoming.getFirst();
    }

    private ProgressDto progress(Map<String, Map<String, Object>> raw, ProfileType profile) {
        int answered = raw.size();
        int upcoming = engine.upcoming(raw, profile, maxQuestions).size();
        return new ProgressDto(answered, answered + upcoming, engine.skippedAdvanced(raw, profile).size());
    }

    /** Validates against the options this profile was shown, and returns the value to store. */
    private Map<String, Object> validateValue(Question question, ProfileType profile, Map<String, Object> value) {
        if (Answers.isUnknown(value)) {
            return Map.of(Answers.UNKNOWN, true);
        }
        Map<String, String> options = presentedOptions(question, profile);
        switch (question.getType()) {
            case SINGLE -> {
                Object selected = value.get("selected");
                if (!(selected instanceof String code) || options == null || !options.containsKey(code)) {
                    throw new BadRequestException("'selected' must be one of: " + (options == null ? "[]" : options.keySet()));
                }
                return Map.of("selected", code);
            }
            case MULTI -> {
                Object selected = value.get("selected");
                List<String> chosen = selected instanceof List<?> list
                        ? list.stream().map(String::valueOf).distinct().toList()
                        : List.of();
                if (chosen.isEmpty() || options == null || !options.keySet().containsAll(chosen)) {
                    throw new BadRequestException(
                            "'selected' must be a non-empty list drawn from: " + (options == null ? "[]" : options.keySet()));
                }
                return Map.of("selected", chosen);
            }
            case NUMBER -> {
                if (!(value.get("number") instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                    throw new BadRequestException("'number' must be a number");
                }
                double min = question.getMinValue() == null ? 0 : question.getMinValue();
                if (n.doubleValue() < min || (question.getMaxValue() != null && n.doubleValue() > question.getMaxValue())) {
                    throw new BadRequestException("'number' must be between " + fmt(min) + " and "
                            + (question.getMaxValue() == null ? "any larger value" : fmt(question.getMaxValue())));
                }
                return Map.of("number", n);
            }
            case SCALE -> {
                if (!(value.get("scale") instanceof Number scale) || scale.intValue() < 1 || scale.intValue() > 5) {
                    throw new BadRequestException("'scale' must be an integer between 1 and 5");
                }
                return Map.of("scale", scale.intValue());
            }
        }
        throw new BadRequestException("Unsupported question type");
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    /** The profile's variant, if any, merged over the base question. */
    private static Map<String, Object> variant(Question q, ProfileType profile) {
        return q.getVariants() == null ? null : q.getVariants().get(profile.name());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> presentedOptions(Question q, ProfileType profile) {
        Map<String, Object> v = variant(q, profile);
        Map<String, Object> source = v != null && v.get("options") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : q.getOptions();
        if (source == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), String.valueOf(e.getValue())));
        return out;
    }

    private QuestionDto toDto(Question q, Map<String, Map<String, Object>> raw, ProfileType profile) {
        if (q == null) return null;
        Map<String, Object> v = variant(q, profile);
        String text = v != null && v.get("text") instanceof String s ? s : q.getText();
        String hint = v != null && v.get("hint") instanceof String s ? s : q.getHint();
        String unit = v != null && v.get("unit") instanceof String s ? s : q.getUnit();

        String followUpNote = q.getClarifies() != null
                ? "Follow-up — a simpler way to ask the last question."
                : q.getFollowUpReason();

        // What "I don't know" will do: a simpler re-ask if one exists,
        // otherwise knowledge questions count as a gap to learn and
        // self-reported ones are read cautiously.
        boolean hasClarifier = engine.bank().all().stream()
                .anyMatch(other -> other.isActive() && q.getCode().equals(other.getClarifies()) && other.appliesTo(profile));
        String consequence = hasClarifier ? "No problem — we'll ask it a simpler way."
                : q.isKnowledgeQuestion() ? "No guessing needed — we'll note it as something to learn."
                : "We'll assume the cautious answer for your plan.";

        // "Finish" only when an answer here wouldn't lead to any more questions.
        Map<String, Map<String, Object>> hypothetical = new LinkedHashMap<>(raw);
        hypothetical.put(q.getCode(), Map.of());
        boolean last = engine.upcoming(hypothetical, profile, maxQuestions).isEmpty();

        String wikiUrl = q.getWikiTitle() == null ? null
                : "https://en.wikipedia.org/wiki/" + URLEncoder.encode(q.getWikiTitle().replace(' ', '_'), StandardCharsets.UTF_8);

        return new QuestionDto(
                q.getCode(), text, q.getType(), presentedOptions(q, profile), q.getCategory().name(),
                hint, unit, q.getMinValue(), q.getMaxValue(), q.getScaleLabels(),
                followUpNote, consequence, q.getWikiTitle(), wikiUrl, last);
    }
}
