package com.github.oeuvres.alix.lucene.search;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.stream.IntStream;


import org.apache.lucene.util.BitSet;


public class FieldRailTest extends FieldRail
{

    public FieldRailTest(FieldText fieldText) throws IOException {
        super(fieldText);
        // TODO Auto-generated constructor stub
    }

    /**
     * Parallel freqs calculation, it works but is more expensive than serial,
     * because of concurrency cost.
     * 
     * @param filter
     * @return
     * @throws IOException Lucene errors.
     */
    protected AtomicIntegerArray freqsParallel(final BitSet filter) throws IOException
    {
        // may take big place in mem
        int[] rail = new int[(int) (channel.size() / 4)];
        channelMap.asIntBuffer().get(rail);
        AtomicIntegerArray freqs = new AtomicIntegerArray(dic.size());
        boolean hasFilter = (filter != null);
        int maxDoc = this.maxDoc;
        int[] posInt = this.docId4offset;
        int[] limInt = this.docId4len;

        IntStream loop = IntStream.range(0, maxDoc).filter(docId -> {
            if (limInt[docId] == 0)
                return false;
            if (hasFilter && !filter.get(docId))
                return false;
            return true;
        }).parallel().map(docId -> {
            // to use a channelMap in parallel, we need a new IntBuffer for each doc, too
            // expensive
            for (int i = posInt[docId], max = posInt[docId] + limInt[docId]; i < max; i++) {
                int formId = rail[i];
                freqs.getAndIncrement(formId);
            }
            return docId;
        });
        loop.count(); // go
        return freqs;
    }
}
