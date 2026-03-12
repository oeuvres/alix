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

/**
 * Streaming ingestion of XML corpora into Lucene indices.
 *
 * <h2>Pipeline overview</h2>
 *
 * <p>Two ingestion paths are available, both ending at the same consumer:</p>
 *
 * <pre>
 * TEI/XML  →  [optional XSLT]  →  alix.xsl  →  AlixSaxHandler  →  AlixDocument  →  AlixLuceneConsumer  →  IndexWriter
 *                                                                                         ↑
 * Alix-native XML  →  AlixXmlIngester  →  AlixSaxHandler  →  AlixDocument  ─────────────┘
 * </pre>
 *
 * <ul>
 *   <li><b>{@link com.github.oeuvres.alix.ingest.TeiIngester}</b> — 
 *       orchestrates the full TEI path: reads an {@link com.github.oeuvres.alix.ingest.IngestConfig},
 *       optionally applies a user-supplied pre-processing XSLT, then runs the bundled
 *       {@code alix.xsl} stylesheet to produce Alix-namespace SAX events. Manages the
 *       Lucene {@code IndexWriter} lifecycle (build into tmp dir, atomic swap on success).</li>
 *   <li><b>{@link com.github.oeuvres.alix.ingest.AlixXmlIngester}</b> — 
 *       direct SAX ingester for documents already in the
 *       {@linkplain com.github.oeuvres.alix.ingest.AlixSaxHandler#ALIX_NS Alix namespace}.
 *       No XSLT, no index management; the caller provides the
 *       {@link com.github.oeuvres.alix.ingest.AlixDocumentConsumer}.</li>
 * </ul>
 *
 * <h2>Core classes</h2>
 *
 * <ul>
 *   <li>{@link com.github.oeuvres.alix.ingest.AlixDocument} — 
 *       mutable document accumulator. Collects fields as {@code (offset, length)} slices
 *       into a shared {@code char[]} buffer. Reused across documents to minimize allocation.</li>
 *   <li>{@link com.github.oeuvres.alix.ingest.AlixSaxHandler} — 
 *       SAX {@link org.xml.sax.ContentHandler} implementing the Alix XML grammar
 *       ({@code alix:set}, {@code alix:book}, {@code alix:document}, {@code alix:chapter},
 *       {@code alix:field}). Validates nesting, manages the book→chapter lifecycle,
 *       and injects synthetic fields ({@code ALIX_FILENAME}, {@code ALIX_BOOKID}, {@code ALIX_ORD}).</li>
 *   <li>{@link com.github.oeuvres.alix.ingest.AlixDocumentConsumer} — 
 *       functional interface: sink for a completed {@link com.github.oeuvres.alix.ingest.AlixDocument}.</li>
 *   <li>{@link com.github.oeuvres.alix.ingest.AlixLuceneConsumer} — 
 *       the standard consumer implementation. Maps {@code AlixDocument} fields to Lucene
 *       {@link org.apache.lucene.document.Document} fields (STORE, INT, CATEGORY, FACET, TEXT)
 *       and writes to an {@link org.apache.lucene.index.IndexWriter}.</li>
 *   <li>{@link com.github.oeuvres.alix.ingest.IngestConfig} — 
 *       immutable configuration loaded from a Java XML {@link java.util.Properties} file.
 *       Resolves TEI file globs, exclusions, XSLT paths, and dictionary/stopword lists
 *       relative to the config file directory.</li>
 * </ul>
 *
 * <h2>Configuration file</h2>
 *
 * <p>The configuration uses the standard Java
 * {@link java.util.Properties#loadFromXML(java.io.InputStream) XML properties} format.
 * Paths are resolved relative to the config file's parent directory.</p>
 *
 * <pre>{@code
 * <?xml version="1.0" encoding="utf-8" ?>
 * <!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
 * <properties>
 *     <!-- corpus id (defaults to config filename stem) -->
 *     <entry key="name">piaget</entry>
 *     <!-- display label -->
 *     <entry key="label">Piaget</entry>
 *     <!-- TEI file globs (one per line, resolved relative to this file) -->
 *     <entry key="tei">
 *     ../corpus/*.xml
 *     </entry>
 *     <!-- optional: exclude patterns -->
 *     <entry key="exclude">
 *     ../corpus/draft_*.xml
 *     </entry>
 *     <!-- optional: pre-processing XSLT applied before alix.xsl -->
 *     <entry key="prexslt">my-prealix.xsl</entry>
 *     <!-- required: parent directory for Lucene indices -->
 *     <entry key="indexroot">../lucene</entry>
 *     <!-- optional: dictionary and stopword files -->
 *     <entry key="dicfile">my-dic.csv</entry>
 *     <entry key="stopfile">my-stop.csv</entry>
 * </properties>
 * }</pre>
 *
 * <h2>Required keys</h2>
 * <ul>
 *   <li><b>tei</b> — glob patterns selecting TEI/XML input files (at least one match required).</li>
 *   <li><b>indexroot</b> — directory that will contain the Lucene index directory.</li>
 * </ul>
 *
 * <h2>Minimal usage</h2>
 *
 * <pre>{@code
 * import com.github.oeuvres.alix.ingest.*;
 * import com.github.oeuvres.alix.lucene.analysis.fr.FrenchAnalyzer;
 * import com.github.oeuvres.alix.util.Report.ReportConsole;
 * import java.nio.file.Path;
 *
 * Report rep = new ReportConsole();
 * TeiIngester ingester = new TeiIngester(rep);
 * IngestConfig cfg = IngestConfig.load(Path.of("corpus.xml"), rep);
 * ingester.ingest(cfg, new FrenchAnalyzer());
 * }</pre>
 *
 * <p>This reads the configuration, expands TEI file globs, builds a Lucene index
 * into a temporary directory under {@code indexroot}, and atomically swaps it
 * into place on success.</p>
 *
 * @see com.github.oeuvres.alix.ingest.TeiIngester
 * @see com.github.oeuvres.alix.ingest.IngestConfig
 * @see com.github.oeuvres.alix.ingest.AlixSaxHandler
 */
package com.github.oeuvres.alix.ingest;
