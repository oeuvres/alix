package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import org.apache.lucene.util.BytesRef;
// import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
// import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.Test;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.util.Chain;

public class FrDicsTest {


    public void lookup()
    {
        CharsAttImpl chars = new CharsAttImpl();
        for (String word: new String[] {"d'abord", "parce qu’", "xx e"}) {
            chars.setEmpty().append(word);
            FrDics.norm(chars);
            System.out.println(chars + " " + FrDics.word(chars));
        }
    }

    public void decompose()
    {
        HashMap<CharsAtt, Integer> locs = new HashMap<CharsAtt, Integer>();
        Chain chain = new Chain();
        for (String word: new String[] {"d'abord", "d’antan", "chemin de fer", "chemin de fer d’intérêt local"}) {
            chain.copy(word);
            FrDics.decompose(chain, locs);
        }
        System.out.println(locs);
    }
    
    @Test
    public void load() throws IOException
    {
        System.out.println("d’: " + FrDics.TREELOC.get("d’"));
        File dic = new File("D:/code/piaget_labo/install/piaget-dic.csv");
        FrDics.load(dic.getCanonicalPath(), dic);
        CharsAttImpl chars = new CharsAttImpl();
        for (String word: new String[] {"français", "Mur", "de nouveau"}) {
            chars.setEmpty().append(word);
            FrDics.norm(chars);
            System.out.println(chars + " —word— " + FrDics.word(chars)+ " —name— " + FrDics.name(chars));
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
