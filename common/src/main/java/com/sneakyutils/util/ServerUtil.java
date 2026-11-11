package com.sneakyutils.util;

import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Server identity from the {@code P_SERVER_NAME} environment variable.
 * Values are read on each call; both getters return null rather than inventing an identity.
 *
 * <p>Platform-free (no Paper/Velocity/Kotlin dependency), so it works on backends and proxies.
 */
public final class ServerUtil {
    private static final String SERVER_NAME_ENV = "P_SERVER_NAME";
    private static final Pattern SEPARATOR_RUN = Pattern.compile("[^\\p{L}\\p{N}]+");

    private ServerUtil() {}

    /** The supplied server name, exactly as set (for display); null when missing or blank. */
    public static @Nullable String getServerName() {
        return serverNameOf(System.getenv(SERVER_NAME_ENV));
    }

    /**
     * Lowercase kebab-case id derived from {@link #getServerName()}, e.g. {@code Dev Main} -> {@code dev-main}.
     * Null when the name is missing/blank or contains no letters or digits.
     */
    public static @Nullable String getServerId() {
        return serverIdOf(getServerName());
    }

    static @Nullable String serverNameOf(@Nullable String raw) {
        return raw == null || isBlank(raw) ? null : raw;
    }

    static @Nullable String serverIdOf(@Nullable String name) {
        if (name == null) return null;
        String id = SEPARATOR_RUN.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("-");
        int start = 0;
        int end = id.length();
        while (start < end && id.charAt(start) == '-') start++;
        while (end > start && id.charAt(end - 1) == '-') end--;
        return start == end ? null : id.substring(start, end);
    }

    // Matches Kotlin's isBlank (the previous implementation): also treats no-break spaces as blank,
    // which String.isBlank does not.
    private static boolean isBlank(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
        }
        return true;
    }
}
