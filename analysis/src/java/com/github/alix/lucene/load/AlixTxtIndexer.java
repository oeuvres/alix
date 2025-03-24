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
package com.github.alix.lucene.load;

import static com.github.oeuvres.alix.common.Names.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.Locale;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;

import com.github.oeuvres.alix.lucene.analysis.AnalyzerMeta;
import com.github.oeuvres.alix.util.Dir;

/**
 * A txt indexer
 */
public class AlixTxtIndexer
{
    final static DecimalFormatSymbols frsyms = DecimalFormatSymbols.getInstance(Locale.FRANCE);
    final static DecimalFormat df000 = new DecimalFormat("000", frsyms);
    /** Field type */
    final static String TEXT = "text";
    final static String META = "meta";
    final static String STORE = "store";
    final static String INT = "int";
    final static String FACET = "facet";
    final static String FACETS = "facets";
    /** Lucene writer */
    private final IndexWriter writer;
    /** Keep an hand on the text analyzer */
    private final Analyzer analyzer;

    /**
     * Constructor.
     * @param writer A lucene index to write in.
     */
    public AlixTxtIndexer(final IndexWriter writer) {
        this.writer = writer;
        this.analyzer = writer.getAnalyzer();
    }

    /**
     * Transform txt to TEI
     * 
     * @param file A text file to transform.
     * @return TEI.
     * @throws IOException File errors.
     */
    static public String tei(File file) throws IOException
    {
        BufferedReader br = new BufferedReader(new FileReader(file));
        // destinataire
        String address = br.readLine();
        String[] pers = address.split(" to ");
        if (!pers[0].equals("Voltaire")) {
            br.close();
            return null;
        }
        String dest = pers[1];
        // Lieu d’envoi
        // br.readLine();
        // date
        String dateline = br.readLine();
        int year = Integer.parseInt(dateline.substring(0, 4));

        // passer la ligne;
        br.readLine();
        StringBuffer sb = new StringBuffer();
        String line;
        sb.append("<p>");
        while ((line = br.readLine()) != null) {
            if ("".equals(line))
                sb.append("</p>\n<p>");
            else
                sb.append(line);
        }
        sb.append("</p>\n");
        String text = sb.toString();
        text = text.replaceAll("\\d([  ;,?.])", "$1").replaceAll("&", "&amp;");
        br.close();

        StringBuffer xml = new StringBuffer();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<TEI xmlns=\"http://www.tei-c.org/ns/1.0\">\n");
        xml.append("  <teiHeader>\n");
        xml.append("    <fileDesc>\n");
        xml.append("      <titleStmt>\n");
        xml.append("<title>" + dateline + ", " + address + "</title>\n");
        xml.append("<author>" + dest + "</author>\n");
        xml.append("      </titleStmt>\n");
        xml.append("    </fileDesc>\n");
        xml.append("    <profileDesc>\n");
        xml.append("<creation><date notAfter=\"" + year + "\"/></creation>\n");
        xml.append("    </profileDesc>\n");
        xml.append("  </teiHeader>\n");
        xml.append("  <text>\n");
        xml.append("    <body>\n");
        xml.append("      <div type=\"letter\">\n");
        xml.append(text);
        xml.append("      </div>\n");
        xml.append("    </body>\n");
        xml.append("  </text>\n");
        xml.append("</TEI>\n");
        return xml.toString();
    }

    /**
     * Provide a filename for the documents to be processed. All document from this
     * source will be indexed with this token. This keyword should be unique for the
     * entire index. All documents from this filename will deleted before
     * indexation.
     * 
     * @param file Txt file to load.
     * @throws IOException Lucene errors.
     */

    @SuppressWarnings("resource")
    public void load(File file) throws IOException
    {
        String fileName = file.getName();
        fileName = fileName.substring(0, fileName.lastIndexOf('.'));
        writer.deleteDocuments(new Term(ALIX_FILENAME, fileName));

        String bibl;

        Document book = new Document();
        Document chapter = new Document();
        book.add(new StringField(ALIX_FILENAME, fileName, Store.YES)); // for deletions
        chapter.add(new StringField(ALIX_FILENAME, fileName, Store.YES)); // for deletions

        String bookid = fileName;

        book.add(new StringField(ALIX_BOOKID, bookid, Store.YES));
        book.add(new SortedDocValuesField(ALIX_BOOKID, new BytesRef(bookid))); // keep bookid as a facet
        book.add(new StringField(ALIX_ID, bookid, Store.YES));
        book.add(new SortedDocValuesField(ALIX_ID, new BytesRef(bookid)));
        book.add(new StringField(ALIX_TYPE, BOOK, Store.YES));

        chapter.add(new StringField(ALIX_FILENAME, fileName, Store.YES)); // for deletions
        chapter.add(new StringField(ALIX_BOOKID, bookid, Store.YES));
        chapter.add(new SortedDocValuesField(ALIX_BOOKID, new BytesRef(bookid))); // keep bookid as a facet
        String id = bookid + "_";
        chapter.add(new StringField(ALIX_ID, id, Store.YES));
        chapter.add(new SortedDocValuesField(ALIX_ID, new BytesRef(id)));
        chapter.add(new StringField(ALIX_TYPE, CHAPTER, Store.YES));

        BufferedReader br = new BufferedReader(new FileReader(file));
        String line;
        // destinataire
        line = br.readLine();
        bibl = line;

        String[] pers = line.split(" to ");
        if (!pers[0].equals("Voltaire")) {
            br.close();
            return;
        }

        final String author = "author";
        book.add(new SortedDocValuesField(author, new BytesRef(pers[1])));
        book.add(new StoredField(author, pers[1]));

        chapter.add(new SortedDocValuesField(author, new BytesRef(pers[1])));
        chapter.add(new StoredField(author, pers[1]));
        // Lieu d’envoi
        line = br.readLine();
        if (!"Unknown".equals(line)) {
            book.add(new SortedDocValuesField("place", new BytesRef(line)));
            book.add(new StoredField("place", line));
        }
        // date
        line = br.readLine();
        bibl = line + ", " + bibl;
        int val = Integer.parseInt(line.substring(0, 4));
        String name = "year";

        book.add(new IntPoint(name, val)); // to search
        book.add(new StoredField(name, val)); // to show
        book.add(new NumericDocValuesField(name, val)); // to sort

        book.add(new StoredField("bibl", bibl)); // (TokenStream fields cannot be stored)
        TokenStream ts = new AnalyzerMeta().tokenStream("meta", bibl); // renew token stream
        book.add(new Field("bibl", ts, Alix.ftypeMeta)); // indexation of the chosen tokens
        chapter.add(new StoredField("bibl", bibl)); // (TokenStream fields cannot be stored)
        ts = new AnalyzerMeta().tokenStream("meta", bibl); // renew token stream
        chapter.add(new Field("bibl", ts, Alix.ftypeMeta)); // indexation of the chosen tokens

        chapter.add(new IntPoint(name, val)); // to search
        chapter.add(new StoredField(name, val)); // to show
        chapter.add(new NumericDocValuesField(name, val)); // to sort
        // passer la ligne;
        br.readLine();
        StringBuffer sb = new StringBuffer();
        while ((line = br.readLine()) != null) {
            if ("".equals(line))
                sb.append("\n<p/>\n");
            else
                sb.append(line);
        }
        br.close();
        String text = sb.toString();
        text = text.replaceAll("\\d([  ;,?.])", "$1");

        chapter.add(new StoredField(name, text)); // text has to be stored for snippets and conc
        TokenStream source = analyzer.tokenStream("stats", text);
        chapter.add(new Field(name, source, Alix.ftypeText)); // indexation of the chosen tokens

        // System.out.println(doc);
        writer.addDocument(chapter);
        writer.addDocument(book);
        ts.close();
    }

    /**
     * Direct indexation.
     * 
     * @param args Arguments.
     * @throws Exception All errors.
     */
    public static void main(String[] args) throws Exception
    {

        Path lucpath = Paths.get("/home/fred/code/obvie/WEB-INF/bases/voltaire");
        Dir.rm(lucpath);

        // long time = System.nanoTime();
        /*
         * Alix alix = Alix.instance(lucpath, new FrAnalyzer()); IndexWriter writer =
         * alix.writer(); TxtIndexer indexer = new TxtIndexer(writer);
         */
        File dir = new File("/home/fred/code/voltaire/corrtext");
        File[] files = dir.listFiles();
        Arrays.sort(files);
        // int i = 10;
        for (File f : files) {
            String xml = tei(f);
            if (xml == null)
                continue;

            String fileName = f.getName();
            fileName = fileName.substring(0, fileName.lastIndexOf('.'));

            File out = new File("/home/fred/code/voltaire/tei", fileName + ".xml");
            System.out.println(out);
            FileWriter fileWriter = new FileWriter(out);
            fileWriter.write(xml);
            fileWriter.flush();
            fileWriter.close();
        }

    }

}
