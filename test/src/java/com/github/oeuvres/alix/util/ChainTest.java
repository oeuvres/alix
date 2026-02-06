package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class ChainTest {

    @Test
    public void glob()
    {
        Chain glob = new Chain();
        glob.copy("maz*");
        assertTrue(glob.glob("maz"));
        assertTrue(glob.glob("maze"));
        assertTrue(glob.glob("mazurka"));
        assertFalse(glob.glob("amaze"));
        glob.copy("maz?");
        assertFalse(glob.glob("maz"));
        assertTrue(glob.glob("maze"));
        assertFalse(glob.glob("mazurka"));
        assertFalse(glob.glob("amaze"));
        glob.copy("? ?");
        assertFalse(glob.glob("D f"));
    }
    
    @Test
    public void normalizeString()
    {
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
            String result = Chain.normalizeSpace(phrases[i]);
            assertEquals( expected[i], result);
        }
    }

    @Test
    public void splitChar()
    {
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
    public void splitString()
    {
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
