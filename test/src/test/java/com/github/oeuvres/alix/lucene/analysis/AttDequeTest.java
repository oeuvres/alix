package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;


import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttributeImpl;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;

public class AttDequeTest {

    @Test
    public void twoWay()
    {
        final CharTermAttribute term = new CharsAttImpl();
        final OffsetAttribute offsets = new OffsetAttributeImpl();
        final AttLinkedList deque = new AttLinkedList();
        final String[] src = new String[] {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K"};
        final int srcLen = src.length;
        for (int i = 0; i < srcLen; i++) {
            deque.addLast(src[i].toCharArray(), 0, 1, i, i+1);
            System.out.println("addLast(" + src[i] + ") "+ deque);
        }
        while (!deque.isEmpty()) {
            final int index = deque.removeFirst(term, offsets);
            System.out.println("removeFirst() " + index + ". " + term + " (" + offsets.startOffset() + "," + offsets.endOffset() + ")");
        }
        for (int i = srcLen - 1; i >= 0; i--) {
            deque.addFirst(src[i].toCharArray(), 0, 1, i, i+1);
            System.out.println("addFirst(" + src[i] + ") " + deque);
        }
        while (!deque.isEmpty()) {
            final int index = deque.removeLast(term, offsets);
            System.out.println("removeFirst() " + index + ". " + term + " (" + offsets.startOffset() + "," + offsets.endOffset() + ")");
        }
    }

}
