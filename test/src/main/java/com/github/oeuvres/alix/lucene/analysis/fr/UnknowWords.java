package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.LinkedList;
import java.util.List;

import org.apache.lucene.analysis.hunspell.DictEntries;
import org.apache.lucene.analysis.hunspell.DictEntry;
import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.CharsFreq;
import com.github.oeuvres.alix.util.IOUtil;
import com.github.oeuvres.alix.util.LemmaLexicon;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.Report.ReportConsole;
import com.github.oeuvres.alix.util.TopArray;

public class UnknowWords
{
    public static Dictionary frDic()
        throws IOException, ParseException
    {
        
        try (
            InputStream aff = IOUtil.openResource(UnknowWords.class, "/com/github/oeuvres/alix/fr/fr-alix.aff");
            InputStream dic = IOUtil.openResource(UnknowWords.class, "/com/github/oeuvres/alix/fr/fr-alix.dic");
            Directory tempDirectory = new ByteBuffersDirectory()
        ) {
            return new Dictionary(
                tempDirectory,
                "hunspell",
                aff,
                dic
            );
        }
    }

    public static void main(String[] args) throws Exception
    {
        // build an hunspell dictionary
        
        
        CharsFreq freqList = new CharsFreq(2000);
        Report report = new ReportConsole();
        Path cfgPath = Path.of("../web/conf/alix-piaget.xml");
        final LuceneIndex index = LuceneIndex.open(cfgPath);
        // LemmaLexicon lemmas = FrenchLexicons.buildLemmaLexicon();
        Dictionary hundic = frDic();
        FlucText contentFluc = index.flucText(index.content());
        TermStats contentStats = contentFluc.termStats();
        TermLexicon contentLexicon = contentFluc.termLexicon();
        
        final int topk = 2000;
        TopArray top = new TopArray(topk);
        for (int termId = 0; termId < contentStats.vocabSize(); termId++) {
            top.push(termId, contentStats.termFreq(termId));
        }
        int rank = 1;
        for (TopArray.TopEntry termCount: top) {
            String word = contentLexicon.form(termCount.id());
            System.out.print(rank + ". " + contentLexicon.form(termCount.id()));
            DictEntries entries = hundic.lookupEntries(word);
            List<String> pos = new LinkedList<String>();
            if (entries != null) {
                pos.clear();
                for (DictEntry entry : entries) {
                    pos.addAll(entry.getMorphologicalValues("po:"));
                }
                System.out.print(" " +pos);
            }
            System.out.println();
            rank++;
        }
        
        /*
        for (int termId = 0; termId < contentStats.vocabSize(); termId++) {
            String word = contentLexicon.form(termId);
            if (word.length() <= 0) continue;
            if (Char.isUpperCase(word.charAt(0))) continue;
            // if (lemmas.ord(word) >= 0) continue;
            if (hundic.lookupEntries(word) != null) continue;
            if (word.indexOf('\'') >= 0 || word.indexOf('’') >= 0 || word.indexOf(' ') >= 0) continue;
            // no expression?
            final int count = (int) contentStats.termFreq(termId);
            freqList.setCount(word, count);
        }
        
        int rank = 1;
        for (CharsFreq.Entry entry: freqList) {
            System.out.println(rank + ". " +entry + " (" + entry.count() + ")");
            if (rank++ > 2000) break;
        }
        */
    }
}
