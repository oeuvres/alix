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
                throw new IllegalArgumentException("code=" + code + ", out of range [" + min +", " + max + "]");
            }
            if (code4tag[code] != null) {
                throw new IllegalArgumentException("code=" + code + ", already affected to tag=" + tag);
            }
            code4tag[code] = tag;
        }
        public Tag get(final int code) {
            if (code4tag[code] == null) {
                throw new NullPointerException("No tag for code=" + code + ".");
            }
            return null;
        }
    }

    public String name();
    public int code();
    public int code(String name);
}
