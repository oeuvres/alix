package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

public interface QueryTermAttribute extends EnumAtt<QueryTermAttribute.Type>
{
    /**
     * Describes whether and how a query token is executable against the index.
     */
    public enum Type {
        /**
         * The term exists exactly in the index.
         */
        EXACT,

        /**
         * No searchable indexed term or pattern was found.
         */
        NONE,

        /**
         * The token is a prefix pattern with at least one indexed expansion.
         */
        PREFIX,

        /**
         * The token was rewritten to an indexed term.
         */
        RESOLVED,

        /**
         * The token is a wildcard pattern with at least one indexed expansion.
         */
        WILDCARD
    }
}
