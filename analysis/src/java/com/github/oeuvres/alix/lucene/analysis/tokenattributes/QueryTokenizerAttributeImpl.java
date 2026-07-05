package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

/**
 * Implementation of {@link QueryTokenizerAttribute}.
 */
public final class QueryTokenizerAttributeImpl
        extends EnumAttImpl<QueryTokenizerAttribute.Type>
        implements QueryTokenizerAttribute
{
    
    /**
     * Creates a query-tokenizer type attribute.
     */
    public QueryTokenizerAttributeImpl() {
        super(QueryTokenizerAttribute.class, QueryTokenizerAttribute.Type.class, QueryTokenizerAttribute.Type.NONE);
    }
}