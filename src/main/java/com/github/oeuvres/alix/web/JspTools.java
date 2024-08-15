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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * Wrap the global jsp variables.
     * @param page jsp page context.
     */
    public JspTools(final PageContext page) {
        this.request = (HttpServletRequest) page.getRequest();
        this.response = (HttpServletResponse) page.getResponse();
        this.out = page.getOut();
        this.page = page;
    }

    /**
     * Check if a String is significant as parameter value.
     * 
     * @param s String to test.
     * @return true if significant, false otherwise.
     */
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
     * @param name name of the cookie.
     * @return value if cookie set, or null.
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
     * @param name name of the cookie.
     * @param value value to set.
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
     * Ensure that a String could be included in an html attribute with quotes.
     * 
     * @param cs chars to escape.
     * @return String escaped for html.
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
     * Ensure that a String could be included in an html attribute with quotes.
     * 
     * @param s source chars.
     * @return escaped String.
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
     * Get a request parameter as a boolean with a default value.
     * 
     * @param name     Name of a request parameter.
     * @param fallback Default value.
     * @return priority order: request, fallback.
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

    /**
     * Get a request parameter as a boolean with a default value, and an optional
     * cookie persistence.
     * 
     * @param name     Name of a request parameter.
     * @param fallback Default value.
     * @param cookie   Name of a cookie.
     * @return priority order: request, cookie, fallback.
     */
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
     * Useful for servlet 3.0 (and not 3.1).
     * 
     * @param part multipart/form-data
     * @return file name of uploaded file.
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
     * @param name     name of a request parameter.
     * @param fallback default value.
     * @return Priority order: request, fallback.
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
     * @param name     name of http param.
     * @param fallback default value.
     * @param cookie   name of a cookie.
     * @return priority order: request, cookie, fallback.
     */
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
     * @param name     name of http param.
     * @param fallback default value.
     * @return priority order: request, fallback.
     */
    public int getInt(final String name, final int fallback)
    {
        return getInt(name, null, fallback, null);
    }

    /**
     * Get a request parameter as an int with a default value, and a cookie
     * persistence.
     * 
     * @param name     name of an http param.
     * @param fallback default value.
     * @param cookie   name of a cookie.
     * @return priority order: request, cookie, fallback.
     */
    public int getInt(final String name, final int fallback, final String cookie)
    {
        return getInt(name, null, fallback, cookie);
    }

    /**
     * Get a request parameter as an int with a default value, and a cookie
     * persistence.
     * 
     * @param name     name of an http param.
     * @param range [min, max].
     * @param fallback default value.
     * @return priority order: request, fallback.
     */
    public int getInt(final String name, final int[] range, final int fallback)
    {
        return getInt(name, range, fallback, null);
    }

    /**
     * Get a request parameter as an int with a default value, and a cookie
     * persistence. Value should be included in a range.
     * 
     * @param name     name of an http param.
     * @param range [min, max].
     * @param fallback default value.
     * @param cookie   name of a cookie.
     * @return if value in range, priority order: request, cookie, fallback.
     */
    public int getInt(final String name, final int[] range, final int fallback, final String cookie)
    {
        int min = Integer.MIN_VALUE;
        int max = Integer.MAX_VALUE;
        if (range == null);
        else if (range.length < 2);
        else {
            min = Math.min(range[0], range[1]);
            max = Math.max(range[0], range[1]);
        }
        final String parString = request.getParameter(name);
        Integer value = parseInt(parString);
        
        // handle cookie logic
        if (check(cookie)) {
            // param has an empty value, seems that client wants to reset cookie
            // do not give back the stored value
            if (parString != null && !check(parString)) {
                cookie(cookie, null);
            }
            // check if value is valid to set a cookie
            else if (value != null && value >= min && value <= max) {
                cookie(cookie, "" + value);
            }
            else if (value == null) {
                value = parseInt(cookie(cookie));
                // if cookie not valid in range, unset
                if (value != null && (value < min || value > max)) {
                    value = null;
                    cookie(cookie, null);
                }
            }
        }
        // try to send a value
        if (value == null) { // try attribute fallback
            value = getIntegerAtt(name);
        }
        if (value == null) {
            return fallback;
        }
        else if (value < min) { // lower than floor, send floor
            return min;
        }
        else if (value > max) { // upper than ceil, send ceil
            return max;
        }
        else {
            return value;
        }

    }

    /**
     * Use request attribute {@link HttpServletRequest#getAttribute(String)} as a fallback value.
     * 
     * @param name of a request attribute.
     * @return an Integer if was set, null if nothing set or not an Integer.
     */
    private Integer getIntegerAtt(final String name)
    {
        Object att = request.getAttribute(name);
        if (att == null) return null;
        if (att  instanceof Integer) return (Integer)att;
        return null;
    }

    /**
     * Get an int Range between a min and a max (included).
     * 
     * @param name name of an http param.
     * @param range [min, max].
     * @return [] if no value, [] if at least one value outise [min, max], [point] if 1 value, [lower, upper] if 2 values.
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
            return new int[0];
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
            return new int[0];
        }
        else if (pos == 1) {
            if (data[0] < min || data[0] > max) return null;
            return new int[] {data[0]};
        }
        else {
            final int lower = Math.max(Math.min(data[0], data[1]), min);
            final int upper = Math.min(Math.max(data[0], data[1]), max);
            if (lower == min && upper == max) {
                // probably default values, no info
                return new int[0];
            }
            else if (lower == upper) {
                return new int[] {lower};
            }
            else {
                return new int[] {lower, upper};
            }
        }
    }

    /**
     * Returns a set of unique int values.
     * 
     * @param name of an http param.
     * @return array of int values without duplicates.
     */
    public int[] getIntSet(final String name)
    {
        String[] vals = request.getParameterValues(name);
        if (vals == null || vals.length < 1) {
            return new int[0];
        }
        IntList list = new IntList(vals.length);
        for (String val : vals) {
            int value = -1;
            try {
                value = Integer.parseInt(val);
            } catch (Exception e) {
                // output error ?
                continue;
            }
            list.push(value);
        }
        return list.toSet();
    }



    /**
     * Get a request parameter as a String with a default value.
     * 
     * @param name     name of an http param.
     * @param fallback default value.
     * @return priority order: request, fallback.
     */
    public String getString(final String name, final String fallback)
    {
        return getString(name, fallback, null, null);
    }

    /**
     * Get a request parameter as a String with a default value, or optional cookie
     * persistence. Optional set of accepted values.
     * 
     * @param name     name of http param.
     * @param fallback default value.
     * @param set accepted values.
     * @return Priority order: request, fallback.
     */
    public String getString(final String name, final String fallback, final Set<String> set)
    {
        return getString(name, fallback, set, null);
    }
    
    /**
     * Get a request parameter as a String with a default value, or optional cookie
     * persistence. Optional set of accepted values.
     * 
     * @param name     name of http param.
     * @param fallback default value.
     * @param set accepted values.
     * @param cookie   name of a cookie.
     * @return Priority order: request, cookie, fallback.
     */
    public String getString(final String name, final String fallback, final Set<String> set, final String cookie)
    {
        String par = request.getParameter(name);
        // no cookie name, answer fast
        if (!check(cookie) && check(par)) {
            if (set == null) {
                return par;
            }
            else if (set.contains(par)) {
                return par;
            }
            // not an accepted value in the set
            else {
                par = null;
            }
        }
        Object o = request.getAttribute(name);
        String att = null;
        if (o != null && o instanceof String) {
            att = (String) o;
        }
        // no cookie name, answer fast
        if (!check(cookie) && check(att)) {
            if (set == null) {
                return att;
            }
            else if (set.contains(att)) {
                return att;
            }
            else {
                att = null;
            }
        }
        // no cookie, no value,send fallback
        if (!check(cookie)) {
            return fallback;
        }
        
        // now deal with cookie name
        final String cookieValue = cookie(cookie);
        // set cookie with a desired param
        if (check(par)) {
            cookie(cookie, par);
            return par;
        }
        // param is empty (but not null), reset cookie ?
        if (par != null) {
            cookie(name, null);
        }
        if (check(cookieValue)) return cookieValue;
        else return fallback;
    }
    
    /**
     * Get a repeated parameter as an array of String, filtered of empty strings and
     * repeated values. Original order is kept.
     * 
     * @param name name of request parameter.
     * @return null if no plain values.
     */
    public String[] getStringSet(final String name)
    {
        return getStringSet(name, null);
    }


    /**
     * Get a repeated parameter as an array of String, filtered of empty strings,
     * repeated values, and value not accepted in a set. Original order is kept.
     * 
     * @param name name of request parameter.
     * @param set optional, a set of accepted values.
     * @return null if no accepted plain values.
     */
    public String[] getStringSet(final String name, final Set<String> set)
    {
        final String[] empty = new String[0];
        String[] values = request.getParameterValues(name);
        if (values == null)
            return empty;
        List<String> list = new ArrayList<>();
        Set<String> dic = new HashSet<>();
        for (String v : values) {
            if (v == null)
                continue;
            if ("".equals(v.trim()))
                continue;
            if (dic.contains(v))
                continue;
            if (set != null && !set.contains(v)) continue;
            dic.add(v);
            list.add(v);
        }
        if (list.size() < 1)
            return empty;
        return list.toArray(empty);
    }

    /**
     * Return request object.
     *
     * @return request object.
     */
    public HttpServletRequest request()
    {
        return request;
    }

    /**
     * Return response object.
     * 
     * @return response object.
     */
    public HttpServletResponse response()
    {
        return response;
    }

    /**
     * Parse a request parameter value as an int.
     * 
     * @param value String to parse.
     * @return Integer value or null if no Integer found.
     */
    private Integer parseInt(final String value)
    {
        if (value == null) return null;
        if (!check(value)) return null;
        int no;
        try {
            no = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
        Integer ret = no;
        return ret;
    }

    /**
     * Build a query string from a set of parameters names, values are taken from http request.
     *
     * @param pars list of paramter names.
     * @return a query string, ready for a href attribute.
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
