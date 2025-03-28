package com.github.oeuvres.alix.common;


/**
 * Contract and tool for linguistic tool.
 */
public interface Tag {

    static class Index{
        private final int min;
        private final int max;
        private final Tag[] code4tag;
        public Index(final int min, final int max) {
            if (min < 0) {
                throw new IllegalArgumentException("min=" + min + " < 0, out of range.");
            }
            if (min < 0) {
                throw new IllegalArgumentException("max=" + max + " < min=" + min+", out of range");
            }
            this.min = min;
            this.max = max;
            code4tag = new Tag[max + 1];
        }
        public void add(int code, Tag tag) {
            if (code < min || code > max) {
                throw new IllegalArgumentException("code=" + code + " for “" + tag + "”, out of range [" + min +", " + max + "]");
            }
            if (code4tag[code] != null) {
                throw new IllegalArgumentException("code=" + code  + " for “" + tag + ", already affected to tag=" + code4tag[code]);
            }
            code4tag[code] = tag;
        }
        /**
         * Returns a {@link Tag} by code, or null if code out of range, or if 
         * no tag for this code.
         * @param code {@link Tag#code()}-
         * @return tag for this code if any or null.
         */
        public Tag get(final int code) {
            if (code < min || code > max) return null;
            return code4tag[code];
        }
    }

    public String name();
    public int code();
    public int code(String name);
}
