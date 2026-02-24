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
package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.LemmaFilter;
import com.github.oeuvres.alix.lucene.analysis.MLFilter;
import com.github.oeuvres.alix.lucene.analysis.MLTokenizer;
import com.github.oeuvres.alix.lucene.analysis.PosTaggingFilter;
import com.github.oeuvres.alix.lucene.analysis.SentenceStartLowerCaseFilter;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.ProbAttribute;
import com.github.oeuvres.alix.util.Dir;

import opennlp.tools.postag.POSModel;

public class FrenchLemmaRun
{
    private static Analyzer buildAnalyzer(final POSModel model) {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new MLTokenizer(FrenchLexicons.getDotEndingWords());
                TokenStream stream = tokenizer;
                stream = new MLFilter(stream);
                stream = new FrenchCliticSplitFilter(stream);
                stream = new SentenceStartLowerCaseFilter(stream, FrenchLexicons.getLemmaLexicon());
                stream = new PosTaggingFilter(stream, model, PosTaggingFilter.HYPHEN_REWRITER);
                stream = new LemmaFilter(stream, FrenchLexicons.getLemmaLexicon());
                /* here, to think, switches and forks for different fields */
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }
    
    public static void main(String[] args) throws Exception {
        
        
        final POSModel model = loadModel();
        Path dest = Paths.get("target/postagging-test.tsv");
        try (
            Analyzer analyzer = buildAnalyzer(model);
                PrintWriter out = new PrintWriter(Files.newBufferedWriter(dest, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)); 
        ) {
            // Writer out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
            List<Path> paths = Dir.ls("D:/code/piaget_xml/piaget1970*.xml");
            out.println("form\tpos\tprob\tlemma");
            for (Path p : paths) {
                System.out.print("#" + p + '\n');
                try (BufferedReader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8);
                    TokenStream ts = analyzer.tokenStream("contents", reader)
                )
                {
                    dump(out, ts);
                }
            }
        }
    }
    
    public static void dump(PrintWriter out, final TokenStream ts) throws IOException {
        Objects.requireNonNull(ts, "ts");

        final CharTermAttribute termAtt = ts.getAttribute(CharTermAttribute.class);
        final PosAttribute posAtt = ts.getAttribute(PosAttribute.class);
        final ProbAttribute probAtt = ts.getAttribute(ProbAttribute.class);
        final LemmaAttribute lemmaAtt = ts.getAttribute(LemmaAttribute.class);

        ts.reset();
        while (ts.incrementToken()) {
            final int pos = posAtt.getPos();
            final double prob = probAtt.getProb();
            String lemma = (lemmaAtt != null)?lemmaAtt.toString():"";

            out.printf(
                Locale.ROOT,
                "%s\t%s\t%.5f\t%s%n",
                escape(termAtt.toString()),
                Upos.get(pos).name(),
                prob,
                lemma
            );
        }
        ts.end();
    }
    
    public static String escape(final String s) {
        if (s == null) return "null";
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
    
    private static POSModel loadModel() throws IOException {
        final String spec = "/com/github/oeuvres/alix/fr/opennlp-fr-ud-gsd-pos-1.3-2.5.4.bin";
        InputStream in = FrenchPosTaggingFilterDemo.class.getResourceAsStream(spec);
        try (InputStream autoClose = in) {
            return new POSModel(autoClose);
        }
    }
}
