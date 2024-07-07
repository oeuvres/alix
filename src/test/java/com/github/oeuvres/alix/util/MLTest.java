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
        include.add("a");
        String[] xml = {
            "A simple <tag>tag</tag> to strip.",
            " att=\"value\">Broken tag</tag> at start.",
            "An include <mark>tag <i>with in another</i></mark>.",
            "Normalize <a \n  href=\"#go\">atts</a> in a preserved tag.",
        };
        String[] expected = {
            "A simple tag to strip.",
            "Broken tag at start.",
            "An include <mark>tag with in another</mark>.",
            "Normalize <a href=\"#go\">atts</a> in a preserved tag.",
        };
        for (int i = 0; i < xml.length; i++) {
            String result = ML.detag(xml[i], include);
            assertEquals( expected[i], result);
        }
    }
}
