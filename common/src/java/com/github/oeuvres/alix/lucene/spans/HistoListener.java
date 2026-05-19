package com.github.oeuvres.alix.lucene.spans;

import java.util.Objects;

import com.github.oeuvres.alix.lucene.output.HistoNum;
import com.github.oeuvres.alix.lucene.output.HistoNum.Col;

public class HistoListener implements SpanListener
{
    private final HistoNum histo;
    private int histoIndex = -1;
    
    /**
     * Accumulates span counts into {@link NumHisto#SNIPPETS} of the given histogram.
     */
    public HistoListener(final HistoNum histo)
    {
        this.histo = Objects.requireNonNull(histo);
        histo.valueSnippets = new int[histo.length()];
        histo.valueDocs = new int[histo.length()];
        histo.cols().add(Col.SNIPPETS);
        histo.cols().add(Col.DOCS);
    }

    @Override
    public void startDoc(final int docId)
    {
        histoIndex = histo.index(docId, -1);
    }

    @Override
    public boolean span(final SpanMatch m)
    {
        return false;
    }

    @Override
    public void endDoc(final int spanCount)
    {
        if (histoIndex < 0) return;
        histo.valueSnippets[histoIndex] += spanCount;
        if (spanCount > 0) histo.valueDocs[histoIndex]++;
    }
    
    
}