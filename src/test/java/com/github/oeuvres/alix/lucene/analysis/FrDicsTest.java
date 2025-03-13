package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import org.apache.lucene.util.BytesRef;
// import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
// import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.Test;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.util.Chain;

public class FrDicsTest {

    @Test
    public void decompose()
    {
        HashMap<CharsAttImpl, Integer> locs = new HashMap<CharsAttImpl, Integer>();
        Chain chain = new Chain();
        for (String word: new String[] {"d'abord", "d’antan", "chemin de fer", "chemin de fer d’intérêt local"}) {
            chain.copy(word);
            FrDics.decompose(chain, locs);
        }
        System.out.println(locs);
    }
    
    public void load() throws IOException
    {
        System.out.println(FrDics.TREELOC.get("d’"));
        File dic = new File("D:/code/ddr_lab/install/ddr-dic.csv");
        FrDics.load(dic);
        CharsAttImpl chars = new CharsAttImpl();
        for (String word: new String[] {"français", "stato-national", "de nouveau"}) {
            chars.setEmpty().append(word);
            FrDics.norm(chars);
            System.out.println(chars + " " + FrDics.word(chars));
        }

    }

    
    public void stopwords()
    {
        for (String word: new String[] {"de", "le", "la", "voiture"}) {
            BytesRef bytes = new BytesRef(word);
            System.out.println(word + ":" + FrDics.isStop(bytes));
        }
    }

}
