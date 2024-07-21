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
package com.github.oeuvres.alix.cli;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.fr.Tag.TagFilter;
import com.github.oeuvres.alix.lucene.analysis.FilterLemmatize;
import com.github.oeuvres.alix.lucene.analysis.FilterFrPersname;
import com.github.oeuvres.alix.lucene.analysis.TokenizerFr;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;
/**
 * Adhoc tool to extract Names.
 */
@Command(name = "Balinoms", description = "Tag names in an XML/TEI file", mixinStandardHelpOptions = true)
public class Balinoms implements Callable<Integer>
{
    static class Name implements Comparable<Name>
    {
        int count = 1;
        int tag = Tag.NAME.flag;
        String form;

        Name(final String form) {
            this.form = form;
        }

        public void inc()
        {
            count++;
        }

        @Override
        public int compareTo(Name o)
        {
            return o.count - count;
        }
    }

    static class AnalyzerNames extends Analyzer
    {

        @Override
        protected TokenStreamComponents createComponents(String fieldName)
        {
            final Tokenizer source = new TokenizerFr();
            TokenStream result = new FilterLemmatize(source);
            result = new FilterFrPersname(result);
            return new TokenStreamComponents(source, result);
        }

    }

    static Analyzer anaNoms = new AnalyzerNames();

    static class Entry implements Comparable<Entry>
    {
        int count;
        final int flag;
        final String form;

        Entry(final String form, final int flag) {
            this.form = form;
            this.flag = flag;
            this.count = 1;
        }

        @Override
        public int compareTo(Entry e)
        {
            int dif = e.count - this.count;
            if (dif == 0)
                return this.form.compareTo(e.form);
            return dif;
        }
    }

    Map<String, Entry> dic = new HashMap<>();

    @Parameters(arity = "1..*", description = "au moins un fichier XML/TEI à baliser")
    File[] files;

    @Override
    public Integer call() throws Exception
    {
        for (final File src : files) {
            String name = src.getName().substring(0, src.getName().lastIndexOf('.'));
            String ext = src.getName().substring(src.getName().lastIndexOf('.'));
            String dest = src.getParent() + "/" + name + "_alix" + ext;
            System.out.println(src + " > " + dest + "…");
            long time = System.nanoTime();
            parse(new String(Files.readAllBytes(Paths.get(src.toString())), StandardCharsets.UTF_8),
                    new PrintWriter(dest));
            ;
            Files.write(Paths.get(src.getParent() + "/" + name + "_names.tsv"), top(-1, null).getBytes("UTF-8"));
            dic.clear();
            System.out.println(((System.nanoTime() - time) / 1000000) + " ms.");
        }
        System.out.println("C’est fini");
        return 0;
    }

    /**
     * Tag name in an XML string.
     * 
     * @param xml The source XML content.
     * @param out A writer for different destinations.
     * @throws IOException Lucene errors.
     */
    public void parse(String xml, PrintWriter out) throws IOException
    {
        TokenStream stream = anaNoms.tokenStream("stats", new StringReader(xml));
        @SuppressWarnings("unused")
        int toks = 0;
        int begin = 0;
        //
        final CharsAttImpl termAtt = (CharsAttImpl) stream.addAttribute(CharTermAttribute.class);
        final CharsAttImpl orthAtt = (CharsAttImpl) stream.addAttribute(OrthAtt.class);
        final CharsAttImpl lemAtt = (CharsAttImpl) stream.addAttribute(LemAtt.class);
        final OffsetAttribute attOff = stream.addAttribute(OffsetAttribute.class);
        final FlagsAttribute attFlags = stream.addAttribute(FlagsAttribute.class);
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
                    // System.out.println("term=" + termAtt + " orth=" + orthAtt + " lem=" +
                    // lemAtt);
                    if (!orthAtt.isEmpty())
                        lemAtt.append(orthAtt);
                    else
                        lemAtt.append(termAtt);
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

    }

    /**
     * Increment dics. This way should limit object creation.
     * 
     * @param chars Increment a term entry.
     * @param flag  Flag of the term.
     */
    private void inc(final CharsAttImpl chars, final int flag)
    {
        @SuppressWarnings("unlikely-arg-type")
        Entry entry = dic.get(chars);
        if (entry == null) {
            String lem = chars.toString();
            dic.put(lem, new Entry(lem, flag));
        } else {
            entry.count++;
        }
    }

    /**
     * Get the top list.
     * 
     * @param limit  Count of terms selected.
     * @param filter Filter of grammar tags.
     * @return A TSV formatted String.
     */
    public String top(final int limit, TagFilter filter)
    {
        StringBuffer sb = new StringBuffer();
        Entry[] lexiq = dic.values().toArray(new Entry[0]);
        Arrays.sort(lexiq);
        sb.append("forme\ttype\teffectif\n");
        int n = 1;
        for (Entry entry : lexiq) {
            sb.append(entry.form + "\t" + Tag.name(entry.flag) + "\t" + entry.count + "\n");
            if (n == limit)
                break;
            n++;
        }
        return sb.toString();
    }

    /**
     * Run the Class.
     * 
     * @param args Command line arguments.
     * @throws IOException File problems.
     */
    public static void main(String args[]) throws IOException
    {
        int exitCode = new CommandLine(new Balinoms()).execute(args);
        System.exit(exitCode);
    }

}
