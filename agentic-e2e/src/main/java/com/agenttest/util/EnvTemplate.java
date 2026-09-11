package com.agenttest.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves %VAR_NAME% placeholders in strings using environment variables.
 *
 * Example:
 *   "Navigate to %APP_BASE_URL%/products"
 *   → "Navigate to http://localhost:8080/products"
 *
 * Unknown variables are left as-is.
 */
public class EnvTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Z0-9_]+)%");

    public static String resolve(String template) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String var   = m.group(1);
            String value = System.getenv(var);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
