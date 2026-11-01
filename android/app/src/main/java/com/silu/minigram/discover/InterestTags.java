package com.silu.minigram.discover;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The fixed interest taxonomy (also the set of tags a channel owner can pick) and a keyword fallback for channels without tags. */
public final class InterestTags {
    private InterestTags() {}

    public static final Map<String, String[]> KEYWORDS = new LinkedHashMap<>();
    static {
        KEYWORDS.put("Tech", new String[]{"tech", "technology", "software", "coding", "programming", "developer", "android", "ai", "gadget", "startup tech", "linux", "cyber"});
        KEYWORDS.put("Cricket", new String[]{"cricket", "ipl", "bcci", "t20", "test match", "batting", "bowler"});
        KEYWORDS.put("Music", new String[]{"music", "songs", "song", "lyrics", "bollywood music", "guitar", "playlist", "singer", "dj"});
        KEYWORDS.put("Startups", new String[]{"startup", "startups", "founder", "entrepreneur", "vc", "funding", "saas", "business"});
        KEYWORDS.put("Anime", new String[]{"anime", "manga", "otaku", "naruto", "one piece"});
        KEYWORDS.put("Photography", new String[]{"photo", "photography", "camera", "lens", "photographer", "lightroom"});
        KEYWORDS.put("Gaming", new String[]{"gaming", "game", "games", "pubg", "bgmi", "esports", "minecraft", "valorant", "playstation"});
        KEYWORDS.put("Finance", new String[]{"finance", "stock", "stocks", "trading", "investing", "mutual fund", "crypto", "market", "nifty"});
        KEYWORDS.put("Fitness", new String[]{"fitness", "gym", "workout", "yoga", "health", "diet", "running"});
        KEYWORDS.put("Movies", new String[]{"movie", "movies", "film", "cinema", "web series", "netflix", "bollywood", "hollywood"});
        KEYWORDS.put("Travel", new String[]{"travel", "trip", "tourism", "trek", "trekking", "backpack"});
        KEYWORDS.put("Food", new String[]{"food", "recipe", "recipes", "cooking", "chef", "restaurant"});
        KEYWORDS.put("Art", new String[]{"art", "design", "drawing", "illustration", "sketch", "painting"});
        KEYWORDS.put("Education", new String[]{"education", "study", "exam", "jee", "neet", "upsc", "learning", "course", "students"});
        KEYWORDS.put("News", new String[]{"news", "headlines", "breaking", "politics", "daily"});
        KEYWORDS.put("Memes", new String[]{"meme", "memes", "funny", "jokes", "comedy"});
        KEYWORDS.put("Books", new String[]{"books", "book", "reading", "novel", "literature", "poetry"});
        KEYWORDS.put("Cars", new String[]{"car", "cars", "bike", "bikes", "auto", "motorcycle", "ev"});
        KEYWORDS.put("Science", new String[]{"science", "space", "isro", "physics", "nasa", "biology"});
    }

    public static List<String> all() { return new ArrayList<>(KEYWORDS.keySet()); }

    /** Tags guessed from a channel's name/description when its owner has not set any. */
    public static Set<String> derive(String name, String description) {
        String text = " " + ((name == null ? "" : name) + " " + (description == null ? "" : description)).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ") + " ";
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String[]> e : KEYWORDS.entrySet())
            for (String k : e.getValue())
                if (text.contains(" " + k + " ")) { out.add(e.getKey()); break; }
        return out;
    }
}
