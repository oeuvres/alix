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
package alix.sqlite;

import java.io.File;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import alix.fr.Tag;
import alix.lucene.analysis.FrLemFilter;
import alix.lucene.analysis.FrPersnameFilter;
import alix.lucene.analysis.FrTokenizer;
import alix.lucene.analysis.LocutionFilter;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.analysis.tokenattributes.CharsLemAtt;
import alix.lucene.analysis.tokenattributes.CharsOrthAtt;

/**
 * Index words and lemma a set of html files in an SQLite base
 */
public class Insert {

    /**
     * A lucene analyzer to get lems
     */
    static class AnalyzerSite extends Analyzer {
        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            final Tokenizer source = new FrTokenizer();
            TokenStream result = new FrLemFilter(source);
            result = new LocutionFilter(result);
            result = new FrPersnameFilter(result);
            return new TokenStreamComponents(source, result);
        }

    }
    /** Instantiation of lem */
    static Analyzer ana = new AnalyzerSite();
    
    /**
     * Ensure Auto increment
     */
    static private class OrthEntry
    {
        static int autoinc;
        final int id;
        final CharsAtt form;
        OrthEntry(final CharsAtt form) {
            this.form = form;
            this.id = ++autoinc;
        }
    }
    static private class LemEntry
    {
        static int autoinc;
        final int id;
        final CharsAtt form;
        LemEntry(final CharsAtt form) {
            this.form = form;
            this.id = ++autoinc;
        }
    }
    
    static HashMap<CharsAtt, LemEntry> lem = new HashMap<>();
    static HashMap<CharsAtt, OrthEntry> orth = new HashMap<>();

    /** Sqlite Connection */
    Connection conn;
    
    /**
    * Connect to a sample database
    */
   public static void connect(final File sqlite) {
       
       /*
       Connection conn = null;
       try {
           // db parameters
           String url = "jdbc:sqlite:C:/sqlite/db/chinook.db";
           // create a connection to the database
           conn = DriverManager.getConnection(url);
           
           System.out.println("Connection to SQLite has been established.");
           
       } catch (SQLException e) {
           System.out.println(e.getMessage());
       } finally {
           try {
               if (conn != null) {
                   conn.close();
               }
           } catch (SQLException ex) {
               System.out.println(ex.getMessage());
           }
       }
       */
   }

   /**
    * For incremental index, load dictionaries from base
    */
    public static void dicsLoad()
    {
        
    }

    
    public static void parse(File file)
    {
        
    }
    
    /**
     * First, populate dics to optimize 
     * @param xml
     */
    public void dics(String xml)
    {
        /*
        TokenStream stream = ana.tokenStream("stats", new StringReader(xml));
        int toks = 0;
        int begin = 0;
        //
        final CharsAtt termAtt = (CharsAtt) stream.addAttribute(CharTermAttribute.class);
        final CharsAtt orthAtt = (CharsAtt) stream.addAttribute(CharsOrthAtt.class);
        final CharsAtt lemAtt = (CharsAtt) stream.addAttribute(CharsLemAtt.class);
        try {
            stream.reset();
            // print all tokens until stream is exhausted
            while (stream.incrementToken()) {
                toks++;
                final int flag = attFlags.getFlags();
                // TODO test to avoid over tagging ?
                if (!Tag.NAME.sameParent(flag))
                    continue;
                // Should not arrive, but it arrives
                if (lemAtt.isEmpty()) {
                    // System.out.println("term=" + termAtt + " orth=" + orthAtt + " lem=" + lemAtt);
                    if (!orthAtt.isEmpty()) lemAtt.append(orthAtt);
                    else lemAtt.append(termAtt);
                }

                out.print(xml.substring(begin, attOff.startOffset()));
                begin = attOff.endOffset();
                if (Tag.NAMEplace.flag == flag) {
                    out.print("<placeName>");
                    out.print(xml.substring(attOff.startOffset(), attOff.endOffset()));
                    out.print("</placeName>");
                    inc(lemAtt, Tag.NAMEplace.flag);
                }
                // personne
                else if (Tag.NAMEpers.flag == flag || Tag.NAMEfict.flag == flag) {
                    out.print("<persName key=\"" + lemAtt + "\">");
                    out.print(xml.substring(attOff.startOffset(), attOff.endOffset()));
                    out.print("</persName>");
                    inc(lemAtt, Tag.NAMEpers.flag);
                }
                // non repéré supposé personne
                else if (Tag.NAME.flag == flag) {
                    out.print("<persName key=\"" + lemAtt + "\">");
                    out.print(xml.substring(attOff.startOffset(), attOff.endOffset()));
                    out.print("</persName>");
                    inc(lemAtt, Tag.NAMEpers.flag);
                } else { // || Tag.NAMEauthor.flag == flag
                    out.print("<name>");
                    out.print(xml.substring(attOff.startOffset(), attOff.endOffset()));
                    out.print("</name>");
                    inc(lemAtt, Tag.NAME.flag);
                }

            }

            stream.end();
        } finally {
            stream.close();
            // analyzer.close();
        }
        out.print(xml.substring(begin));
        out.flush();
        out.close();
        */
    }
    
}
