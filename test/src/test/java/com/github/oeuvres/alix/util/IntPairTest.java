package com.github.oeuvres.alix.util;


import java.util.HashSet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class IntPairTest {

    @Test
    public void mutable()
    {
        final HashSet<IntPair> pairs = new HashSet<>();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                pairs.add(new IntPair(x, y));
            }
        }
        final IntPairMutable key = new IntPairMutable();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                key.set(x, y);
                final boolean condition = pairs.contains(key);
                assertTrue(condition, key + ":" + condition);
                System.out.println(key + ":" + condition);
            }
        }
        key.set(-1, 5);
        final boolean condition = pairs.contains(key);
        assertFalse(condition, key + ":" + condition);
    }

}
