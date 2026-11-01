package com.silu.minigram.discover;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Interest-based channel ranking. Runs entirely on the phone: the interests never leave it and the
 * candidate list is a generic "popular channels" fetch that does not depend on them.
 */
public final class Recommender {
    private Recommender() {}

    public static final class Candidate {
        public String id, name, description, photoUrl;
        public long subscribers;
        public Set<String> tags = new LinkedHashSet<>();     // owner-set tags
        public Set<String> effectiveTags = new LinkedHashSet<>();
        public boolean verified;
        public double score;
        public String matchedTag = "";
    }

    /** @return candidates the user is not subscribed to, best match first; channels with no overlap come last (popularity only). */
    public static List<Candidate> rank(Set<String> interests, List<Candidate> pool, Set<String> subscribedIds, int limit) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : pool) {
            if (subscribedIds.contains(c.id)) continue;
            c.effectiveTags = c.tags.isEmpty() ? InterestTags.derive(c.name, c.description) : c.tags;
            int overlap = 0; String first = "";
            for (String t : c.effectiveTags) if (interests.contains(t)) { overlap++; if (first.isEmpty()) first = t; }
            c.matchedTag = first.isEmpty() && !c.effectiveTags.isEmpty() ? c.effectiveTags.iterator().next() : first;
            double pop = Math.log10(Math.max(1, c.subscribers) + 1);          // 0..~6
            double ownerTagged = c.tags.isEmpty() ? 0 : 0.5;                   // trust explicit tags a bit more than guesses
            c.score = overlap * 10.0 + (overlap > 0 ? ownerTagged : 0) + pop + (c.verified ? 0.3 : 0);
            if (overlap == 0 && !interests.isEmpty()) c.score *= 0.2;           // not a match: keep far below real matches
            out.add(c);
        }
        Collections.sort(out, (a, b) -> Double.compare(b.score, a.score));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public static String formatCount(long n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M");
        if (n >= 1_000) return String.format(java.util.Locale.US, "%.1fk", n / 1_000.0).replace(".0k", "k");
        return String.valueOf(n);
    }
}
