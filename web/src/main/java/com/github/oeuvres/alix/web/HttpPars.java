/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateful helper for HTTP request parameter resolution, wrapping an
 * {@link HttpServletRequest} and an optional {@link HttpServletResponse}.
 * 
 * <p>Each typed getter resolves a parameter value through a priority chain:
 * <b>request parameter → request attribute → cookie → fallback</b>.
 * When a cookie name is supplied, the resolved value is persisted as a cookie;
 * an empty (non-null) parameter resets that cookie.
 * Cookie persistence requires a non-null response; instances constructed
 * with {@link #HttpPars(HttpServletRequest)} silently skip all cookie writes.
 * </p>
 * 
 * <p>Also provides static utilities for query-string building
 * and parameter value testing.</p>
 */
public class HttpPars
{

    /** Wrapped request, source of parameters and attributes. */
    public final HttpServletRequest request;
    /** Wrapped response, used for cookie persistence (null for read-only usage). */
    public final HttpServletResponse response;
    /** Lazily populated cookie cache, keyed by cookie name. */
    private HashMap<String, String> cookies;
    /** Default cookie max-age: 30 days, in seconds. */
    private static final int COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 30;

    /**
     * Construct a parameter helper with cookie persistence.
     * 
     * @param request  the current HTTP request.
     * @param response the current HTTP response (receives Set-Cookie headers).
     */
    public HttpPars(final HttpServletRequest request, final HttpServletResponse response)
    {
        this.request = request;
        this.response = response;
    }

    /**
     * Construct a read-only parameter helper (no cookie persistence).
     * Cookie-writing calls become silent no-ops. Suitable for JSON endpoints
     * where server-side cookie management is unwanted.
     * 
     * @param request the current HTTP request.
     */
    public HttpPars(final HttpServletRequest request)
    {
        this(request, null);
    }

    /**
     * Get a cookie value by name. Lazily caches all request cookies
     * on first call.
     * 
     * @param name cookie name.
     * @return the cookie value, or null if absent or name is blank.
     */
    public String cookie(final String name)
    {
        if (!hasValue(name))
            return null;
        if (cookies == null) {
            Cookie[] cooks = request.getCookies();
            if (cooks == null)
                return null;
            cookies = new HashMap<String, String>();
            for (Cookie cook : cooks) {
                cookies.put(cook.getName(), cook.getValue());
            }
        }
        return cookies.get(name);
    }

    /**
     * Set or delete a cookie on the response using the Jakarta {@link Cookie} API.
     * A null value deletes the cookie (max-age=0). Non-null values are persisted
     * for {@value #COOKIE_MAX_AGE_SECONDS} seconds with HttpOnly and SameSite=Strict.
     * 
     * <p>No-op if this instance was constructed without a response
     * (see {@link #HttpPars(HttpServletRequest)}).</p>
     * 
     * @param name  cookie name.
     * @param value cookie value, or null to delete.
     */
    public void cookie(final String name, final String value)
    {
        if (!hasValue(name))
            return;
        if (response == null)
            return;
        if (value == null) {
            if (cookie(name) == null)
                return;
            Cookie c = new Cookie(name, "");
            c.setMaxAge(0);
            c.setHttpOnly(true);
            c.setAttribute("SameSite", "Strict");
            response.addCookie(c);
        } else {
            Cookie c = new Cookie(name, value);
            c.setMaxAge(COOKIE_MAX_AGE_SECONDS);
            c.setHttpOnly(true);
            c.setAttribute("SameSite", "Strict");
            response.addCookie(c);
        }
    }

    /**
     * Resolve a request parameter as a boolean.
     * Values {@code "false"}, {@code "0"}, {@code "null"} are interpreted as false;
     * any other non-blank value as true.
     * 
     * @param name     parameter name.
     * @param fallback value returned when the parameter is absent or blank.
     * @return resolved boolean.
     */
    public boolean getBoolean(final String name, final boolean fallback)
    {
        String value = request.getParameter(name);
        if ("false".equals(value) || "0".equals(value) || "null".equals(value))
            return false;
        if (hasValue(value))
            return true;
        return fallback;
    }

    /**
     * Resolve a request parameter as a boolean with cookie persistence.
     * Priority: request parameter → cookie → fallback.
     * An empty (non-null) parameter resets the cookie.
     * 
     * @param name     parameter name.
     * @param fallback value returned when neither parameter nor cookie yield a result.
     * @param cookie   cookie name for persistence.
     * @return resolved boolean.
     */
    public boolean getBoolean(final String name, final boolean fallback, final String cookie)
    {
        String value = request.getParameter(name);
        if ("false".equals(value) || "0".equals(value) || "null".equals(value)) {
            cookie(cookie, "0");
            return false;
        }
        if (hasValue(value)) {
            cookie(cookie, "1");
            return true;
        }
        // param is empty but not null, reset cookie
        if (value != null) {
            cookie(cookie, null);
            return fallback;
        }
        // try cookie
        value = cookie(cookie);
        if ("0".equals(value))
            return false;
        if (hasValue(value))
            return true;
        cookie(cookie, null);
        return fallback;
    }

    /**
     * Resolve a request parameter as an {@link Enum} constant.
     * Uses {@link Enum#valueOf(Class, String)} to match; returns
     * the fallback on mismatch or absence.
     * 
     * @param name     parameter name.
     * @param fallback default value (must not be null — its declaring class is used for lookup).
     * @return the matched enum constant, or fallback.
     * @throws IllegalArgumentException if fallback is null.
     */
    public Enum<?> getEnum(final String name, final Enum<?> fallback)
    {
        if (fallback == null) {
            throw new IllegalArgumentException(
                    "fallback can't be null, a value is needed to get the declaring class of the Enum");
        }
        String value = request.getParameter(name);
        if (!hasValue(value)) {
            return fallback;
        }
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * Resolve a request parameter as an {@link Enum} constant with cookie persistence.
     * Priority: request parameter → cookie → fallback.
     * An empty (non-null) parameter resets the cookie.
     * 
     * @param name     parameter name.
     * @param fallback default value (must not be null).
     * @param cookie   cookie name for persistence.
     * @return the matched enum constant, or fallback.
     * @throws IllegalArgumentException if fallback is null.
     */
    public Enum<?> getEnum(final String name, final Enum<?> fallback, final String cookie)
    {
        if (fallback == null) {
            throw new IllegalArgumentException(
                    "fallback can't be null, a value is needed to get the declaring class of the Enum");
        }
        String value = request.getParameter(name);
        if (hasValue(value)) {
            try {
                Enum<?> ret = Enum.valueOf(fallback.getDeclaringClass(), value);
                cookie(cookie, ret.name());
                return ret;
            } catch (IllegalArgumentException e) {
                // bad param, fall through
            }
        }
        if (value != null) {
            cookie(cookie, null);
            return fallback;
        }
        value = cookie(cookie);
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), value);
        } catch (Exception e) {
            cookie(cookie, null);
            return fallback;
        }
    }

    /**
     * Resolve a request parameter as a float.
     * 
     * @param name     parameter name.
     * @param fallback value returned when absent or unparseable.
     * @return resolved float.
     */
    public float getFloat(final String name, final float fallback)
    {
        String value = request.getParameter(name);
        if (hasValue(value)) {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return fallback;
    }

    /**
     * Resolve a request parameter as a float with cookie persistence.
     * Priority: request parameter → cookie → fallback.
     * An empty (non-null) parameter resets the cookie.
     * 
     * @param name     parameter name.
     * @param fallback value returned when neither parameter nor cookie yield a valid float.
     * @param cookie   cookie name for persistence.
     * @return resolved float.
     */
    public float getFloat(final String name, final float fallback, final String cookie)
    {
        String value = request.getParameter(name);
        if (hasValue(value)) {
            try {
                float ret = Float.parseFloat(value);
                cookie(cookie, "" + ret);
                return ret;
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        if (value != null && !hasValue(value)) {
            cookie(cookie, null);
            return fallback;
        }
        value = cookie(cookie);
        if (value == null)
            return fallback;
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            cookie(cookie, null);
            return fallback;
        }
    }

    /**
     * Resolve a request parameter as an int.
     * 
     * @param name     parameter name.
     * @param fallback value returned when absent or unparseable.
     * @return resolved int.
     */
    public int getInt(final String name, final int fallback)
    {
        return getInt(name, null, fallback, null);
    }

    /**
     * Resolve a request parameter as an int with cookie persistence.
     * 
     * @param name     parameter name.
     * @param fallback default value.
     * @param cookie   cookie name for persistence.
     * @return resolved int. Priority: request → cookie → fallback.
     */
    public int getInt(final String name, final int fallback, final String cookie)
    {
        return getInt(name, null, fallback, cookie);
    }

    /**
     * Resolve a request parameter as an int, clamped to a range.
     * 
     * @param name     parameter name.
     * @param range    {@code [min, max]} bounds (inclusive), or null for unclamped.
     * @param fallback default value.
     * @return resolved int, clamped to range if provided.
     */
    public int getInt(final String name, final int[] range, final int fallback)
    {
        return getInt(name, range, fallback, null);
    }

    /**
     * Resolve a request parameter as an int, clamped to a range,
     * with optional cookie persistence.
     * Priority: request parameter → request attribute → cookie → fallback.
     * The resolved value is clamped to [{@code range[0]}, {@code range[1]}].
     * An empty (non-null) parameter resets the cookie.
     * 
     * @param name     parameter name.
     * @param range    {@code [min, max]} bounds (inclusive), or null for unclamped.
     * @param fallback default value.
     * @param cookie   cookie name for persistence, or null.
     * @return resolved int, clamped to range.
     */
    public int getInt(final String name, final int[] range, final int fallback, final String cookie)
    {
        int min = Integer.MIN_VALUE;
        int max = Integer.MAX_VALUE;
        if (range != null && range.length >= 2) {
            min = Math.min(range[0], range[1]);
            max = Math.max(range[0], range[1]);
        }
        final String parString = request.getParameter(name);
        Integer value = parseInt(parString);

        if (hasValue(cookie)) {
            if (parString != null && !hasValue(parString)) {
                cookie(cookie, null);
            } else if (value != null && value >= min && value <= max) {
                cookie(cookie, "" + value);
            } else if (value == null) {
                value = parseInt(cookie(cookie));
                if (value != null && (value < min || value > max)) {
                    value = null;
                    cookie(cookie, null);
                }
            }
        }
        if (value == null) {
            value = getIntegerAtt(name);
        }
        if (value == null) {
            return fallback;
        } else if (value < min) {
            return min;
        } else if (value > max) {
            return max;
        } else {
            return value;
        }
    }

    /**
     * Look up a request attribute as an Integer fallback.
     * 
     * @param name attribute name.
     * @return the attribute value as Integer, or null if absent or wrong type.
     */
    private Integer getIntegerAtt(final String name)
    {
        Object att = request.getAttribute(name);
        if (att instanceof Integer) return (Integer) att;
        return null;
    }

    /**
     * Parse a multi-valued parameter as an int range, clamped to bounds.
     * Returns an array of length 0 (no valid values), 1 (single value),
     * or 2 ({@code [lower, upper]}, guaranteed lower ≤ upper).
     * Open-ended ranges are supported: a missing lower defaults to min,
     * a missing upper defaults to max.
     * 
     * @param name  parameter name.
     * @param range {@code [min, max]} bounds (inclusive), or null for unclamped.
     * @return int array of length 0, 1, or 2.
     */
    public int[] getIntRange(final String name, final int[] range)
    {
        int min = Integer.MIN_VALUE;
        int max = Integer.MAX_VALUE;
        if (range != null && range.length >= 2) {
            min = Math.min(range[0], range[1]);
            max = Math.max(range[0], range[1]);
        }
        String[] values = request.getParameterValues(name);
        if (values == null || values.length == 0) {
            return new int[0];
        }
        Integer value0 = null;
        try {
            value0 = Integer.valueOf(values[0]);
        } catch (NumberFormatException e) {
            // leave null
        }
        if (values.length == 1) {
            if (value0 == null) {
                return new int[0];
            } else if (value0 >= min && value0 <= max) {
                return new int[] { value0 };
            } else {
                return new int[0];
            }
        }
        Integer value1 = null;
        try {
            value1 = Integer.parseInt(values[1]);
        } catch (NumberFormatException e) {
            // leave null
        }
        if (value0 == null && value1 == null) {
            return new int[0];
        }
        final int[] data = new int[2];
        if (value0 == null) {
            if (value1 < min) return new int[0];
            data[0] = min;
            data[1] = Integer.min(value1, max);
        } else if (value1 == null) {
            if (value0 > max) return new int[0];
            data[0] = Integer.max(value0, min);
            data[1] = max;
        } else {
            data[0] = Integer.max(Integer.min(value0, value1), min);
            data[1] = Integer.min(Integer.max(value0, value1), max);
        }
        return data;
    }

    /**
     * Collect a multi-valued int parameter as a deduplicated array,
     * preserving request order.
     * 
     * @param name parameter name.
     * @return array of unique int values (empty if none valid).
     */
    public int[] getIntSet(final String name)
    {
        String[] vals = request.getParameterValues(name);
        if (vals == null || vals.length < 1) {
            return new int[0];
        }
        int dstPos = 0;
        final int[] dst = new int[vals.length];
        Set<Integer> set = new HashSet<>(vals.length);
        for (String val : vals) {
            int value;
            try {
                value = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                continue;
            }
            if (set.contains(value)) continue;
            set.add(value);
            dst[dstPos++] = value;
        }
        return Arrays.copyOf(dst, dstPos);
    }

    /**
     * Resolve a request parameter as a String.
     * 
     * @param name     parameter name.
     * @param fallback value returned when absent or blank.
     * @return resolved string. Priority: request parameter → request attribute → fallback.
     */
    public String getString(final String name, final String fallback)
    {
        return getString(name, fallback, null, null);
    }

    /**
     * Resolve a request parameter as a String, restricted to an allowed set.
     * 
     * @param name     parameter name.
     * @param fallback value returned when absent, blank, or not in set.
     * @param set      allowed values, or null for unrestricted.
     * @return resolved string. Priority: request parameter → request attribute → fallback.
     */
    public String getString(final String name, final String fallback, final Set<String> set)
    {
        return getString(name, fallback, set, null);
    }

    /**
     * Resolve a request parameter as a String, with optional allowed-value
     * restriction and cookie persistence.
     * Priority: request parameter → request attribute → cookie → fallback.
     * An empty (non-null) parameter resets the cookie.
     * 
     * @param name     parameter name.
     * @param fallback value returned when no source yields a valid result.
     * @param set      allowed values, or null for unrestricted.
     * @param cookie   cookie name for persistence, or null.
     * @return resolved string.
     */
    public String getString(final String name, final String fallback, final Set<String> set, final String cookie)
    {
        String par = request.getParameter(name);
        // no cookie, answer fast
        if (!hasValue(cookie) && hasValue(par)) {
            if (set == null) {
                return par;
            } else if (set.contains(par)) {
                return par;
            } else {
                par = null;
            }
        }
        // try request attribute
        Object o = request.getAttribute(name);
        String att = null;
        if (o instanceof String) {
            att = (String) o;
        }
        if (!hasValue(cookie) && hasValue(att)) {
            if (set == null) {
                return att;
            } else if (set.contains(att)) {
                return att;
            } else {
                att = null;
            }
        }
        if (!hasValue(cookie)) {
            return fallback;
        }

        // cookie path
        final String cookieValue = cookie(cookie);
        if (hasValue(par)) {
            cookie(cookie, par);
            return par;
        }
        if (par != null) {
            cookie(cookie, null);
        }
        if (hasValue(cookieValue)) return cookieValue;
        return fallback;
    }

    /**
     * Collect a multi-valued String parameter as a deduplicated array,
     * preserving request order. Blank values are skipped.
     * 
     * @param name parameter name.
     * @return array of unique non-blank values (empty array if none).
     */
    public String[] getStringSet(final String name)
    {
        return getStringSet(name, null);
    }

    /**
     * Collect a multi-valued String parameter as a deduplicated array,
     * preserving request order. Blank values and values not in the allowed
     * set are skipped.
     * 
     * @param name parameter name.
     * @param set  optional set of allowed values, or null for unrestricted.
     * @return array of unique accepted values (empty array if none).
     */
    public String[] getStringSet(final String name, final Set<String> set)
    {
        final String[] empty = new String[0];
        String[] values = request.getParameterValues(name);
        if (values == null)
            return empty;
        List<String> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String v : values) {
            if (v == null)
                continue;
            if (v.trim().isEmpty())
                continue;
            if (seen.contains(v))
                continue;
            if (set != null && !set.contains(v)) continue;
            seen.add(v);
            list.add(v);
        }
        if (list.isEmpty())
            return empty;
        return list.toArray(empty);
    }

    /**
     * Test whether a string carries a usable parameter value.
     * Returns {@code false} for null, blank, or the literal {@code "null"}.
     * 
     * @param s string to test.
     * @return true if the string is non-null, non-blank, and not {@code "null"}.
     */
    public static boolean hasValue(String s)
    {
        if (s == null)
            return false;
        s = s.trim();
        if (s.isEmpty())
            return false;
        if ("null".equals(s))
            return false;
        return true;
    }

    /**
     * Parse a string as an Integer, returning null on failure.
     * 
     * @param value string to parse.
     * @return parsed Integer, or null if blank, null, or not a valid integer.
     */
    private Integer parseInt(final String value)
    {
        if (!hasValue(value)) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Return the wrapped request.
     * 
     * @return the HTTP request.
     */
    public HttpServletRequest request()
    {
        return request;
    }

    /**
     * Return the wrapped response.
     * 
     * @return the HTTP response, or null if constructed read-only.
     */
    public HttpServletResponse response()
    {
        return response;
    }

    /**
     * Build a query string from the given parameter names, reading values
     * from the current request. Pairs are joined with {@code &amp;}.
     * Does not include a leading {@code ?}.
     * 
     * <p>Values are minimally encoded: only the characters that are structural
     * in a query string ({@code &amp; = + #} and space) are percent-encoded.
     * Unicode characters (accented letters, CJK, etc.) are preserved as
     * UTF-8, keeping URLs readable — e.g. {@code q=État&sort=date}
     * rather than {@code q=%C3%89tat&sort=date}.</p>
     * 
     * <p>The returned string is suitable for direct inclusion in a
     * double-quoted HTML {@code href} attribute, since {@code &amp;} is used
     * as separator and values are HTML-safe after encoding.
     * For contexts that need a raw {@code &amp;} separator (redirects,
     * JavaScript), use {@link #toQueryStringRaw(String...)} instead.</p>
     * 
     * @param names parameter names to include (absent parameters are skipped).
     * @return the query string fragment, or empty string if no parameters have values.
     * @see #toQueryStringRaw(String...)
     */
    public String toQueryString(final String... names)
    {
        return toQueryString(true, names);
    }

    /**
     * Build a query string with raw {@code &} separators, suitable for
     * programmatic URI construction (redirects, {@code Location} headers,
     * JavaScript). Same encoding rules as {@link #toQueryString(String...)},
     * but without HTML entity escaping of the separator.
     * 
     * @param names parameter names to include (absent parameters are skipped).
     * @return the query string fragment, or empty string if no parameters have values.
     * @see #toQueryString(String...)
     */
    public String toQueryStringRaw(final String... names)
    {
        return toQueryString(false, names);
    }

    /**
     * Internal query-string builder.
     * 
     * @param htmlSep if true, join pairs with {@code &amp;}; otherwise {@code &}.
     * @param names   parameter names.
     * @return the query string fragment.
     */
    private String toQueryString(final boolean htmlSep, final String... names)
    {
        final String sep = htmlSep ? "&amp;" : "&";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String name : names) {
            String value = request.getParameter(name);
            if (value == null)
                continue;
            if (first) {
                first = false;
            } else {
                sb.append(sep);
            }
            sb.append(encodeQueryComponent(name));
            sb.append('=');
            sb.append(encodeQueryComponent(value));
        }
        return sb.toString();
    }

    /**
     * Encode a single {@code name=value} query-string pair, using the same
     * minimal percent-encoding as {@link #toQueryString(String...)}.
     * Useful for building a per-row varying part outside of the request context,
     * e.g. in a KWIC loop:
     * <pre>{@code
     *   String base = pars.toQueryString("q", "cat", "sort");
     *   for (Hit hit : hits) {
     *       String href = base + "&amp;" + HttpPars.encodeParam("doc", hit.docId);
     *   }
     * }</pre>
     * 
     * @param name  parameter name.
     * @param value parameter value.
     * @return the encoded pair, e.g. {@code "doc=1234"} or {@code "q=État"}.
     */
    public static String encodeParam(final String name, final String value)
    {
        return encodeQueryComponent(name) + '=' + encodeQueryComponent(value);
    }

    /**
     * Convenience overload of {@link #encodeParam(String, String)} for int values.
     * 
     * @param name  parameter name.
     * @param value parameter value.
     * @return the encoded pair, e.g. {@code "doc=1234"}.
     */
    public static String encodeParam(final String name, final int value)
    {
        return encodeQueryComponent(name) + '=' + value;
    }

    /**
     * Minimally percent-encode a query-string component (name or value).
     * Encodes characters that are structural in a query string
     * ({@code &amp; = + #}, space) and characters that are URI delimiters
     * per RFC 3986 §2.2 ({@code " < >}).
     * All other characters, including non-ASCII Unicode, pass through
     * unchanged as UTF-8, keeping URLs human-readable.
     * 
     * @param s the raw component string.
     * @return the encoded string.
     */
    static public String encodeQueryComponent(final String s)
    {
        if (s == null)
            return "";
        final int len = s.length();
        StringBuilder sb = null; // lazy — most values need no encoding
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            String esc;
            switch (c) {
            // query-string structural
            case '&':
                esc = "%26";
                break;
            case '=':
                esc = "%3D";
                break;
            case '+':
                esc = "%2B";
                break;
            case '#':
                esc = "%23";
                break;
            case ' ':
                esc = "%20";
                break;
            // RFC 3986 §2.2 delimiters, unsafe in HTML attributes
            case '"':
                esc = "%22";
                break;
            case '<':
                esc = "%3C";
                break;
            case '>':
                esc = "%3E";
                break;
            default:
                if (sb != null) sb.append(c);
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(len + 8);
                sb.append(s, 0, i);
            }
            sb.append(esc);
        }
        return (sb != null) ? sb.toString() : s;
    }

}