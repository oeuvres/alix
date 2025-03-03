package com.github.oeuvres.alix.lucene.search;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.fr.TagFilter;
import com.github.oeuvres.alix.lucene.Alix;


public class FieldRailTest
{
    public static void matrix() throws IOException
    {
        final int left = 5;
        final int right = 5;
        final int maxForm = 20000;
        long startTime = System.nanoTime();
        final Alix alix = Alix.instance("test", Paths.get("../piaget_labo/lucene/piaget"));
        final String fieldName = "text_cloud";
        FieldRail frail = alix.fieldRail(fieldName);
        BitSet docFilter = new FixedBitSet(frail.maxDoc());
        // do it one time to start alix
        docFilter.set(5);
        /*
        System.out.println(frail.maxForm());
        for (int formId = 0; formId < frail.maxForm(); formId++) {
            writer.append(frail.form(formId) + " " + frail.fieldText().occs(formId) + "\n");
        }
        writer.flush();
        */
        int[][] mat = frail.coocmat(left, right, maxForm, docFilter);
        final int note = mat[0][0];
        System.out.println(note + "  " + (((double)( System.nanoTime() - startTime)) / 1000000) + "ms");
        startTime = System.nanoTime();
        /**
        Path filePath = Paths.get("piaget_cooc,5,5.csv");
        BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8);
        final String sep = ",";
        mat = frail.coocmat(left, right, 20000, null);
        System.out.println("matrix " + (((double)( System.nanoTime() - startTime)) / 1000000) + "ms");
        startTime = System.nanoTime();
        for (int formId = 0; formId < maxForm; formId++) {
            writer.append(sep + frail.form(formId).replaceAll(sep, "\",\""));
        }
        writer.append("\n");
        for (int pivotId = 0; pivotId < maxForm; pivotId++) {
            writer.append(frail.form(pivotId));
            for (int coocId = 0; coocId < maxForm; coocId++) {
                writer.append("," + mat[pivotId][coocId]);
            }
            writer.append("\n");
        }
        writer.flush();
        **/
        System.out.println("write " + (((double)( System.nanoTime() - startTime)) / 1000000) + "ms");
    }
    
    public static void export() throws IOException
    {
        Path path = Paths.get("../piaget_labo/lucene/piaget");
        // Path path = Paths.get("../ddr_lab/lucene/rougemont");
        final Alix alix = Alix.instance("test", path);
        final String fieldName = "text_cloud";
        FieldRail frail = alix.fieldRail(fieldName);
        BitSet docFilter = null;
        // BitSet docFilter = new FixedBitSet(frail.maxDoc());
        // docFilter.set(1100);
        TagFilter formFilter = new TagFilter();
        // formFilter.set(Tag.NOSTOP.flag);
        String fileName = "../word2vec/";
        fileName += path.getFileName().toString();
        fileName += "_" + fieldName;
        if (formFilter.get(Tag.NOSTOP.flag)) fileName += "_nostop";
        fileName += ".txt";
        frail.export(
            fileName,
            docFilter,
            formFilter
        );
        System.out.println(fileName + " exported");
    }
    
    public static void main(String[] args) throws IOException
    {
        export();
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
