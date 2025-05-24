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
public class Expressions  extends Cli implements Callable<Integer>
{
    HashMap<Chain, IntMutable> bigrams = new HashMap<>(16384);
    Chain form = new Chain();
    final static TagFilter nonword = new TagFilter().setGroup(0).clear(TOKEN).setGroup(NUM);
    final static TagFilter name = new TagFilter().setGroup(NAME);
    final static CharArraySet STOP = new CharArraySet(200, false);
    static {
        String[] words = new String[]{
            "<",
            ">",
            "A",
            "A.",
            "A'",
            "a",
            "a.",
            "a'",
            "ans",
            "autre",
            "B",
            "B.",
            "B'",
            "b",
            "b.",
            "b'",
            "beau",
            "beaux",
            "bel",
            "belle",
            "belles",
            "bon",
            "bonne",
            "bonnes",
            "bons",
            "C",
            "C.",
            "C'",
            "c",
            "c.",
            "c'",
            "certain",
            "cinq",
            "chapitre",
            "chose",
            "choses",
            "D",
            "D.",
            "D'",
            "d",
            "d.",
            "d'",
            "deux",
            "dix",
            "dix-huit",
            "dix-neuf",
            "dix-sept",
            "douze",
            "E",
            "E.",
            "E'",
            "e",
            "e.",
            "e'",
            "F",
            "F.",
            "F'",
            "f",
            "f.",
            "f'",
            "fig.",
            "grand",
            "grande",
            "grands",
            "grandes",
            "G",
            "G.",
            "G'",
            "g",
            "g.",
            "g'",
            "H",
            "H.",
            "H'",
            "h",
            "h.",
            "h'",
            "huit",
            "I",
            "I.",
            "I'",
            "i",
            "i.",
            "i'",
            "in",
            "J",
            "J.",
            "J'",
            "j",
            "j.",
            "j'",
            "K",
            "K.",
            "K'",
            "k",
            "k.",
            "k'",
            "L",
            "L.",
            "L'",
            "l",
            "l.",
            "l'",
            "M",
            "M.",
            "M'",
            "m",
            "m.",
            "m'",
            "même",
            "mêmes",
            "madame",
            "Madame",
            "melle",
            "Melle",
            "monsieur",
            "Monsieur",
            "N",
            "N.",
            "N'",
            "n",
            "n.",
            "n'",
            "neuf",
            "nombreux",
            "O",
            "O.",
            "o'",
            "o",
            "o.",
            "o'",
            "of",
            "onze",
            "P",
            "P.",
            "p'",
            "p",
            "p.",
            "p'",
            "page",
            "partie",
            "petit",
            "petite",
            "petites",
            "petits",
            "Q",
            "Q.",
            "Q'",
            "q",
            "q.",
            "q'",
            "quatorze",
            "quatre",
            "quelconque",
            "quinze",
            "R",
            "R.",
            "R'",
            "r",
            "r.",
            "r'",
            "S",
            "S.",
            "S'",
            "s",
            "s.",
            "s'",
            "section",
            "sections",
            "seize",
            "sept",
            "seul",
            "seule",
            "seules",
            "seuls",
            "six",
            "T",
            "T.",
            "T'",
            "t",
            "t.",
            "t'",
            "tel",
            "telle",
            "telles",
            "tels",
            "the",
            "treize",
            "trois",
            "U",
            "U.",
            "U'",
            "u",
            "u.",
            "u'",
            "V",
            "V.",
            "V'",
            "v",
            "v.",
            "v'",
            "vingt",
            "vol.",
            "W",
            "W.",
            "W'",
            "w",
            "w.",
            "w'",
            "X",
            "X.",
            "X'",
            "x",
            "x.",
            "x'",
            "Y",
            "Y.",
            "Y'",
            "y",
            "y.",
            "y'",
            "Z",
            "Z.",
            "Z'",
            "z",
            "z.",
            "z'",
            "α",
            "α.",
            "α'",
        };
        for (final String word: words) {
            STOP.add(word);
        }
    }
    
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
        for (Entry<Chain, IntMutable> entry: bigrams.entrySet()) {
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
            if (
                PUN.isPun(flags)
             || group == VERB.code
             || group == DET.code
             || group == CONN.code
             || group == PRO.code
             || group == ADV.code
             || STOP.contains(orthAtt.buffer(), 0, orthAtt.length())
             || Char.isDigit(termAtt.charAt(0))
             || termAtt.charAt(0) == '-'
            ) {
                form.setLength(0);
                continue;
            }
            if (form.isEmpty()) {
                // if (!lemAtt.isEmpty()) form.append(lemAtt);
                if (!orthAtt.isEmpty()) form.append(orthAtt);
                else form.append(termAtt);
                words = 1;
                continue;
            }
            form.append(" ");
            if (!orthAtt.isEmpty()) form.append(orthAtt);
            else form.append(lemAtt);
            words++;
            if (words < 3) continue;
            IntMutable count = bigrams.get(form);
            if (count == null) {
                count = new IntMutable(0);
                bigrams.put((Chain)form.clone(), count);
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
        int exitCode = new CommandLine(new Expressions()).execute(args);
        System.exit(exitCode);
    }

}
