package com.github.oeuvres.alix.common;

import java.io.Reader;
import java.io.StringReader;

import com.github.oeuvres.alix.fr.TagFr;
import com.github.oeuvres.alix.lucene.analysis.FrDics;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;

public class TagTest
{

    static void tagfr()
    {
        System.out.println(TagFr.NAME);
        System.out.println(TagFr.name(0xA0));
    }
    
    public static void main(String[] args) {
        tagfr();
        /*
        String csv = """
GRAPH,TAG,LEM,NORM
test,,,OK
""";
        Reader reader = new StringReader(csv);
        FrDics.load("test", reader, true);
        CharsAtt att = new CharsAttImpl("test");
        System.out.println(att);
        FrDics.norm(att);
        System.out.println(att);
        */
    }
}
