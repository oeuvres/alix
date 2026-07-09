package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.util.Objects;

import com.github.oeuvres.alix.lucene.output.HistoNum;
import com.github.oeuvres.alix.lucene.output.HistoNum.Col;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker.SnippetsConsumer;

public class HistoSnippets implements SnippetsConsumer
{
    private final HistoNum histo;
    
    /**
     * Accumulates snippets counts into {@link NumHisto#SNIPPETS} of the given histogram.
     */
    public HistoSnippets(final HistoNum histo)
    {
        this.histo = Objects.requireNonNull(histo);
        histo.valueSnippets = new int[histo.length()];
        histo.valueDocs = new int[histo.length()];
        histo.cols().add(Col.SNIPPETS);
        histo.cols().add(Col.DOCS);
    }
    
	@Override
	public void docSnippets(int docId, DocSnippets snippets) throws IOException {
        final int histoIndex = histo.index(docId, -1);
        if (histoIndex < 0) return;
        final int snippetsCount = snippets.count();
        if (snippetsCount <= 0) return;
        histo.valueSnippets[histoIndex] += snippetsCount;
        histo.valueDocs[histoIndex]++;
	}
   
}