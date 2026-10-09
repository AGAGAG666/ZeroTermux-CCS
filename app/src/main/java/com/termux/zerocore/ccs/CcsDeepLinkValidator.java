package com.termux.zerocore.ccs;

import java.net.URI;
import java.net.URISyntaxException;

/** Strict, Android-independent validation for externally supplied CC Switch links. */
final class CcsDeepLinkValidator {
    private static final int MAX_DEEP_LINK_LENGTH = 64 * 1024;

    private CcsDeepLinkValidator() {}

    static boolean isSupported(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_DEEP_LINK_LENGTH) {
            return false;
        }

        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return false;
        }

        return "ccswitch".equalsIgnoreCase(uri.getScheme())
            && "v1".equalsIgnoreCase(uri.getHost())
            && "/import".equals(uri.getPath())
            && hasQueryParameter(uri.getRawQuery(), "resource");
    }

    private static boolean hasQueryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) return false;
        for (String entry : rawQuery.split("&")) {
            int separator = entry.indexOf('=');
            String key = separator >= 0 ? entry.substring(0, separator) : entry;
            if (name.equals(key)) return true;
        }
        return false;
    }
}
