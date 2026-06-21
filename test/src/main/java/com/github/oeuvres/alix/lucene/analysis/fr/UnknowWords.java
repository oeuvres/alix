package com.github.oeuvres.alix.lucene.analysis.fr;

import java.nio.file.Path;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.CharsFreq;
import com.github.oeuvres.alix.util.LemmaLexicon;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.Report.ReportConsole;

public class UnknowWords
{

    public static void main(String[] args) throws Exception
    {
        CharsFreq freqList = new CharsFreq(2000);
        Report report = new ReportConsole();
        Path cfgPath = Path.of("../web/conf/alix-piaget.xml");
        final LuceneIndex index = LuceneIndex.open(cfgPath);
        LemmaLexicon lemmas = FrenchLexicons.buildLemmaLexicon();
        FlucText contentFluc = index.flucText(index.content());
        TermStats contentStats = contentFluc.termStats();
        TermLexicon contentLexicon = contentFluc.termLexicon();
        for (int termId = 0; termId < contentStats.vocabSize(); termId++) {
            String word = contentLexicon.form(termId);
            if (word.length() <= 0) continue;
            if (Char.isUpperCase(word.charAt(0))) continue;
            if (lemmas.ord(word) >= 0) continue;
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
    }
}
