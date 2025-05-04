package com.github.oeuvres.alix.lucene.index;

import static com.github.oeuvres.alix.common.Flags.*;
import static com.github.oeuvres.alix.fr.TagFr.NUM;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.Callable;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import static com.github.oeuvres.alix.common.Flags.*;
import static com.github.oeuvres.alix.fr.TagFr.*;

import com.github.oeuvres.alix.common.TagFilter;
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
import com.github.oeuvres.alix.util.Chain;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.IntMutable;
import com.github.oeuvres.alix.util.Top;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "Unknown", description = "Analyse an XML/TEI corpus to output a freqlist of unknown words.")
public class ListErrors  extends Cli implements Callable<Integer>
{
    HashMap<Chain, IntMutable> errors = new HashMap<>(20000);
    Chain form = new Chain();
    final static TagFilter nonword = new TagFilter().setGroup(0).clear(TOKEN).setGroup(NUM);
    final static TagFilter name = new TagFilter().setGroup(NAME);

    
    @Override
    public Integer call() throws Exception
    {
        long time = System.nanoTime();
        Analyzer analyzer = new AnaUnknown();

        parse(conf);
        paths.sort(null);
        for (final Path path: paths) {
            System.out.println(path);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    new FileInputStream(path.toFile())
                , "UTF-8")
            );
            analyze(analyzer.tokenStream("", reader));
        }
        
        analyzer.close();
        Top<Chain> top = new Top<Chain>(Chain.class, 2000);
        for (Entry<Chain, IntMutable> entry: errors.entrySet()) {
            if (entry.getKey().equals("Ad*")) {
                System.out.println(entry);
            }
            top.insert(entry.getValue().value(), entry.getKey());
        }
        
        System.out.println(((System.nanoTime() - time) / 1000000) + " ms.");

        System.out.println(errors.size() + " errors");
        System.out.println(top);
        return 0;
    }
    
    private void up()
    {
        if (form.isEmpty()) return;
        IntMutable count = errors.get(form);
        if (count == null) {
            count = new IntMutable(0);
            errors.put((Chain)form.clone(), count);
        }
        count.inc();
        form.setLength(0);
    }
    
    private void analyze(final TokenStream tokenStream) throws IOException
    {
        

        final CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
        final FlagsAttribute flagsAtt = tokenStream.addAttribute(FlagsAttribute.class);
        final LemAtt lemAtt = tokenStream.addAttribute(LemAtt.class);
        final OrthAtt orthAtt = tokenStream.addAttribute(OrthAtt.class);
        
        final CharsAttImpl testAtt = new CharsAttImpl();
        tokenStream.reset();
        while(tokenStream.incrementToken()) {
            if (termAtt.isEmpty()) {
                continue;
            }
            final int flags = flagsAtt.getFlags();
            testAtt.wrap(termAtt.buffer(), termAtt.length());
            char lastChar = termAtt.charAt(termAtt.length() - 1);
            if (
                nonword.get(flags)
                || name.get(flags)
                || !lemAtt.isEmpty()
                || FrDics.isStop(testAtt)
                || Char.isDigit(lastChar)
                || lastChar == '\''
                || termAtt.length() < 3
            ) {
                continue;
            }
            form.append(termAtt);
            up();
        }
        tokenStream.close();
    }

    public class AnaUnknown extends Analyzer
    {
        /**
         * Default constructor.
         */
        public AnaUnknown()
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
        int exitCode = new CommandLine(new ListErrors()).execute(args);
        System.exit(exitCode);
    }

}
