package com.github.oeuvres.alix.lucene.analysis;



import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;


import com.github.oeuvres.alix.util.CharsDic;
import com.github.oeuvres.alix.util.LongIntMap;

public final class LemmaDic
{

    CharsDic forms;
    LongIntMap lemmaMap;
    
    public void lemma(final CharTermAttribute src, final int pos, final CharTermAttribute dst)
    {
        // get formid
        //
    }
}
