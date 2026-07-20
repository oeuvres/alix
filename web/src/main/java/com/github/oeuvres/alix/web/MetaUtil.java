package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

/**
 * Accumulator for the {@code meta} block of a JSON response. An
 * instance captures its construction time and collects arbitrary
 * named entries which a subclass can later emit as JSON or as an
 * HTML list.
 *
 * <p>The reported {@code timeMs} is measured from
 * {@code OpMeta} construction, not from request start; instantiate
 * the meta first to get a meaningful elapsed time.</p>
 *
 * <p>Reserved entry names emitted by {@link #toJson} are
 * {@code status}, {@code params}, {@code paramsSource} and
 * {@code timeMs}. Using these as keys in {@link #put} produces a
 * JSON document with duplicate names.</p>
 */
public class MetaUtil
{
    private final Map<String, Object> entries = new LinkedHashMap<>();
    private final List<String> log = new LinkedList<>();
    long t0 = System.nanoTime();
    
    /**
     * Get back an object from meta
     * 
     * Returns the value to which the specified key is mapped, or null if this map contains no mapping for the key.
     */
    public Object get(final String key)
    {
        return entries.get(key);
    }

    /**
     * Add a log message to output.
     */
    public void log(String message)
    {
        log.add(message);
    }


    /**
     * Records a {@code boolean} meta entry.
     *
     * @param key   entry name
     * @param value entry value
     */
    public void put(final String key, final boolean value)
    {
        entries.put(key, Boolean.valueOf(value));
    }
    /**
     * Records a {@code double} meta entry.
     *
     * @param key   entry name
     * @param value entry value
     */
    public void put(final String key, final double value)
    {
        entries.put(key, Double.valueOf(value));
    }

    /**
     * Records a {@code Float} meta entry. Emitted by
     * {@link Op#jsonObject} via its {@code toString} fallback,
     * since {@code Float} is not in the natively typed set.
     *
     * @param key   entry name
     * @param value entry value
     */
    public void put(final String key, final float value)
    {
        entries.put(key, Float.valueOf(value));
    }

    /**
     * Records an {@code int} meta entry.
     *
     * @param key   entry name
     * @param value entry value
     */
    public void put(final String key, final int value)
    {
        entries.put(key, Integer.valueOf(value));
    }

    /**
     * Records an {@code int[]} meta entry.
     *
     * @param key   entry name
     * @param values entry values
     */
    public void put(final String key, final int[] values)
    {
        entries.put(key, values);
    }
    
    /**
     * Records an {@code String[]} meta entry.
     *
     * @param key   entry name
     * @param values entry values
     */
    public void put(final String key, final String[] values)
    {
        entries.put(key, values);
    }
    
    /**
     * Records a {@code long} meta entry.
     *
     * @param key   entry name
     * @param value entry value
     */
    public void put(final String key, final long value)
    {
        entries.put(key, Long.valueOf(value));
    }

    /**
     * Records a {@code String} meta entry.
     *
     * @param key   entry name
     * @param value entry value
     */
    public void put(final String key, final String value)
    {
        entries.put(key, value);
    }
    
    /**
     * Records a generic {@code Object} meta entry.
     *
     * @param key   entry name
     * @param value entry value
     */
    public void put(final String key, final Object value)
    {
        entries.put(key, value);
    }
    
    /**
     * Remove an entry.
     * 
     * @param key  entry name
     * @return old entry {@code Object} or null if none
     */
    public Object remove(final String key)
    {
        return entries.remove(key);
    }


    /**
     * Renders the accumulated entries as an HTML {@code <li>}
     * sequence, intended for embedding inside a list element of an
     * HTML diagnostic page. Keys and values are not escaped: callers
     * must not put untrusted content into the entries.
     *
     * @return the {@code <li>} fragments concatenated, possibly empty
     * @throws IOException 
     */
    public void toHtml(final Appendable writer, final HttpPars pars) throws IOException
    {
        if (!entries.isEmpty()) {
            writer.append("<ul>\n");
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                writer.append("<li>" + e.getKey() + ": '" + e.getValue() + "'</li>\n");
            }
            writer.append("</ul>\n");
        }
        if (pars != null) {
            writer.append("<ol>\n");
            for (Map.Entry<String, HttpPars.Resolved> entry : pars.resolvedParams().entrySet()) {
                writer.append("<li>")
                .append(entry.getKey())
                .append(": ")
                .append(entry.getValue().toString())
                .append("</li>\n");
            }
            writer.append("</ol>\n");
        }
    }

    /**
     * Emits the meta content as a sequence of JSON members:
     * {@code status} (from the response), {@code params} and
     * {@code paramsSource} (echo of resolved parameters and their
     * source), each entry recorded via {@link #put}, then
     * {@code timeMs} (elapsed since this instance was constructed).
     *
     * <p>This method does not open or close an enclosing object;
     * the caller must wrap the call between {@code beginObject()}
     * and {@code endObject()}.</p>
     *
     * @param jw   target Gson writer, already inside an object
     * @param pars resolved parameters, used to read the response
     *             status and the parameter echo
     * @throws IOException if writing fails
     */
    public void toJson(final JsonWriter jw, final HttpPars pars) throws IOException
    {
        jw.name("status").value(pars.response().getStatus());
        jw.name("params").beginObject();
        for (Map.Entry<String, HttpPars.Resolved> e : pars.resolvedParams().entrySet()) {
            jw.name(e.getKey());
            jsonObject(jw, e.getValue().value());
        }
        jw.endObject();
        jw.name("paramsSource").beginObject();
        for (Map.Entry<String, HttpPars.Resolved> e : pars.resolvedParams().entrySet()) {
            jw.name(e.getKey()).value(e.getValue().source().name());
        }
        jw.endObject();
        if (log.size() > 0) {
            jw.name("log").beginObject();
            jw.beginArray();
            for (String message: log) {
                jw.value(message);
            }
            jw.endArray();
        }
        for (Map.Entry<String, Object> e : entries.entrySet()) {
            jw.name(e.getKey());
            jsonObject(jw, e.getValue());
        }
        jw.name("timeMs").value((System.nanoTime() - t0) / 1_000_000);
    }
    

    public void toString(final Appendable writer, final HttpPars pars) throws IOException
    {
        for (Map.Entry<String, Object> e : entries.entrySet()) {
            writer.append(e.getKey() + ": '" + e.getValue() + "'\n");
        }
        for (Map.Entry<String, HttpPars.Resolved> entry : pars.resolvedParams().entrySet()) {
            writer
            .append(entry.getKey())
            .append(": ")
            .append(entry.getValue().toString())
            .append("\n");
        }
    }
    
    /**
     * Writes a Java value as the matching JSON token. Supports the types
     * produced by {@link HttpPars.Resolved#value()}: {@code Integer},
     * {@code Long}, {@code Double}, {@code Boolean}, {@code String},
     * {@code Enum} (written as its {@code name()}), {@code int[]} and
     * {@code String[]} (written as JSON arrays). {@code null} is written
     * as JSON {@code null}. Any other type falls through to
     * {@code toString()}.
     *
     * @param jw target Gson writer
     * @param v  value to emit, or {@code null}
     * @throws IOException if writing fails
     */
    protected static void jsonObject(final JsonWriter jw, final Object v) throws IOException
    {
        if (v == null) {
            jw.nullValue();
            return;
        }
        if (v instanceof Integer i) {
            jw.value(i);
            return;
        }
        if (v instanceof Long l) {
            jw.value(l);
            return;
        }
        if (v instanceof Double d) {
            if (Double.isNaN(d)) jw.value("NaN");
            else jw.value(d);
            return;
        }
        if (v instanceof Boolean b) {
            jw.value(b);
            return;
        }
        if (v instanceof String s) {
            jw.value(s);
            return;
        }
        if (v instanceof Enum<?> e) {
            jw.value(e.name());
            return;
        }
        if (v instanceof int[] arr) {
            jw.beginArray();
            for (int x : arr)
                jw.value(x);
            jw.endArray();
            return;
        }
        if (v instanceof String[] arr) {
            jw.beginArray();
            for (String s : arr)
                jw.value(s);
            jw.endArray();
            return;
        }
        jw.value(v.toString());
    }

}
