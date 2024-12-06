package com.github.oeuvres.alix.lucene.search;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;


import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.fr.TagFilter;
import com.github.oeuvres.alix.lucene.Alix;
import com.github.oeuvres.alix.util.Edge;
import com.github.oeuvres.alix.util.EdgeMap;

public class LocutionTest
{
    public static void main(String[] args) throws IOException
    {
        
        final Alix alix = Alix.instance("test", Path.of("D:\\code\\piaget_labo\\lucene\\piaget"));
        final String fieldName = "text_orth";
        // FieldText ftext = alix.fieldText(fieldName);
        FieldRail frail = alix.fieldRail(fieldName);
        BitSet docFilter = new FixedBitSet(frail.maxDoc());
        docFilter.set(5);
        EdgeMap dic = frail.expressions(
            null, 
            new TagFilter().set(Tag.SUB),
            // clear STOP & NOSTOP in MISC group
            new TagFilter().setAll().clearGroup(Tag.PUN).clearGroup(Tag.MISC).clear(Tag.CONJcoord).clearGroup(Tag.VERB), // .
            new TagFilter().set(Tag.SUB).set(Tag.ADJ)
        );
        final int max = 200;
        int no = 0;
        System.out.println("\n\n    ————");
        for (Edge edge: dic) {
            no++;
            System.out.println(edge);
            if (no >= max) break;
        }
    }

}
