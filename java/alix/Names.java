/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
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
package alix;


public final class Names
{
    /** For a cookie  */
    public static final String ALIX_BASE = "alix.base";
    /** Mandatory field, unique id for a book and its chapters */
    public static final String ALIX_BOOKID = "alix.bookid";
    /** Mandatory field, XML source file name, used for update */
    public static final String ALIX_FILENAME = "alix.filename";
    /** Mandatory field, unique id provide by user for all documents */
    public static final String ALIX_ID = "alix.id";
    /** Mandatory field, define the level of a leaf (book/chapter, article) */
    public static final String ALIX_TYPE = "alix.type";
    /** <alix:article> atomic text indexed as a document */
    public static final String ARTICLE = "article";
    /** <alix:book> (contains <alix:chapter>)  */
    public static final String BOOK = "book";
    /** Field type */
    public final static String CATEGORY = "category";
    /** <alix:chapter> text inside a book  */
    public static final String CHAPTER = "chapter";
    /** <alix:document> independant document  */
    public static final String DOCUMENT = "document";
    /** Field type */
    public final static String FACET = "facet";
    /** <alix:field>  */
    public static final String FIELD = "field";
    /** Field type */
    public final static String HTML = "html";
    /** Field type */
    public final static String INT = "int";
    /** Field type */
    public final static String META = "meta";
    /** For information */
    public final static String NOTFOUND = "notfound";
    /** For information */
    public final static String NOTALIX = "notalix";
    /** Name of a field to inform Analyzer it is a search query */
    public static final String SEARCH = "search";
    /** Field type */
    public final static String STRING = "string";
    /** Field type */
    public final static String STORE = "store";
    /** Field type */
    public final static String TEXT = "text";
    /** Field type */
    public final static String TOKEN = "token";
    /** Field type */
    public final static String UNKNOWN = "unknown";
    /** Field type */
    public final static String XML = "xml";

    private Names() {
        // restrict instantiation
    }
}
