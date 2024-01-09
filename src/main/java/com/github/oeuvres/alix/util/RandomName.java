package com.github.oeuvres.alix.util;

import java.util.concurrent.ThreadLocalRandom;

public class RandomName {
    /** Allowed chars for name */
    private static final String chars = "abcdefghijklmnopqrstuvwxyz"
                        + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                        + "1234567890_";
    private static final int len = chars.length();
    
    
    /**
     * Get a random name of a size
     */
    public static String name(int size)
    {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            final int randint = ThreadLocalRandom.current().nextInt(0, len);
            sb.append(chars.charAt(randint));
        }
        return sb.toString();
    }
}
