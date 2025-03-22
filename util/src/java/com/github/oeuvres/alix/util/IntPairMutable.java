package com.github.oeuvres.alix.util;
import java.util.HashSet;

/**
 * An {@link IntPair} mutable, for example as a testing key in a {@link HashSet}
 */
public class IntPairMutable extends IntPair
{

    /**
     * Empty ocnstructor.
     */
    public IntPairMutable() {
        super(0, 0);
    }

    /**
     * Modify pair.
     * @param x first int value.
     * @param y second int value.
     * @return this.
     */
    public IntPairMutable set(final int x, final int y) {
        this.x = x;
        this.y = y;
        this.hash = hashCalc();
        return this;
    }
}
