package com.github.oeuvres.alix.lucene.index;

import static com.github.oeuvres.alix.common.Flags.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.Callable;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttributeImpl;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import com.github.oeuvres.alix.lucene.analysis.FilterAposHyphenFr;
import com.github.oeuvres.alix.lucene.analysis.FilterFrPos;
import com.github.oeuvres.alix.lucene.analysis.FilterHTML;
import com.github.oeuvres.alix.lucene.analysis.FrDics;
import com.github.oeuvres.alix.lucene.analysis.TokenizerML;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.index.Analyze4vec.Analyzer4vec;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.Chain;
import com.github.oeuvres.alix.util.IntMutable;
import com.github.oeuvres.alix.util.Top;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "Unknown", description = "Analyse an XML/TEI corpus to output a freqlist of unknown words.")
public class ListErrors  extends Cli implements Callable<Integer>
{
    HashMap<Chain, IntMutable> errors = new HashMap<>(20000);
    Chain form = new Chain();
    
    
    @Override
    public Integer call() throws Exception
    {
        long time = System.nanoTime();
        Analyzer analyzer = new AnaUnknown();

        if (conf != null) {
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
        }
        else {
            String text = "Mais cela ne signifie naturellement pas qu’il sache d’emblée composer les dépassements entre eux (Δ<hi>xz</hi> = Δ<hi>xy</hi> + Δ<hi>yz</hi>) et, comme on le verra sous 2), il est au contraire probable qu’aux débuts un plus grand dépassement, et même un dépassement égal mais entre éléments plus grands, leur paraissent d’une autre nature qu’un dépassement entre petits éléments.";
            analyze(analyzer.tokenStream("", new StringReader(text)));
        }

        
        analyzer.close();
        Top<Chain> top = new Top<Chain>(Chain.class, 2000);
        for (Entry<Chain, IntMutable> entry: errors.entrySet()) {
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
        final CharsAttImpl charsAtt = new CharsAttImpl();
        final FlagsAttribute flagsAtt = tokenStream.addAttribute(FlagsAttribute.class);
        tokenStream.reset();
        while(tokenStream.incrementToken()) {
            final int flags = flagsAtt.getFlags();
            if (PUN.isPun(flags)) {
                up();
                continue;
            }
            if (DIGIT.code() == flags) {
                up();
                continue;
            }
            charsAtt.wrap(termAtt.buffer(), termAtt.length());
            if (Char.isUpperCase(charsAtt.charAt(0))) {
                FrDics.norm(charsAtt);
                if (FrDics.name(charsAtt) != null) {
                    up();
                    continue;
                }
                charsAtt.capitalize();
                FrDics.norm(charsAtt);
                if (FrDics.name(charsAtt) != null) {
                    up();
                    continue;
                }
                charsAtt.toLower();
            }
            if (FrDics.word(charsAtt) != null) {
                up();
                continue;
            }
            if (!form.isEmpty()) form.append(' ');
            form.append(termAtt);
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
        /*
        int exitCode = new CommandLine(new ListErrors()).execute(args);
        System.exit(exitCode);
        */
        CharTermAttribute term = new CharTermAttributeImpl();
        term.append("test");
        System.out.println(term.equals("test"));
    }

}
