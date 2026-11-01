package com.silu.minigram.discover;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public class RecommenderTest {
    static Recommender.Candidate ch(String id, String name, String desc, long subs, String... tags) {
        Recommender.Candidate c = new Recommender.Candidate();
        c.id = id; c.name = name; c.description = desc; c.subscribers = subs;
        c.tags.addAll(Arrays.asList(tags));
        return c;
    }
    static Set<String> set(String... s) { return new LinkedHashSet<>(Arrays.asList(s)); }

    @Test public void matchingChannelsBeatBiggerNonMatching() {
        List<Recommender.Candidate> pool = Arrays.asList(
            ch("huge", "Big Memes", "funny stuff", 5_000_000, "Memes"),
            ch("tech", "Tech India Daily", "", 48_000, "Tech"),
            ch("cric", "Cricket Adda", "", 91_000, "Cricket"));
        List<Recommender.Candidate> r = Recommender.rank(set("Tech", "Cricket"), pool, set(), 10);
        assertEquals("cric", r.get(0).id);
        assertEquals("tech", r.get(1).id);
        assertEquals("huge", r.get(2).id);
        assertEquals("Tech", r.get(1).matchedTag);
    }

    @Test public void subscribedChannelsAreExcluded() {
        List<Recommender.Candidate> pool = Arrays.asList(ch("a", "A", "", 10, "Tech"), ch("b", "B", "", 10, "Tech"));
        List<Recommender.Candidate> r = Recommender.rank(set("Tech"), pool, set("a"), 10);
        assertEquals(1, r.size());
        assertEquals("b", r.get(0).id);
    }

    @Test public void untaggedChannelsUseKeywordFallback() {
        List<Recommender.Candidate> pool = Arrays.asList(
            ch("p", "Pune Startups", "founders and funding in Pune", 12_900),
            ch("x", "Random Chat", "hello", 12_900));
        List<Recommender.Candidate> r = Recommender.rank(set("Startups"), pool, set(), 10);
        assertEquals("p", r.get(0).id);
        assertEquals("Startups", r.get(0).matchedTag);
    }

    @Test public void moreOverlapWins() {
        List<Recommender.Candidate> pool = Arrays.asList(
            ch("one", "One", "", 1000, "Tech"), ch("two", "Two", "", 1000, "Tech", "Startups"));
        assertEquals("two", Recommender.rank(set("Tech", "Startups"), pool, set(), 5).get(0).id);
    }

    @Test public void emptyInterestsFallBackToPopularity() {
        List<Recommender.Candidate> pool = Arrays.asList(ch("s", "S", "", 10, "Tech"), ch("b", "B", "", 100000, "Memes"));
        assertEquals("b", Recommender.rank(set(), pool, set(), 5).get(0).id);
    }

    @Test public void limitIsRespected() {
        List<Recommender.Candidate> pool = new ArrayList<>();
        for (int i = 0; i < 20; i++) pool.add(ch("c" + i, "C" + i, "", i, "Tech"));
        assertEquals(5, Recommender.rank(set("Tech"), pool, set(), 5).size());
    }

    @Test public void formatsCounts() {
        assertEquals("48.2k", Recommender.formatCount(48_200));
        assertEquals("91k", Recommender.formatCount(91_000));
        assertEquals("1.2M", Recommender.formatCount(1_200_000));
        assertEquals("999", Recommender.formatCount(999));
    }

    @Test public void deriveFindsWholeWordsOnly() {
        assertTrue(InterestTags.derive("Gym Bros", "").contains("Fitness"));
        assertFalse(InterestTags.derive("Carpet shop", "").contains("Cars"));   // "car" must not match inside "carpet"
    }
}
