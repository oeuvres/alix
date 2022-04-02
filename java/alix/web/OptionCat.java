/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
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
package alix.web;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;

/**
 * Options for filters by grammatical types
 */
public enum OptionCat implements Option
{

    ALL("Tout", null),
    NOSTOP("Mots “pleins”", new TagFilter().setAll().nostop(true)),
    SUB("Substantifs", new TagFilter().set(Tag.SUB)),
    NAME("Noms propres",
            new TagFilter().set(Tag.NAME).set(Tag.NAMEevent).set(Tag.NAMEgod).set(Tag.NAMEorg).set(Tag.NAMEpeople)),
    VERB("Verbes", new TagFilter().set(Tag.VERB)),
    ADJ("Adjectifs", new TagFilter().set(Tag.ADJ).set(Tag.VERBger)),
    ADV("Adverbes", new TagFilter().set(Tag.ADV)),
    STOP("Mots grammaticaux",
            new TagFilter().setAll().clearGroup(Tag.SUB).clearGroup(Tag.NAME).clear(Tag.VERB).clear(Tag.ADJ).clear(0)),
    UKNOWN("Mots inconnus", new TagFilter().set(0)),
    LOC("Locutions", new TagFilter().setAll().locutions(true)),
    PERS("Personnes",
            new TagFilter().set(Tag.NAME).set(Tag.NAMEpers).set(Tag.NAMEpersf).set(Tag.NAMEpersm).set(Tag.NAMEauthor)
                    .set(Tag.NAMEfict)),
    PLACE("Lieux", new TagFilter().set(Tag.NAMEplace)),
    RS("Autres noms propres",
            new TagFilter().set(Tag.NAME).set(Tag.NAMEevent).set(Tag.NAMEgod).set(Tag.NAMEorg).set(Tag.NAMEpeople)),
    STRONG("Mots “forts”", new TagFilter().set(Tag.SUB).set(Tag.VERB).set(Tag.ADJ).nostop(true)),
    ;
    final public String label;
    final public TagFilter tags;

    private OptionCat(final String label, final TagFilter tags)
    {
        this.label = label;
        this.tags = tags;
    }

    public TagFilter tags()
    {
        return tags;
    }

    public String label()
    {
        return label;
    }

    public String hint()
    {
        return null;
    }
}
