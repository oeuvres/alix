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
package com.github.oeuvres.alix.web.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <h2>Source tracking</h2>
 * <p>Every typed getter records the name, the effective value it returned,
 * and the {@link Source} it came from. The servlet can then emit a
 * diagnostics block in its response showing what was asked, what was used,
 * and why:</p>
 * <pre>{@code
 * {
 *   "meta": {
 *     "params":       { "field": "text", "top": 50, "idfexp": 1.3 },
 *     "paramsSource": { "field": "DEFAULT", "top": "HTTP", "idfexp": "COOKIE" }
 *   }
 * }
 * }</pre>
 * <p>Entries are recorded in the order getters are called, so the output
 * reflects the code path of the servlet. Values retain their JVM type
 * (Integer, Double, Boolean, String, Enum, int[]) so JSON emitters can
 * preserve type fidelity.</p>
 *
 * <p>Also provides static utilities for query-string building and
 * parameter value testing.</p>
 */
public class HttpPars
{
    /**
     * Where an effective parameter value came from, in priority order.
     */
    public enum Source {
        /** Value came from {@link HttpServletRequest#getParameter}. */
        HTTP,
        /** Value came from {@link HttpServletRequest#getAttribute} (e.g. forward dispatch). */
        ATTRIBUTE,
        /** Value came from a persisted cookie. */
        COOKIE,
        /** No source yielded a valid value; the fallback was used. */
        FALLBACK
    }

    /**
     * Immutable record of one resolved parameter: the effective value
     * returned by a typed getter, and the {@link Source} that supplied it.
     *
     * @param value  effective value (typed, may be {@code null} only for fallback)
     * @param source where the value came from
     */
    public record Resolved(Object value, Source source) {
        public String toString()
        {
            return "'" + value + "' (" + source + ")";
        }

    }

    /** Wrapped request, source of parameters and attributes. */
    public final HttpServletRequest request;
    /** Wrapped response, used for cookie persistence (null for read-only usage). */
    public final HttpServletResponse response;
    /** Lazily populated cookie cache, keyed by cookie name. */
    private HashMap<String, String> cookies;
    /** Insertion-ordered log of resolved parameters, populated by every typed getter. */
    private final LinkedHashMap<String, Resolved> resolvedParams = new LinkedHashMap<>();
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
     * Records a resolution and returns the value unchanged. Every typed
     * getter funnels its return points through this method so the
     * {@link #resolvedParams()} log stays in sync with what callers saw.
     *
     * @param <T>    value type
     * @param name   parameter name
     * @param value  effective value
     * @param source where the value came from
     * @return {@code value}
     */
    private <T> T record(final String name, final T value, final Source source)
    {
        resolvedParams.put(name, new Resolved(value, source));
        return value;
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
     * Encode a single {@code name=value} query-string pair, using the same
     * minimal percent-encoding as {@link #queryString(String...)}.
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
        StringBuilder sb = null;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            String esc;
            switch (c) {
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
            case '"':
                esc = "%22";
                break;
            case '<':
                esc = "%3C";
                break;
            case '>':
                esc = "%3E";
                break;
            case '\n':
                esc= "%0A";
                break;
            case '\r':
                esc= "%0D";
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
            return record(name, false, Source.HTTP);
        if (hasValue(value))
            return record(name, true, Source.HTTP);
        return record(name, fallback, Source.FALLBACK);
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
            return record(name, false, Source.HTTP);
        }
        if (hasValue(value)) {
            cookie(cookie, "1");
            return record(name, true, Source.HTTP);
        }
        if (value != null) {
            cookie(cookie, null);
            return record(name, fallback, Source.FALLBACK);
        }
        value = cookie(cookie);
        if ("0".equals(value))
            return record(name, false, Source.COOKIE);
        if (hasValue(value))
            return record(name, true, Source.COOKIE);
        cookie(cookie, null);
        return record(name, fallback, Source.FALLBACK);
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
            return record(name, fallback, Source.FALLBACK);
        }
        try {
            return record(name, Enum.valueOf(fallback.getDeclaringClass(), value), Source.HTTP);
        } catch (IllegalArgumentException e) {
            return record(name, fallback, Source.FALLBACK);
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
                return record(name, ret, Source.HTTP);
            } catch (IllegalArgumentException e) {
                // bad param, fall through
            }
        }
        if (value != null) {
            cookie(cookie, null);
            return record(name, fallback, Source.FALLBACK);
        }
        value = cookie(cookie);
        try {
            return record(name, Enum.valueOf(fallback.getDeclaringClass(), value), Source.COOKIE);
        } catch (Exception e) {
            cookie(cookie, null);
            return record(name, fallback, Source.FALLBACK);
        }
    }

    /**
     * Resolve a request parameter as a double.
     *
     * @param name     parameter name.
     * @param fallback value returned when absent or unparseable.
     * @return resolved double.
     */
    public double getDouble(final String name, final double fallback)
    {
        String value = request.getParameter(name);
        if (hasValue(value)) {
            try {
                return record(name, Double.parseDouble(value), Source.HTTP);
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return record(name, fallback, Source.FALLBACK);
    }

    /**
     * Resolve a request parameter as a double with cookie persistence.
     * Priority: request parameter → cookie → fallback.
     * An empty (non-null) parameter resets the cookie.
     *
     * @param name     parameter name.
     * @param fallback value returned when neither parameter nor cookie yield a valid double.
     * @param cookie   cookie name for persistence.
     * @return resolved double.
     */
    public double getDouble(final String name, final double fallback, final String cookie)
    {
        String value = request.getParameter(name);
        if (hasValue(value)) {
            try {
                double ret = Double.parseDouble(value);
                cookie(cookie, "" + ret);
                return record(name, ret, Source.HTTP);
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        if (value != null && !hasValue(value)) {
            cookie(cookie, null);
            return record(name, fallback, Source.FALLBACK);
        }
        value = cookie(cookie);
        if (value == null)
            return record(name, fallback, Source.FALLBACK);
        try {
            return record(name, Double.parseDouble(value), Source.COOKIE);
        } catch (NumberFormatException e) {
            cookie(cookie, null);
            return record(name, fallback, Source.FALLBACK);
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
     * An empty (non-null) parameter resets the cookie and returns the fallback.
     * An out-of-range parameter is clamped; the cookie is not updated.
     *
     * @param name     parameter name.
     * @param range    {@code [min, max]} bounds (inclusive), or null for unclamped.
     * @param fallback default value when no source yields a result.
     * @param cookie   cookie name for persistence, or null to disable.
     * @return resolved int, clamped to range.
     */
    public int getInt(final String name, final int[] range, final int fallback, final String cookie)
    {
        final int min, max;
        if (range != null && range.length >= 2) {
            min = Math.min(range[0], range[1]);
            max = Math.max(range[0], range[1]);
        } else {
            min = Integer.MIN_VALUE;
            max = Integer.MAX_VALUE;
        }

        final String parString = request.getParameter(name);

        if (parString != null && !hasValue(parString)) {
            cookie(cookie, null);
            return record(name, fallback, Source.FALLBACK);
        }

        final Integer fromPar = parseInt(parString);
        if (fromPar != null) {
            if (fromPar >= min && fromPar <= max && cookie != null) {
                cookie(cookie, String.valueOf(fromPar));
            }
            final int clamped = fromPar < min ? min : fromPar > max ? max : fromPar;
            return record(name, clamped, Source.HTTP);
        }

        if (parString == null) {
            final Object att = request.getAttribute(name);
            if (att instanceof Integer) {
                final int fromAtt = (Integer) att;
                final int clamped = fromAtt < min ? min : fromAtt > max ? max : fromAtt;
                return record(name, clamped, Source.ATTRIBUTE);
            }
        }

        final Integer fromCookie = parseInt(cookie(cookie));
        if (fromCookie != null) {
            if (fromCookie < min || fromCookie > max) {
                cookie(cookie, null);
                return record(name, fallback, Source.FALLBACK);
            }
            return record(name, (int) fromCookie, Source.COOKIE);
        }

        return record(name, fallback, Source.FALLBACK);
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
            return record(name, new int[0], Source.FALLBACK);
        }
        Integer value0 = null;
        try {
            value0 = Integer.valueOf(values[0]);
        } catch (NumberFormatException e) {
            // leave null
        }
        if (values.length == 1) {
            if (value0 == null) {
                return record(name, new int[0], Source.FALLBACK);
            } else if (value0 >= min && value0 <= max) {
                return record(name, new int[] { value0 }, Source.HTTP);
            } else {
                return record(name, new int[0], Source.FALLBACK);
            }
        }
        Integer value1 = null;
        try {
            value1 = Integer.parseInt(values[1]);
        } catch (NumberFormatException e) {
            // leave null
        }
        if (value0 == null && value1 == null) {
            return record(name, new int[0], Source.FALLBACK);
        }
        final int[] data = new int[2];
        if (value0 == null) {
            if (value1 < min) return record(name, new int[0], Source.FALLBACK);
            data[0] = min;
            data[1] = Integer.min(value1, max);
        } else if (value1 == null) {
            if (value0 > max) return record(name, new int[0], Source.FALLBACK);
            data[0] = Integer.max(value0, min);
            data[1] = max;
        } else {
            data[0] = Integer.max(Integer.min(value0, value1), min);
            data[1] = Integer.min(Integer.max(value0, value1), max);
        }
        return record(name, data, Source.HTTP);
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
            return record(name, new int[0], Source.FALLBACK);
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
        final int[] out = Arrays.copyOf(dst, dstPos);
        return record(name, out, out.length == 0 ? Source.FALLBACK : Source.HTTP);
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
        final boolean useCookie = hasValue(cookie);
        
        // 1) Explicit request parameter always has precedence.
        // If present but invalid/blank, it suppresses lower-priority sources.
        final String valuePar = request.getParameter(name);
        if (valuePar != null) {
            final String value = acceptString(valuePar, set);
            if (value != null) {
                if (useCookie) cookie(cookie, value);
                return record(name, value, Source.HTTP);
            }
            if (useCookie) cookie(cookie, null);
            return record(name, fallback, Source.FALLBACK);
        }

        // 2) Request attribute
        final Object valueAtt = request.getAttribute(name);
        if (valueAtt instanceof String) {
            final String att = acceptString((String) valueAtt, set);
            if (att != null) {
                return record(name, att, Source.ATTRIBUTE);
            }
        }
        
        // 3) Cookie
        if (useCookie) {
            final String cookieRaw = cookie(cookie);
            final String cookieValue = acceptString(cookieRaw, set);
            if (cookieValue != null) {
                return record(name, cookieValue, Source.COOKIE);
            }
            if (cookieRaw != null) {
                cookie(cookie, null); // clear stale/invalid cookie
            }
        }

        // 4) Fallback
        return record(name, fallback, Source.FALLBACK);
    }
    
    private String acceptString(final String value, final Set<String> set)
    {
        if (!hasValue(value)) return null;
        if (set != null && !set.contains(value)) return null;
        return value;
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
            return record(name, empty, Source.FALLBACK);
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
            return record(name, empty, Source.FALLBACK);
        final String[] out = list.toArray(empty);
        return record(name, out, Source.HTTP);
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
     * Convenience view: ordered map of {@code name → value} across all
     * resolved parameters. Suitable for direct JSON emission as the
     * {@code meta.params} block.
     *
     * @return ordered map of effective values.
     */
    public Map<String, Object> params()
    {
        final LinkedHashMap<String, Object> out = new LinkedHashMap<>(resolvedParams.size());
        for (Map.Entry<String, Resolved> e : resolvedParams.entrySet()) {
            out.put(e.getKey(), e.getValue().value());
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Convenience view: ordered map of {@code name → Source} across all
     * resolved parameters. Suitable for direct JSON emission as the
     * {@code meta.paramsSource} block.
     *
     * @return ordered map of sources.
     */
    public Map<String, Source> paramsSource()
    {
        final LinkedHashMap<String, Source> out = new LinkedHashMap<>(resolvedParams.size());
        for (Map.Entry<String, Resolved> e : resolvedParams.entrySet()) {
            out.put(e.getKey(), e.getValue().source());
        }
        return Collections.unmodifiableMap(out);
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
     * JavaScript), use {@link #queryStringRaw(String...)} instead.</p>
     *
     * @param names parameter names to include (absent parameters are skipped).
     * @return the query string fragment, or empty string if no parameters have values.
     * @see #queryStringRaw(String...)
     */
    public String queryString(final String... names)
    {
        return queryString(true, names);
    }

    /**
     * Build a query string with raw {@code &} separators, suitable for
     * programmatic URI construction (redirects, {@code Location} headers,
     * JavaScript). Same encoding rules as {@link #queryString(String...)},
     * but without HTML entity escaping of the separator.
     *
     * @param names parameter names to include (absent parameters are skipped).
     * @return the query string fragment, or empty string if no parameters have values.
     * @see #queryString(String...)
     */
    public String queryStringRaw(final String... names)
    {
        return queryString(false, names);
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
     * Return the recorded resolution for one parameter, or {@code null}
     * if no getter has been called for that name yet.
     *
     * @param name parameter name.
     * @return the recorded {@link Resolved}, or null.
     */
    public Resolved resolved(final String name)
    {
        return resolvedParams.get(name);
    }

    /**
     * Return an unmodifiable, insertion-ordered view of every parameter
     * that a typed getter has resolved so far. Keys appear in the order
     * the getters were called.
     *
     * @return ordered map of {@code name → Resolved}.
     */
    public Map<String, Resolved> resolvedParams()
    {
        return Collections.unmodifiableMap(resolvedParams);
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
     * Internal query-string builder.
     *
     * @param htmlSep if true, join pairs with {@code &amp;}; otherwise {@code &}.
     * @param names   parameter names.
     * @return the query string fragment.
     */
    private String queryString(final boolean htmlSep, final String... names)
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
}
