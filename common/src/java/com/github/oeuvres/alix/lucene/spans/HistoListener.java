package com.github.oeuvres.alix.lucene.spans;

import java.util.Objects;

import com.github.oeuvres.alix.lucene.output.HistoNum;
import com.github.oeuvres.alix.lucene.output.HistoNum.Col;

public class HistoListener implements SpanListener
{
    private final HistoNum histo;
    private int histoIndex = -1;
    
    /**
     * Accumulates span counts into {@link NumHisto#SPANS} of the given histogram.
     */
    public HistoListener(final HistoNum histo)
    {
        this.histo = Objects.requireNonNull(histo);
        histo.valueSpans = new int[histo.length()];
        histo.cols().add(Col.SPANS);
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
        histo.valueSpans[histoIndex] += spanCount;
    }
    
    
}