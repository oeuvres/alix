package com.github.oeuvres.alix.lucene.search;

import java.io.IOException;
import java.nio.file.Path;

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
        final TagFilter include = new TagFilter().set(Tag.SUB);
        final TagFilter exclude = new TagFilter().setGroup(Tag.PUN).set(Tag.CONJcoord);
        EdgeMap dic = frail.expressions(null, include, exclude);
        final int max = 200;
        int no = 0;
        for (Edge edge: dic) {
            no++;
            System.out.println(no + ". " + edge);
            if (no >= max) break;
        }
    }

}
