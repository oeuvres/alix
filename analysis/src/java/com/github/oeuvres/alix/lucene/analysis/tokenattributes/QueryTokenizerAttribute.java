package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

public interface QueryTokenizerAttribute extends EnumAtt<QueryTokenizerAttribute.Type>
{
    /**
     * Query-tokenizer token type.
     */
    public enum Type {
        /**
         * No tokenizer type has been assigned.
         */
        NONE,

        /**
         * Closing parenthesis.
         */
        PAREN_CLOSE,

        /**
         * Opening parenthesis.
         */
        PAREN_OPEN,

        /**
         * Prefix or wildcard pattern.
         */
        PATTERN,

        /**
         * Quoted expression emitted as one token.
         */
        QUOTED,

        /**
         * Ordinary query word.
         */
        WORD
    }
    
}
