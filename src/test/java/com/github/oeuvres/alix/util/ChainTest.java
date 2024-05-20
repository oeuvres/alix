package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class ChainTest {

    @Test
    public void testNormalizeString() {
        String[] phrases = {
            "",
            " \t \n",
            "  \t",
            "1",
            "  1  \t\n  ",
            "1 2  ",
            " 1\n\r2"
        };
        String[] expected = {
            "",
            "",
            "",
            "1",
            "1",
            "1 2",
            "1 2",
        };
        for (int i = 0; i < phrases.length; i++) {
            String result = Chain.normalize(phrases[i], " \n\t\r", ' ');
            assertEquals( expected[i], result);
        }
    }

    @Test
    public void testSplitChar() {
        String[] paths = {
            "", 
            "/", 
            "1", 
            "/1", 
            "/1/",
            "//1/",
            "1/2",
            "/1/2",
            "/1/2/",
            "//1/2///"
        };
        String[][] expected = {
            {},
            {},
            {"1"},
            {"1"},
            {"1"},
            {"1"},
            {"1", "2"},
            {"1", "2"},
            {"1", "2"},
            {"1", "2"},
        };
        for (int i = 0; i < paths.length; i++) {
            String[] result = new Chain(paths[i]).split('/');
            assertArrayEquals( expected[i], result );
        }
    }

    @Test
    public void testSplitString() {
        String[] phrases = {
            "",
            " \t \n",
            "  \t",
            "1",
            "  1  \t\n  ",
            "1 2",
            " 1\n2"
        };
        String[][] expected = {
            {},
            {},
            {},
            {"1"},
            {"1"},
            {"1", "2"},
            {"1", "2"},
        };
        for (int i = 0; i < phrases.length; i++) {
            String[] result = new Chain(phrases[i]).split(" \n\t");
            assertArrayEquals( expected[i], result );
        }
    }

}
