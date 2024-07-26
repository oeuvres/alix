package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.Assert.*;

import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttributeImpl;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
// import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
// import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.Test;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;

public class AttDequeTest {

    public void twoWay()
    {
        final CharTermAttribute term = new CharsAttImpl();
        final OffsetAttribute offsets = new OffsetAttributeImpl();
        final AttDeque deque = new AttDeque();
        final String[] src = new String[] {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K"};
        final int srcLen = src.length;
        for (int i = 0; i < srcLen; i++) {
            deque.addLast(src[i].toCharArray(), 0, 1, i, i+1);
            System.out.println(deque);
        }
        while (!deque.isEmpty()) {
            deque.removeFirst(term, offsets);
            System.out.println(term + " " + offsets.startOffset() + "," + offsets.endOffset());
        }
        for (int i = srcLen - 1; i >= 0; i--) {
            deque.addFirst(src[i].toCharArray(), 0, 1, i, i+1);
            System.out.println(deque);
        }
        while (!deque.isEmpty()) {
            deque.removeFirst(term, offsets);
            System.out.println(term + " " + offsets.startOffset() + "," + offsets.endOffset());
        }
    }

}
