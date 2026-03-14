package com.github.oeuvres.alix.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal diagnostic callback, usable at any layer without
 * a logging framework dependency.
 *
 * <p>Implementations range from silent ({@link ReportNull})
 * to console ({@link ReportConsole}) to accumulating
 * ({@link ReportList}) for request-scoped diagnostics.</p>
 */
public interface Report
{
    void debug(String msg);
    void info(String msg);
    void warn(String msg);
    void error(String msg);

    /** Silent sink. */
    public final class ReportNull implements Report
    {
        public static final ReportNull INSTANCE = new ReportNull();
        private ReportNull() {}
        @Override public void debug(String msg) {}
        @Override public void info(String msg)  {}
        @Override public void warn(String msg)  {}
        @Override public void error(String msg) {}
    }

    /** Prints to stdout/stderr. */
    public final class ReportConsole implements Report
    {
        @Override
        public void debug(String msg)
        {
            System.out.println(msg);
        }

        @Override
        public void info(String msg)
        {
            System.out.println(msg);
        }

        @Override
        public void warn(String msg)
        {
            System.err.println(msg);
        }

        @Override
        public void error(String msg)
        {
            System.err.println(msg);
        }
    }

    /**
     * Accumulates messages for later retrieval.
     *
     * <p>Intended as a request-scoped diagnostic buffer: create one
     * per request, pass it into code that may encounter problems,
     * then inspect or serialize the collected messages before
     * sending the response.</p>
     *
     * <p>Not thread-safe — one instance per request thread.</p>
     */
    public final class ReportList implements Report
    {
        /** A single diagnostic message with its severity. */
        public record Entry(Level level, String msg) {}

        public enum Level { DEBUG, INFO, WARN, ERROR }

        private final List<Entry> entries = new ArrayList<>();

        @Override public void debug(String msg) { entries.add(new Entry(Level.DEBUG, msg)); }
        @Override public void info(String msg)  { entries.add(new Entry(Level.INFO, msg));  }
        @Override public void warn(String msg)  { entries.add(new Entry(Level.WARN, msg));  }
        @Override public void error(String msg) { entries.add(new Entry(Level.ERROR, msg)); }

        /** All accumulated entries, in order. */
        public List<Entry> entries()
        {
            return Collections.unmodifiableList(entries);
        }

        /** True if any message at WARN or ERROR level was recorded. */
        public boolean hasProblems()
        {
            for (Entry e : entries) {
                if (e.level == Level.WARN || e.level == Level.ERROR) return true;
            }
            return false;
        }

        /** True if no messages were recorded. */
        public boolean isEmpty()
        {
            return entries.isEmpty();
        }

        /**
         * Concatenates all messages into one string, separated by {@code "; "}.
         * Useful for a compact error response.
         */
        public String summary()
        {
            if (entries.isEmpty()) return "";
            if (entries.size() == 1) return entries.get(0).msg();
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(entries.get(i).msg());
            }
            return sb.toString();
        }

        /** Discards all accumulated messages. */
        public void clear()
        {
            entries.clear();
        }
    }
}