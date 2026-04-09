package com.ayedata.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared text and vector helpers used across services (RAG, temporal memory, context enrichment).
 */
public final class TextUtils {

    private TextUtils() {}

    /** Convert float[] embedding vector to List&lt;Double&gt; for MongoDB storage. */
    public static List<Double> toDoubleList(float[] floats) {
        List<Double> list = new ArrayList<>(floats.length);
        for (float f : floats) list.add((double) f);
        return list;
    }

    /** Truncate a string to {@code maxLen} characters, returning "" for null. */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    /** Truncate with an ellipsis ("…") appended when the string is clipped. */
    public static String truncateWithEllipsis(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    /** Return empty string for null, otherwise the original string. */
    public static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
