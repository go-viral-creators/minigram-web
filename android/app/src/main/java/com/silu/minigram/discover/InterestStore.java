package com.silu.minigram.discover;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/** The user's interests. Stored only in this app's private storage; there is no code path that uploads them. */
public final class InterestStore {
    private InterestStore() {}
    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("discover_interests", Context.MODE_PRIVATE); }

    public static Set<String> get(Context c) { return new LinkedHashSet<>(sp(c).getStringSet("interests", new LinkedHashSet<String>())); }
    public static void set(Context c, Set<String> s) { sp(c).edit().putStringSet("interests", new LinkedHashSet<>(s)).apply(); }
    public static boolean seen(Context c) { return sp(c).getBoolean("seen", false); }
    public static void markSeen(Context c) { sp(c).edit().putBoolean("seen", true).apply(); }
}
