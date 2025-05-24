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
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import static com.github.oeuvres.alix.fr.TagFr.*;

import com.github.oeuvres.alix.common.TagFilter;
import com.github.oeuvres.alix.fr.TagFr;
import com.github.oeuvres.alix.lucene.analysis.FilterAposHyphenFr;
import com.github.oeuvres.alix.lucene.analysis.FilterFrPos;
import com.github.oeuvres.alix.lucene.analysis.FilterHTML;
import com.github.oeuvres.alix.lucene.analysis.FilterLemmatize;
import com.github.oeuvres.alix.lucene.analysis.FilterLocution;
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

@Command(name = "Expressions", description = "Analyse an XML/TEI corpus to output a freqlist of multiword expressions.")
public class Names  extends Cli implements Callable<Integer>
{
    HashMap<Chain, IntMutable> forms = new HashMap<>(16384);
    Chain form = new Chain();

    
    @Override
    public Integer call() throws Exception
    {
        long time = System.nanoTime();
        Analyzer analyzer = new AnaCli();

        parse(conf);
        paths.sort(null);
        for (final Path path: paths) {
            System.err.println(path);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    new FileInputStream(path.toFile())
                , "UTF-8")
            );
            analyze(analyzer.tokenStream("", reader));
        }
        
        analyzer.close();
        Top<Chain> top = new Top<Chain>(Chain.class, 2000);
        for (Entry<Chain, IntMutable> entry: forms.entrySet()) {
            top.insert(entry.getValue().value(), entry.getKey());
        }
        
        System.err.println(((System.nanoTime() - time) / 1000000) + " ms.");
        int n = 0;
        for(Top.Entry<Chain> entry: top) {
            System.out.println(++n + ".\t" +entry.value() + "\t" + (int)entry.score());
        }
        return 0;
    }

    private void analyze(final TokenStream tokenStream) throws IOException
    {
        

        final CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
        final FlagsAttribute flagsAtt = tokenStream.addAttribute(FlagsAttribute.class);
        final LemAtt lemAtt = tokenStream.addAttribute(LemAtt.class);
        final OrthAtt orthAtt = tokenStream.addAttribute(OrthAtt.class);
        
        final CharsAttImpl testAtt = new CharsAttImpl();
        tokenStream.reset();
        int words = 0;
        while(tokenStream.incrementToken()) {
            final int flags = flagsAtt.getFlags();
            final int group = (flags & 0xF0);
            if (termAtt.isEmpty()) {
                continue; // empty position
            }
            if (form.isEmpty()) {
                if (group != NAME.code) continue;
                // start with a name
                if (!orthAtt.isEmpty()) form.append(orthAtt);
                else form.append(termAtt);
                words = 1;
                continue;
            }
            // append next name
            if (group == NAME.code) {
                form.append(" ");
                if (!orthAtt.isEmpty()) form.append(orthAtt);
                else form.append(lemAtt);
                continue;
            }
            // append next not name for species
            if (!orthAtt.isEmpty()) form.append(orthAtt);
            else form.append(lemAtt);
            IntMutable count = forms.get(form);
            if (count == null) {
                count = new IntMutable(0);
                forms.put((Chain)form.clone(), count);
            }
            count.inc();
            form.setLength(0);
        }
        tokenStream.close();
    }


    /**
     * Send index loading.
     * 
     * @param args Command line arguments.
     * @throws Exception XML or Lucene errors.
     */
    public static void main(String[] args) throws Exception
    {
        int exitCode = new CommandLine(new Names()).execute(args);
        System.exit(exitCode);
    }

}
