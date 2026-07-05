package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

/**
 * Implementation of {@link QueryTokenizerAttribute}.
 */
public final class QueryTermAttributeImpl
        extends EnumAttImpl<QueryTokenizerAttribute.Type>
        implements QueryTokenizerAttribute
{
    
    /**
     * Creates a query-tokenizer type attribute.
     */
    public QueryTermAttributeImpl() {
        super(QueryTokenizerAttribute.class, QueryTokenizerAttribute.Type.class, QueryTokenizerAttribute.Type.NONE);
    }
}