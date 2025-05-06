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
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import com.github.oeuvres.alix.common.Flags;
import com.github.oeuvres.alix.common.TagFilter;
import com.github.oeuvres.alix.fr.TagFr;
import com.github.oeuvres.alix.lucene.analysis.FilterAposHyphenFr;
import com.github.oeuvres.alix.lucene.analysis.FilterFrPos;
import com.github.oeuvres.alix.lucene.analysis.FilterHTML;
import com.github.oeuvres.alix.lucene.analysis.FilterLemmatize;
import com.github.oeuvres.alix.lucene.analysis.FilterLocution;
import com.github.oeuvres.alix.lucene.analysis.FrDics;
import com.github.oeuvres.alix.lucene.analysis.TokenizerML;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;
import com.github.oeuvres.alix.util.Char;

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
    final static TagFilter nonword = new TagFilter().setGroup(0).clear(TOKEN).setGroup(NUM);
    final static TagFilter sem = new TagFilter().set(VERB).set(SUB).set(ADJ).setGroup(NAME);
    
    /** Convert flags as tag to append to term */

    

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
    
    static void unroll(final TokenStream tokenStream, final Writer writer) throws IOException
    {
        
        final CharsAttImpl test = new CharsAttImpl();
        final CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
        final LemAtt lemAtt = tokenStream.addAttribute(LemAtt.class);
        final OrthAtt orthAtt = tokenStream.addAttribute(OrthAtt.class);
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
            if (flags == PUNpara.code) {
                writer.write('\n');
                writer.write('\n');
                continue;
            }
            // skip pun
            if (
                PUN.isPun(flags)
                || MATH.code == flags
            ) {
                continue;
            }
            // do no suppress term=D’, take orth=de
            char lastChar = (!orthAtt.isEmpty())?orthAtt.charAt(orthAtt.length() - 1):termAtt.charAt(termAtt.length() - 1);
            if (
                   flags == DIGIT.code
                || flags == NUM.code
                || flags == NUMno.code
            ) {
                termAtt.setEmpty().append("#");
            }
            // G4, A'
            else if (Char.isDigit(lastChar) || lastChar == '\'') {
                termAtt.setEmpty().append("{x}");
            }
            // pos suffix do not add precision
            // keep grammatical word works 
            // else if (!sem.get(flags)) continue;
            // skip unknown words to hide OCR suprises
            else if (!TagFr.isName(flags) && lemAtt.isEmpty()) {
                continue;
            }
            else if (!lemAtt.isEmpty()) {
                termAtt.setEmpty().append(lemAtt);
            }
            // verbs inflected ?
            else if (!orthAtt.isEmpty()) {
                termAtt.setEmpty().append(orthAtt);
            }
            // A, B, C…
            if (TagFr.isName(flags) && termAtt.length() < 2) {
                termAtt.setEmpty().append("{x}");
            }
            // last case, output it
            char[] chars = termAtt.buffer();
            final int len = termAtt.length();
            for (int i = 0; i < len; i++) {
                if (chars[i] == ' ') chars[i] = '_';
            }
            
            writer.write(termAtt.buffer(), 0, termAtt.length());
            writer.write(' ');
        }
        tokenStream.close();
    }
    
    public static class Analyzer4vec extends Analyzer
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
            return new TokenStreamComponents(tokenizer, ts);
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
