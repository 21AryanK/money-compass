package com.moneycompass.narrative;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What earlier drafts of one narrative already said, so the next draft can
 * favour points, framings and phrasings the user hasn't read yet — the way a
 * model asked the same question twice reaches for a different take rather
 * than rewording itself.
 *
 * <p>Built from the {@code pointIds} and {@code angle} stored with each draft
 * (see {@code NarrativeDraft}), so it survives restarts and works the same
 * whichever instance serves the request.
 */
public final class DraftMemory {

    final Map<String, Integer> seenPoints = new HashMap<>();
    final Map<String, Integer> seenAngles = new HashMap<>();
    final Map<String, Integer> lastVariant = new HashMap<>();
    final Random random;
    final boolean fresh;

    private DraftMemory(boolean fresh, long seed) {
        this.fresh = fresh;
        this.random = new Random(seed);
    }

    /** A first draft: leads with the highest-priority points. */
    public static DraftMemory first() {
        return new DraftMemory(false, System.nanoTime());
    }

    /** A regenerate: everything earlier drafts used is counted against it. */
    public static DraftMemory after(List<NarrativeDraft> previous) {
        DraftMemory m = new DraftMemory(true, System.nanoTime() ^ previous.size());
        for (NarrativeDraft d : previous) {
            if (d.pointIds() != null) d.pointIds().forEach(id -> m.seenPoints.merge(id, 1, Integer::sum));
            if (d.angle() != null) m.seenAngles.merge(d.angle(), 1, Integer::sum);
        }
        return m;
    }

    /** For tests: reproducible choices. */
    static DraftMemory seeded(boolean fresh, long seed) {
        return new DraftMemory(fresh, seed);
    }
}
