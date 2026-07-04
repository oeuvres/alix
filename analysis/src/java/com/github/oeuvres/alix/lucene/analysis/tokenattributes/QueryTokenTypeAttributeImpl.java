package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

/**
 * Implementation of {@link QueryTokenTypeAttribute}.
 */
public final class QueryTokenTypeAttributeImpl
        extends EnumAttImpl<QueryTokenTypeAttribute.Type>
        implements QueryTokenTypeAttribute
{
    
    /**
     * Creates a query-tokenizer type attribute.
     */
    public QueryTokenTypeAttributeImpl() {
        super(QueryTokenTypeAttribute.class, QueryTokenTypeAttribute.Type.class, QueryTokenTypeAttribute.Type.NONE);
    }
}