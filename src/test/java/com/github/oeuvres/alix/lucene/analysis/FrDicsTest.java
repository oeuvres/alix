package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.Assert.*;

import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttributeImpl;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.BytesRef;
// import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
// import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.Test;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;

public class FrDicsTest {

    @Test
    public void stopwords()
    {
        for (String word: new String[] {"de", "le", "la", "voiture"}) {
            BytesRef bytes = new BytesRef(word);
            System.out.println(word + ":" + FrDics.isStop(bytes));
        }
    }

}
