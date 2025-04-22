package com.github.oeuvres.alix.lucene.index;

import static com.github.oeuvres.alix.common.Flags.*;
import static com.github.oeuvres.alix.fr.TagFr.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import com.github.oeuvres.alix.fr.TagFr;
import com.github.oeuvres.alix.lucene.analysis.FilterAposHyphenFr;
import com.github.oeuvres.alix.lucene.analysis.FilterFrPos;
import com.github.oeuvres.alix.lucene.analysis.FilterHTML;
import com.github.oeuvres.alix.lucene.analysis.FilterLemmatize;
import com.github.oeuvres.alix.lucene.analysis.FilterLocution;
import com.github.oeuvres.alix.lucene.analysis.TokenizerML;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Analyse an XML/TEI corpus to output a custom text designed for a word2vec training.
 */
@Command(name = "Analyze", description = "Analyse an XML/TEI corpus to output a custom text designed for a word2vec training.")
public class Analyze4vec extends Cli implements Callable<Integer>
{
    final static String APP = "alix.corpus4vec";

    @Parameters(index = "1", arity = "1", paramLabel = "corpus.txt", description = "1 destination text file for analyzed corpus.")
    /** Destination text file. */
    File dstFile;
    @Override
    public Integer call() throws Exception
    {
        long time = System.nanoTime();
        parse(conf);
        paths.sort(null);
        Analyzer analyzer = new Analyzer4vec();
        Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(dstFile), "UTF-8"));
        dstFile.getCanonicalFile().getParentFile().mkdirs();
        for (final Path path: paths) {
            System.out.println(path);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    new FileInputStream(path.toFile())
                , "UTF-8")
            );
            unroll(analyzer.tokenStream("", reader), writer);
        }
        analyzer.close();
        writer.close();
        System.out.println(
                "[" + APP + "] " + dstFile + " written in " + ((System.nanoTime() - time) / 1000000) + " ms.");
        return 0;
    }
    
    private static void unroll(final TokenStream tokenStream, final Writer writer) throws IOException
    {
        

        final CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
        final FlagsAttribute flagsAtt = tokenStream.addAttribute(FlagsAttribute.class);
        tokenStream.reset();
        int startLast = 0;
        while(tokenStream.incrementToken()) {
            final int flags = flagsAtt.getFlags();
            if (flags == PUNsection.code) {
                writer.write('\n');
                writer.write('\n');
                continue;
            }
            else if (flags == PUNpara.code) {
                writer.write('\n');
                writer.write('\n');
                continue;
            }
            else if (flags == PUNsent.code) {
                // out.write(10);
                continue;
            }
            else if (PUN.isPun(flags)) {
                continue;
            }
            else {
                char[] chars = termAtt.buffer();
                final int len = termAtt.length();
                for (int i = 0; i < len; i++) {
                    if (chars[i] == ' ') chars[i] = '_';
                }
                writer.write(termAtt.buffer(), 0, termAtt.length());
                writer.write(' ');
            }
        }
        tokenStream.close();
    }
    
    public class Analyzer4vec extends Analyzer
    {
        /**
         * Default constructor.
         */
        public Analyzer4vec()
        {
            super();
        }

        @SuppressWarnings("resource")
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new TokenizerML();
            TokenStream ts = tokenizer; // segment words
            // interpret html tags as token events like para or section
            ts = new FilterHTML(ts);
            // fr split on ’ and -
            ts = new FilterAposHyphenFr(ts);
            // pos tagging before lemmatize
            ts = new FilterFrPos(ts);
            // provide lemma+pos
            ts = new FilterLemmatize(ts);
            // group compounds after lemmatization for verbal compounds
            ts = new FilterLocution(ts);
            // last filter èrepare term to index
            ts = new Filter4vec(ts);
            return new TokenStreamComponents(tokenizer, ts);
        }
    }
    
    /**
     * A final token filter before indexation, to plug after a lemmatizer filter,
     * providing most significant tokens for word cloud. Index lemma instead of
     * forms when available. Strip punctuation and numbers. Positions of striped
     * tokens are deleted. This allows simple computation of a token context (ex:
     * span queries, co-occurrences).
     */
    public class Filter4vec extends TokenFilter
    {
        /** The term provided by the Tokenizer */
        private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
        /** The position increment (inform it if positions are stripped) */
        private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
        /** A linguistic category as a short number, see {@link TagFr} */
        private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
        /** A normalized orthographic form */
        private final OrthAtt orthAtt = addAttribute(OrthAtt.class);
        /** A lemma when possible */
        private final LemAtt lemAtt = addAttribute(LemAtt.class);
        /** keep right position order */
        private int skippedPositions;
        /** Convert flags as tag to append to term */
        static String[] suffix = new String[256];
        static {
            suffix[VERB.code] = "_VERB"; // 305875
            suffix[SUB.code] = ""; // 110522
            suffix[ADJ.code] = "_ADJ"; // 67833
            suffix[VERBger.code] = "_VERB"; // 8207
            suffix[ADV.code] = "_ADV"; // 2336
            suffix[VERBppas.code] = "_VERB"; // 1107
            suffix[VERBexpr.code] = "_VERB"; // 270
            suffix[NUM.code] = ""; // 254
            suffix[EXCL.code] = ""; // 166
            suffix[VERBmod.code] = "_VERB"; // 91
            suffix[VERBaux.code] = "_AUX"; // 89
            suffix[PREP.code] = "_MG"; // 71
            suffix[PROpers.code] = "_MG"; // 51
            suffix[ADVscen.code] = "_MG"; // 33
            suffix[DETindef.code] = "_MG"; // 31
            suffix[PROindef.code] = "_MG"; // 28
            suffix[PROdem.code] = "_MG"; // 27
            suffix[ADVasp.code] = "_MG"; // 24
            suffix[ADVdeg.code] = "_MG"; // 23
            suffix[PROrel.code] = "_MG"; // 18
            suffix[PROquest.code] = "_MG"; // 16
            suffix[CONJsub.code] = "_MG"; // 16
            suffix[DETposs.code] = "_MG"; // 15
            suffix[ADVconj.code] = "_MG"; // 15
            suffix[DETart.code] = "_MG"; // 11
            suffix[DETdem.code] = "_MG"; // 10
            suffix[CONJcoord.code] = "_MG"; // 10
            suffix[ADVneg.code] = "_MG"; // 9
            suffix[ADVquest.code] = "_MG"; // 4
            suffix[DETprep.code] = "_MG"; // 4
            suffix[DETnum.code] = "_MG"; // from locutions
        }

        /**
         * Default constructor.
         * @param input previous filter.
         */
        public Filter4vec(TokenStream input) {
            super(input);
        }

        @Override
        public final boolean incrementToken() throws IOException
        {
            // skipping positions will create holes, the count of tokens will be different
            // from the count of positions
            skippedPositions = 0;
            while (input.incrementToken()) {
                // no position for XML between words
                if (flagsAtt.getFlags() == XML.code) {
                    continue;
                }
                if (accept()) {
                    if (skippedPositions != 0) {
                        posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + skippedPositions);
                    }
                    return true;
                }
                skippedPositions += posIncrAtt.getPositionIncrement();
            }
            return false;
        }

        /**
         * Most of the tokens are not rejected but rewrited, except punctuation.
         * 
         * @return true if accepted
         */
        protected boolean accept()
        {
            final int flags = flagsAtt.getFlags();
            if (flags == TEST.code) {
                System.out.println(termAtt + " — " + orthAtt);
            }
            // record an empty token at punctuation position for the rails
            if (PUN.isPun(flags)) {
                if (flags == PUNclause.code) {
                }
                else if (flags == PUNsent.code) {
                }
                else if (flags == PUNpara.code || flags == PUNsection.code) {
                    // let it
                }
                else {
                    // termAtt.setEmpty().append("");
                }
                return true;
            }
            // unify numbers
            if (flags == DIGIT.code) {
                termAtt.setEmpty().append("#");
                return true;
            }
            if (!lemAtt.isEmpty()) termAtt.setEmpty().append(lemAtt);
            else if (!orthAtt.isEmpty()) termAtt.setEmpty().append(orthAtt);
            // String suff = suffix[flags];
            return true;
        }

        @Override
        public void reset() throws IOException
        {
            super.reset();
        }

        @Override
        public void end() throws IOException
        {
            super.end();
        }

    }
    
    /**
     * Send index loading.
     * 
     * @param args Command line arguments.
     * @throws Exception XML or Lucene errors.
     */
    public static void main(String[] args) throws Exception
    {
        int exitCode = new CommandLine(new Analyze4vec()).execute(args);
        System.exit(exitCode);
    }
}
