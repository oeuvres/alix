/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
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
package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.AttributeReflector;

/**
 * Default mutable implementation of {@link LemmaAttribute}.
 *
 * <p>The class follows Lucene's attribute naming convention, allowing the
 * default attribute factory to resolve {@link LemmaAttribute} to
 * {@code LemmaAttributeImpl}. Character storage and state-copy semantics are
 * inherited from {@link CharAttImpl}.</p>
 */
public final class LemmaAttributeImpl extends CharAttImpl implements LemmaAttribute
{
    @Override
    public void reflectWith(AttributeReflector reflector) {
        reflector.reflect(LemmaAttribute.class, "inflected", value());
    }
    /**
     * Constructs an empty lemma attribute.
     */
    public LemmaAttributeImpl()
    {
        super();
    }
}
