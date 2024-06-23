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

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import javax.servlet.jsp.JspWriter;
import javax.servlet.jsp.PageContext;

import com.github.oeuvres.alix.util.IntList;

/**
 * Jsp toolbox.
 */
public class JspTools
{
    /** Jsp page context */
    public final PageContext page;
    /** Original request */
    public final HttpServletRequest request;
    /** Original response */
    public final HttpServletResponse response;
    /** Where to write */
    public final JspWriter out;
    /** Cookie */
    HashMap<String, String> cookies;
    /** for cookies */
    private final static int MONTH = 60 * 60 * 24 * 30;

    /** Wrap the global jsp variables */
    public JspTools(final PageContext page) {
        this.request = (HttpServletRequest) page.getRequest();
        this.response = (HttpServletResponse) page.getResponse();
        this.out = page.getOut();
        this.page = page;
    }

    /** Check if a String is significant */
    public static boolean check(String s)
    {
        if (s == null)
            return false;
        s = s.trim();
        if (s.length() == 0)
            return false;
        if ("null".equals(s))
            return false;
        return true;
    }

    /**
     * Get a cookie value by name.
     * 
     * @param name
     * @return null if not set
     */
    public String cookie(final String name)
    {
        if (!check(name))
            return null;
        if (cookies == null) {
            Cookie[] cooks = request.getCookies();
            if (cooks == null)
                return null;
            cookies = new HashMap<String, String>();
            for (int i = 0; i < cooks.length; i++) {
                Cookie cook = cooks[i];
                cookies.put(cook.getName(), cook.getValue());
            }
        }
        return cookies.get(name);
    }

    /**
     * Send a cookie to client.
     * 
     * @param name
     * @param value
     */
    public void cookie(String name, String value)
    {
        if (!check(name))
            return;
        if (value == null) {
            if (cookie(name) != null)
                response.addHeader("Set-Cookie", name + "=" + value
                        + "; HttpOnly; SameSite=strict; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT");
        } else {
            response.addHeader("Set-Cookie", name + "=" + value + ";Max-Age=" + MONTH + "; HttpOnly; SameSite=strict");
        }
    }

    /**
     * Ensure that a String could be included in an html attribute with quotes
     */
    public static String escape(final CharSequence cs)
    {
        if (cs == null)
            return "";
        final StringBuilder out = new StringBuilder(Math.max(16, cs.length()));
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            if (c == '"')
                out.append("&quot;");
            else if (c == '<')
                out.append("&lt;");
            else if (c == '>')
                out.append("&gt;");
            else if (c == '&')
                out.append("&amp;");
            else
                out.append(c);
        }
        return out.toString();
    }

    /**
     * Escape HTML for input
     * 
     * @param out
     * @param cs
     * @throws IOException Lucene errors.
     */
    public static void escape(final Writer out, final CharSequence cs) throws IOException
    {
        if (cs == null)
            return;
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            if (c == '"')
                out.append("&quot;");
            else if (c == '<')
                out.append("&lt;");
            else if (c == '>')
                out.append("&gt;");
            else if (c == '&')
                out.append("&amp;");
            else
                out.append(c);
        }
        return;
    }

    /**
     * Ensure that a String could be included in an html attribute with quotes
     */
    public static String escUrl(final String s)
    {
        if (s == null)
            return "";
        final StringBuilder out = new StringBuilder(Math.max(16, s.length()));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"')
                out.append("&quot;");
            else if (c == '<')
                out.append("&lt;");
            else if (c == '>')
                out.append("&gt;");
            else if (c == '&')
                out.append("&amp;");
            else if (c == '+')
                out.append("%2B");
            else
                out.append(c);
        }
        return out.toString();
    }

    /**
     * Get a request parameter as a boolean with a defaul value.
     */
    public boolean getBoolean(final String name, final boolean fallback)
    {
        String value = request.getParameter(name);
        if ("false".equals(value) || "0".equals(value) || "null".equals(value))
            return false;
        if (check(value))
            return true;
        return fallback;
    }

    public boolean getBoolean(final String name, final boolean fallback, final String cookie)
    {
        String value = request.getParameter(name);
        // value explicitly defined to false, set a cookie
        if ("false".equals(value) || "0".equals(value) || "null".equals(value)) {
            cookie(cookie, "0");
            return false;
        }
        // some content, we are true
        if (check(value)) {
            cookie(cookie, "1");
            return true;
        }
        // param is empty but not null, reset cookie
        if (value != null) {
            cookie(name, null);
            return fallback;
        }
        // try to deal with cookie
        value = cookie(cookie);
        if ("0".equals(value))
            return false;
        if (check(value))
            return true;
        // cookie seems to have a problem, reset it
        cookie(name, null);
        return fallback;
    }

    /**
     * Get a request parameter as a boolean with a default value, and an optional
     * cookie persistence.
     * 
     * @param name     Name of a request parameter.
     * @param fallback Default value.
     * @param cookie   Name of a cookie is given as an Enum to control cookies
     *                 proliferation.
     * @return Priority order: request, cookie, fallback.
     */
    public boolean getBoolean(final String name, final boolean fallback, final Enum<?> cookie)
    {
        return getBoolean(name, fallback, cookie.name());
    }

    /**
     * Get a request parameter as an {@link Enum} value that will ensure a closed
     * list of values, with a default value if wrong.
     * 
     * @param name     Name of a request parameter.
     * @param fallback Default value.
     * @return The value from Enum.
     */
    // @SuppressWarnings({ "unchecked", "static-access" })
    public Enum<?> getEnum(final String name, final Enum<?> fallback)
    {
        if (fallback == null) {
            throw new IllegalArgumentException(
                    "fallback can’t be null, a value is needed to get the exact class name of Enum");
        }
        String value = request.getParameter(name);
        if (!check(value)) {
            return fallback;
        }
        // try/catch seems a bit heavy, but behind valueOf, there is a lazy static Map
        // optimized for Enum
        try {
            Enum<?> ret = Enum.valueOf(fallback.getDeclaringClass(), value);
            return ret;
        } catch (Exception e) {
            return (Enum<?>) fallback;
        }
    }

    /**
     * Get a request parameter as an {@link Enum} value that will ensure a closed
     * list of values, with a default value if wrong.
     * 
     * @param name     Name of a request parameter.
     * @param fallback Default value.
     * @param cookie   Cookie persistency.
     * @return The value from Enum.
     */
    public Enum<?> getEnum(final String name, final Enum<?> fallback, final String cookie)
    {
        if (fallback == null) {
            throw new IllegalArgumentException(
                    "fallback can’t be null, a value is needed to get the exact class name of Enum");
        }
        String value = request.getParameter(name);
        if (check(value)) {
            try {
                @SuppressWarnings("static-access")
                Enum<?> ret = fallback.valueOf(fallback.getDeclaringClass(), value);
                cookie(cookie, ret.name());
                return ret;
            } catch (Exception e) {
            }
        }
        // param is empty but not null, reset cookie
        if (value != null) {
            cookie(name, null);
            return fallback;
        }
        // try to deal with cookie
        value = cookie(cookie);
        try {
            @SuppressWarnings("static-access")
            Enum<?> ret = fallback.valueOf(fallback.getDeclaringClass(), value);
            return ret;
        } catch (Exception e) {
            // cookie seenms to have a problem, reset it
            cookie(name, null);
            return (Enum<?>) fallback;
        }
    }

    /**
     * Get a request parameter as an {@link Enum} value that will ensure a closed
     * list of values, with a default value, and an optional cookie persistence.
     * 
     * @param name     Name of a request parameter.
     * @param fallback Default value.
     * @param cookie   Name of a cookie for persistence.
     * @return Priority order: request, cookie, fallback.
     */
    public Enum<?> getEnum(final String name, final Enum<?> fallback, final Enum<?> cookie)
    {
        return getEnum(name, fallback, cookie.name());
    }

    /**
     * Useful for servlet 3.0 (and not 3.1)
     * 
     * @param part
     * @return
     */
    static public String getFileName(Part part)
    {
        for (String cd : part.getHeader("content-disposition").split(";")) {
            if (cd.trim().startsWith("filename")) {
                String fileName = cd.substring(cd.indexOf('=') + 1).trim().replace("\"", "");
                // MSIE fix
                return fileName.substring(fileName.lastIndexOf('/') + 1).substring(fileName.lastIndexOf('\\') + 1);
            }
        }
        return null;
    }

    /**
     * Get a request parameter as a float with default value.
     * 
     * @param name
     * @param fallback
     * @return
     */
    public float getFloat(final String name, final float fallback)
    {
        String value = request.getParameter(name);
        if (check(value)) {
            try {
                float ret = Float.parseFloat(value);
                return ret;
            } catch (NumberFormatException e) {
            }
        }
        return fallback;
    }

    /**
     * Get a request parameter as a float with a default value, and a cookie
     * persistence.
     * 
     * @param name     Name of http param.
     * @param fallback Default value.
     * @param cookie   Name of a cookie is given as an Enum to control cookies
     *                 proliferation.
     * @return Priority order: request, cookie, fallback.
     */
    public float getFloat(final String name, final float fallback, final Enum<?> cookie)
    {
        return getFloat(name, fallback, cookie.name());
    }

    public float getFloat(final String name, final float fallback, final String cookie)
    {
        String value = request.getParameter(name);
        if (check(value)) {
            try {
                float ret = Float.parseFloat(value);
                ;
                cookie(cookie, "" + ret); // value seems ok, store it as a cookie
                return ret;
            } catch (NumberFormatException e) {
            }
        }
        // reset cookie
        if (value != null && !check(value)) {
            cookie(name, null);
            return fallback;
        }
        value = cookie(cookie);
        if (value == null)
            return fallback;
        // verify stored value before send it
        try {
            float ret = Integer.parseInt(value);
            return ret;
        } catch (NumberFormatException e) {
            // bad cookie value, reset it
            cookie(name, null);
            return fallback;
        }
    }

    /**
     * Get a request parameter as an int with a default value.
     * 
     * @param name
     * @param fallback
     * @return
     */
    public int getInt(final String name, final int fallback)
    {
        return getInt(name, null, fallback, null);
    }

    public int getInt(final String name, final int fallback, final String cookie)
    {
        return getInt(name, null, fallback, cookie);
    }

    public int getInt(final String name, final int[] span, final int fallback)
    {
        return getInt(name, span, fallback, null);
    }

    public int getInt(final String name, final int[] span, final int fallback, final String cookie)
    {
        String par = request.getParameter(name);
        // param has an empty value, seems that client wants to reset cookie
        // do not give back the stored value
        if (cookie != null && par != null && !check(par)) {
            cookie(cookie, null);
            return fallback;
        }
        if (check(par)) { // a value, work after
        } else if (cookie != null) { // no par, try cookie
            par = cookie(cookie);
            if (!check(par))
                return fallback;
        } else { // no par, no cookie, good bye
            return fallback;
        }
        int value = 0;
        boolean found = false;
        // try to parse
        try {
            value = Integer.parseInt(par);
            found = true;
        } catch (NumberFormatException e) {
            return fallback;
        }
        if (span != null && span.length > 0) {
            if (value < span[0]) {
                value = span[0];
                found = false;
            } else if (span.length > 1 && value > span[1]) {
                value = span[0];
                found = false;
            }
        }

        if (!found) {
            // perhaps an old cookie with a bad value, delete it
            if (cookie != null)
                cookie(cookie, null);
            return fallback;
        }
        if (cookie != null)
            cookie(cookie, "" + value);
        return value;
    }

    /**
     * Get an int Range between a min and a max (included).
     * @param name Name of an http param
     * @param range range[0] = min value, range[1] = max
     * @return null if no value, [point] if one value, [lower, upper] if 2 value
     */
    public int[] getIntRange(final String name, final int[] range)
    {
        int min = Integer.MIN_VALUE;
        int max = Integer.MAX_VALUE;
        if (range == null);
        else if (range.length < 2);
        else {
            min = Math.min(range[0], range[1]);
            max = Math.max(range[0], range[1]);
        }
        String[] values = request.getParameterValues(name);
        if (values == null || values.length < 1) {
            return null;
        }
        final int[] data = new int[2];
        int pos = 0;
        for (String v : values) {
            int value;
            try {
                value = Integer.parseInt(v);
            } 
            catch (Exception e) {
                continue;
            }
            data[pos++] = value;
            if (pos == 2) break;
        }
        if (pos == 0) {
            return null;
        }
        else if (pos == 1) {
            if (data[0] < min || data[0] > max) return null;
            return new int[] {data[0]};
        }
        else {
            final int lower = Math.max(Math.min(data[0], data[1]), min);
            final int upper = Math.min(Math.max(data[0], data[1]), max);
            if (lower == upper) {
                return new int[] {lower};
            }
            if (lower == min && upper == max) return null;
            return new int[] {lower, upper};
        }
    }

    /**
     * Return
     * 
     * @param name
     * @return
     */
    public int[] getIntSet(final String name)
    {
        String[] vals = request.getParameterValues(name);
        if (vals == null || vals.length < 1) {
            return new int[0];
        }
        IntList list = new IntList(vals.length);
        for (String val : vals) {
            int i = -1;
            try {
                i = Integer.parseInt(val);
            } catch (Exception e) {
                // output error ?
                continue;
            }
            list.put(i, 1);
        }
        return list.toSet();
    }

    /**
     * Get a value from a map (usually static)
     */
    public Object getMap(final String name, Map<String, ?> map, String fallback)
    {
        String value = request.getParameter(name);
        if (map.containsKey(value)) {
            return map.get(value);
        }
        if (fallback == null) {
            return null;
        }
        return map.get(fallback);
    }

    /**
     * Get a value from a map (usually static), with cookie persistance
     */
    public Object getMap(final String name, Map<String, ?> map, String fallback, String cookie)
    {
        String value = request.getParameter(name);
        // a value requested, let’s try it
        if (check(value)) {
            // it works, store it and send it
            if (map.containsKey(value)) {
                cookie(cookie, value);
                return map.get(value);
            }
        }
        // value is not null but empty, reset cookie, return default
        if (value != null && "".equals(value.trim())) {
            cookie(name, null);
            return map.get(fallback);
        }
        // bad request or null request, try to get cookie
        value = cookie(cookie);
        // bad memory, things has changed, reset cookie
        if (!map.containsKey(value)) {
            cookie(name, null);
            value = fallback;
        }
        return map.get(value);
    }

    /**
     * Get a request parameter as a String with a default value.
     */
    public String getString(final String name, final String fallback)
    {
        return getString(name, fallback, ""); // cookie=null produce ambig
    }

    /**
     * Get a request parameter as a String with a default value, and a cookie
     * persistency.
     */
    public String getString(final String name, final String fallback, final String cookie)
    {
        String value = request.getParameter(name);
        if (check(value)) {
            // we want a string, not html
            value = value.replaceAll("<[^>]*>", "");
        }
        // no cookie, answer fast
        if (!check(cookie)) {
            if (check(value))
                return value;
            else
                return fallback;
        }

        if (check(cookie) && check(value)) {
            cookie(cookie, value);
            return value;
        }
        // param is not null, for example empty string, reset cookie
        if (check(cookie) && value != null) {
            cookie(name, null);
            return fallback;
        }
        // try to deal with cookie
        value = cookie(cookie);
        if (check(value)) {
            return value;
        }
        // cookie seems to have a problem, reset it
        cookie(name, null);
        return fallback;
    }

    /**
     * Get a request parameter as a String with a default value, or optional cookie
     * persistence.
     * 
     * @param name     Name of http param.
     * @param fallback Default value.
     * @param cookie   Name of a cookie is given as an Enum to control cookies
     *                 proliferation.
     * @return Priority order: request, cookie, fallback.
     */
    public String getString(final String name, final String fallback, final Enum<?> cookie)
    {
        return getString(name, fallback, cookie.name());
    }

    /**
     * Get a request parameter as a String from a list of value (first is default)
     */
    public String getStringOf(final String name, final Set<String> set, final String fallback)
    {
        String value = request.getParameter(name);
        if (value == null) {
            return fallback;
        }
        if (set.contains(value)) {
            return value;
        }
        return fallback;
    }

    /**
     * Get a repeated parameter as an array of String, filtered of empty strings and
     * repeated values. Original order is kept, first seen,
     * 
     * @param name Name of request parameter
     * @return Null if no plain values
     */
    public String[] getStringSet(final String name)
    {
        String[] values = request.getParameterValues(name);
        if (values == null)
            return null;
        List<String> list = new ArrayList<>();
        Set<String> dic = new HashSet<>();
        for (String v : values) {
            if (v == null)
                continue;
            if ("".equals(v.trim()))
                continue;
            if (dic.contains(v))
                continue;
            dic.add(v);
            list.add(v);
        }
        if (list.size() < 1)
            return null;
        return list.toArray(new String[0]);
    }

    /**
     * Return request object, maybe useful in context of a method
     */
    public HttpServletRequest request()
    {
        return request;
    }

    /**
     * Return response object, maybe useful in context of a method
     */
    public HttpServletResponse response()
    {
        return response;
    }

    /**
     * Build url parameters
     */
    public String queryString(final String[] pars)
    {
        StringBuilder href = new StringBuilder();
        boolean first = true;
        for (String par : pars) {
            String value = request.getParameter(par);
            if (value == null)
                continue;
            if (first) {
                first = false;
                // href.append("?");
            } else {
                href.append("&amp;");
            }
            href.append(par).append("=").append(escape(value));
        }
        return href.toString();
    }

}
