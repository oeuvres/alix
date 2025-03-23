package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class MLTest {

    @Test
    public void testDetag() {
        Set<String> include = new HashSet<>();
        include.add("mark");
        String[] xml = {
            "A simple <tag>tag</tag> to strip.",
            " att=\"value\">Broken tag</tag> at start.",
            "An include <mark>tag <i>with in another</i></mark>.",
            "Normalize <mark \n  id=\"s231\">atts</mark> in a preserved tag.",
            "Broken <mark>tag at end</mark",
        };
        String[] expected = {
            "A simple tag to strip.",
            "Broken tag at start.",
            "An include <mark>tag with in another</mark>.",
            "Normalize <mark id=\"s231\">atts</mark> in a preserved tag.",
            "Broken <mark>tag at end",
        };
        for (int i = 0; i < xml.length; i++) {
            String result = ML.detag(xml[i], include);
            // System.out.println(result);
            assertEquals( expected[i], result);
        }
    }
}
