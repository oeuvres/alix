/*
 * Copyright (C) 2014-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.util;

import java.util.zip.Checksum;

/**
 * Murmur3A (murmurhash3_x86_32)
 * Source: https://github.com/greenrobot/essentials/blob/master/java-essentials/src/main/java/org/greenrobot/essentials/hash/Murmur3A.java
 * 
 */
public class Murmur3A implements Checksum {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private final int seed;

    private int h1;
    private int length;

    private int partialK1;
    private int partialK1Pos;

    /**
     * Constructor with no seed.
     */
    public Murmur3A() {
        seed = 0;
    }

    /**
     * Constructor with a seed.
     * @param seed start value for the checksum.
     */
    public Murmur3A(int seed) {
        this.seed = seed;
        h1 = seed;
    }

    /**
     * 
     * @param k1
     */
    private void applyK1(int k1) {
        k1 *= C1;
        k1 = (k1 << 15) | (k1 >>> 17);  // ROTL32(k1,15);
        k1 *= C2;
    
        h1 ^= k1;
        h1 = (h1 << 13) | (h1 >>> 19);  // ROTL32(h1,13);
        h1 = h1 * 5 + 0xe6546b64;
    }

    /**
     * Get hash code as an int.
     * 
     * @return 32 bits mumurHash.
     */
    public int getHashCode() {
        int finished = h1;
        if (partialK1Pos > 0) {
            int k1 = partialK1 * C1;
            k1 = (k1 << 15) | (k1 >>> 17);  // ROTL32(k1,15);
            k1 *= C2;
            finished ^= k1;
        }
        finished ^= length;
    
        // fmix
        finished ^= finished >>> 16;
        finished *= 0x85ebca6b;
        finished ^= finished >>> 13;
        finished *= 0xc2b2ae35;
        finished ^= finished >>> 16;
        return finished;
    }

    @Override
    public long getValue() {
    
        return 0xFFFFFFFFL & getHashCode();
    }

    @Override
    public void reset() {
        h1 = seed;
        length = 0;
        partialK1Pos = 0;
    }

    @Override
    public void update(int b) {
        switch (partialK1Pos) {
            case 0:
                partialK1 = 0xff & b;
                partialK1Pos = 1;
                break;
            case 1:
                partialK1 |= (0xff & b) << 8;
                partialK1Pos = 2;
                break;
            case 2:
                partialK1 |= (0xff & b) << 16;
                partialK1Pos = 3;
                break;
            case 3:
                partialK1 |= (0xff & b) << 24;
                applyK1(partialK1);
                partialK1Pos = 0;
                break;
        }
        length++;
    }

    /**
     * Updates the current checksum with the specified array of bytes.
     * 
     * @param b the byte array to update the checksum with.
     */
    public void update(byte[] b) {
        update(b, 0, b.length);
    }

    @Override
    public void update(byte[] b, int off, int len) {
        while (partialK1Pos != 0 && len > 0) {
            update(b[off]);
            off++;
            len--;
        }

        final int remainder = len & 3;
        final int stop = off + len - remainder;
        for (int index = off; index < stop; index += 4) {
            int k1 = (b[index] & 0xff) | ((b[index + 1] & 0xff) << 8) |
                    ((b[index + 2] & 0xff) << 16) | (b[index + 3] << 24);
            applyK1(k1);
        }
        length += stop - off;

        for (int i = 0; i < remainder; i++) {
            update(b[stop + i]);
        }
    }

    /**
     * Updates the current checksum with a short value.
     * 
     * @param value the short to update the checksum with.
     */
    public void updateShort(short value) {
        switch (partialK1Pos) {
            case 0:
                partialK1 = value & 0xffff;
                partialK1Pos = 2;
                break;
            case 1:
                partialK1 |= (value & 0xffff) << 8;
                partialK1Pos = 3;
                break;
            case 2:
                partialK1 |= (value & 0xffff) << 16;
                applyK1(partialK1);
                partialK1Pos = 0;
                break;
            case 3:
                partialK1 |= (value & 0xff) << 24;
                applyK1(partialK1);
                partialK1 = (value >> 8) & 0xff;
                partialK1Pos = 1;
                break;
        }
        length += 2;
    }

    /**
     * Updates the current checksum with an array of short.
     * 
     * @param values the short array to update the checksum with.
     */
    public void updateShort(short[] values) {
        int len = values.length;
        if (len > 0 && (partialK1Pos == 0 || partialK1Pos == 2)) {
            // Bulk tweak: for some weird reason this is 25-60% faster than the else block
            int off = 0;
            if (partialK1Pos == 2) {
                partialK1 |= (values[0] & 0xffff) << 16;
                applyK1(partialK1);
                partialK1Pos = 0;
                len--;
                off = 1;
            }

            int joinBeforeIdx = off + (len & 0xfffffffe);
            for (int i = off; i < joinBeforeIdx; i += 2) {
                int joined = (0xffff & values[i]) | ((values[i + 1] & 0xffff) << 16);
                applyK1(joined);
            }
            if (joinBeforeIdx < values.length) {
                partialK1 = values[joinBeforeIdx] & 0xffff;
                partialK1Pos = 2;
            }
            length += 2 * values.length;
        } else {
            for (short value : values) {
                updateShort(value);
            }
        }
    }

    /**
     * Update with an int value, do not use {@link Checksum#update(int)} (for a byte).
     * 
     * @param value int value.
     */
    public void updateInt(int value) {
        switch (partialK1Pos) {
            case 0:
                applyK1(value);
                break;
            case 1:
                partialK1 |= (value & 0xffffff) << 8;
                applyK1(partialK1);
                partialK1 = value >>> 24;
                break;
            case 2:
                partialK1 |= (value & 0xffff) << 16;
                applyK1(partialK1);
                partialK1 = value >>> 16;
                break;
            case 3:
                partialK1 |= (value & 0xff) << 24;
                applyK1(partialK1);
                partialK1 = value >>> 8;
                break;
        }
        length += 4;
    }

    /**
     * Updates the current checksum with the specified array of ints.
     * 
     * @param values the int array to update the checksum with.
     */
    public void updateInt(int[] values) {
        updateInt(values, 0, values.length);
    }
    
    /**
     * Updates the current checksum with the specified array of ints.
     * 
     * @param values the int array to update the checksum with.
     * @param off the start offset of the int array.
     * @param len the number of ints to use for the update.
     */
    public void updateInt(int[] values, int off, int len) {
        if (partialK1Pos == 0) {
            // Bulk tweak: for some weird reason this is 25-60% faster than the else block
            for (int index = off, max = off + len; index < max; index++) {
                applyK1(values[index]);
            }
            length += 4 * len;
        } else {
            for (int index = off, max = off + len; index < max; index++) {
                updateInt(values[index]);
            }
        }
    }

    /**
     * Updates the current checksum with a long value.
     * 
     * @param value long value.
     */
    public void updateLong(long value) {
        switch (partialK1Pos) {
            case 0:
                applyK1((int) (value & 0xffffffff));
                applyK1((int) (value >>> 32));
                break;
            case 1:
                partialK1 |= (value & 0xffffff) << 8;
                applyK1(partialK1);
                applyK1((int) ((value >>> 24) & 0xffffffff));
                partialK1 = (int) (value >>> 56);
                break;
            case 2:
                partialK1 |= (value & 0xffff) << 16;
                applyK1(partialK1);
                applyK1((int) ((value >>> 16) & 0xffffffff));
                partialK1 = (int) (value >>> 48);
                break;
            case 3:
                partialK1 |= (value & 0xff) << 24;
                applyK1(partialK1);
                applyK1((int) ((value >>> 8) & 0xffffffff));
                partialK1 = (int) (value >>> 40);
                break;
        }
        length += 8;
    }

    /**
     * Updates the current checksum with the specified array of ints.
     * 
     * @param values the long array to update the checksum with.
     * @param off the start offset of the int array.
     * @param len the number of bytes to use for the update.
     */
    public void updateLong(long[] values, int off, int len) {
        if (partialK1Pos == 0) {
            // Bulk tweak: for some weird reason this is ~25% faster than the else block
            for (int index = off, max = off + len; index < max; index++) {
                final long value = values[index];
                applyK1((int) (value & 0xffffffff));
                applyK1((int) (value >>> 32));
            }
            length += 8 * len;
        } else {
            for (int index = off, max = off + len; index < max; index++) {
                final long value = values[index];
                updateLong(value);
            }
        }
    }

    /**
     * Updates the current checksum with a float (32 bits, like an int).
     * 
     * @param value the float to update the checksum with.
     */
    public void updateFloat(float value) {
        updateInt(Float.floatToIntBits(value));
    }

    /**
     * Updates the current checksum with a double (64 bits, like an long).
     * 
     * @param value the double to update the checksum with.
     */
    public void updateDouble(double value) {
        updateLong(Double.doubleToLongBits(value));
    }

    /**
     * updates a byte with 0 for false and 1 for true.
     * 
     * @param value true or false.
     */
    public void updateBoolean(boolean value) {
        update(value ? 1 : 0);
    }
}