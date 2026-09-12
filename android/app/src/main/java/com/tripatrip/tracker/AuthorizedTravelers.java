package com.tripatrip.tracker;

import java.util.Locale;

public final class AuthorizedTravelers {
    private AuthorizedTravelers() { }

    public static String canonical(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if ("сеня".equals(n) || "senya".equals(n)) return "Сеня";
        if ("яна".equals(n) || "yana".equals(n)) return "Яна";
        return null;
    }

    public static boolean isAllowed(String name) {
        return canonical(name) != null;
    }
}