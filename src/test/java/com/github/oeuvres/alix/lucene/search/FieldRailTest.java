package com.github.oeuvres.alix.lucene.search;

import java.io.BufferedWriter;
import java.io.File;
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
import com.github.oeuvres.alix.lucene.analysis.FrDics;
import com.github.oeuvres.alix.util.CoocMat;


public class FieldRailTest
{
    public static void matrix() throws IOException
    {
        final int left = 5;
        final int right = 5;
        final int freqMin = 10;
        long startTime = System.nanoTime();
        final Alix alix = Alix.instance("test", Paths.get("../piaget_labo/lucene/piaget"));
        // load children names
        File dicFile = new File("../piaget_labo/install/piaget-dic.csv");
        FrDics.load(dicFile.getCanonicalPath(), dicFile);
        final String fieldName = "text_cloud";
        FieldRail frail = alix.fieldRail(fieldName);
        /*
        BitSet docFilter = new SparseFixedBitSet(frail.maxDoc());
        final int docId = alix.getDocId("piaget1947a05");
        docFilter.set(docId);
        */
        TagFilter tagFilter = new TagFilter();
        /*
        tagFilter.set(Tag.NOSTOP).set(Tag.SUB).set(Tag.ADJ).setGroup(Tag.NAME)
            .set(Tag.VERB).set(Tag.VERBppas).set(Tag.VERBger); // no more unknown .set(Tag.NULL);
        */
        tagFilter.set(Tag.NAME);

        CoocMat coocMat = frail.coocMat(left, right, tagFilter, freqMin, null);
        Path filePath = Paths.get("work/piaget_cooc,5,5.tsv");
        BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8);
        final String sep = "\t";
        int[] headers = coocMat.headers();
        final int headersLen = headers.length;
        for (int col = 0; col < headersLen; col++) {
            final int formId = headers[col];
            writer.append(sep + frail.form(formId).replaceAll(sep, " "));
        }
        writer.append("\n");
        for (int row = 0; row < headersLen; row++) {
            final int formId = headers[row];
            writer.append(frail.form(formId).replaceAll(sep, " "));
            for (int col = 0; col < headersLen; col++) {
                writer.append(sep + coocMat.getByRowCol(row, col));
            }
            writer.append("\n");
        }
        writer.flush();
        System.out.println((((double)( System.nanoTime() - startTime)) / 1000000) + "ms");
    }
    
    public static void export() throws IOException
    {
        Path path = Paths.get("../piaget_labo/lucene/piaget");
        // Path path = Paths.get("../ddr_lab/lucene/rougemont");
        final Alix alix = Alix.instance("test", path);
        File dicFile = new File("../piaget_labo/install/piaget-dic.csv");
        FrDics.load(dicFile.getCanonicalPath(), dicFile);
        final String fieldName = "text_cloud";
        FieldRail frail = alix.fieldRail(fieldName);
        BitSet docFilter = null;
        // BitSet docFilter = new SparseFixedBitSet(frail.maxDoc());
        // docFilter.set(alix.getDocId("piaget1922a05"));
        // piaget1922a05
        TagFilter tagFilter = new TagFilter();
        tagFilter.set(Tag.NOSTOP).set(Tag.SUB).set(Tag.ADJ).setGroup(Tag.NAME).clear(Tag.NAMEfict)
            .set(Tag.VERB).set(Tag.VERBppas).set(Tag.VERBger); // no more unknown .set(Tag.NULL);
        String fileName = "../word2vec/";
        fileName += path.getFileName().toString();
        // fileName += "_" + fieldName;
        // if (formFilter.get(Tag.NOSTOP.flag)) fileName += "_nostop";
        fileName += ".txt";
        frail.export(
            fileName,
            docFilter,
            tagFilter
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
