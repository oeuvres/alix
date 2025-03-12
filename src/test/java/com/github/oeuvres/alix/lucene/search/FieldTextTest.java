package com.github.oeuvres.alix.lucene.search;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.SparseFixedBitSet;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.fr.TagFilter;
import com.github.oeuvres.alix.lucene.Alix;
import com.github.oeuvres.alix.lucene.search.FormIterator.Order;


public class FieldTextTest
{

    @SuppressWarnings("unused")
    public static void freqList() throws IOException
    {
        // Path path = Paths.get("../piaget_labo/lucene/piaget");
        Path path = Paths.get("../ddr_lab/lucene/rougemont");
        final Alix alix = Alix.instance("test", path);
        final String q = null;
        final int limit = 100;
        final int left = 5;
        final int right = 5;
        final String book = null;

        final Order order = FormEnum.Order.FREQ;
        // Where to search in
        String fname = "text_cloud";
        if (q != null) {
            fname = "text_orth";
        }
        final FieldText ftext = alix.fieldText(fname);
        BitSet docFilter = null;
        // TagFilter tagFilter = new TagFilter().set(Tag.NOSTOP); // .set(Tag.ADJ).set(Tag.UNKNOWN).setGroup(Tag.NAME).set(Tag.NULL).set(Tag.NOSTOP);
        TagFilter tagFilter = new TagFilter().setGroup(Tag.NAME);
        FormEnum formEnum = ftext.formEnum(docFilter, tagFilter, Distrib.BM25);
        formEnum.sort(order, limit);
        System.out.println(formEnum);
    }
    
    public static void main(String[] args) throws IOException
    {
        freqList();
    }

    /**
     * Parallel freqs calculation, it works but is more expensive than serial,
     * because of concurrency cost.
     * 
     * @param filter
     * @return
     * @throws IOException Lucene errors.
     */
    /*
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
    */
}
