package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

public class MLTest {

    @Test
    public void testDetag() {
        Set<String> include = Set.of("mark");
        String[] xml = {
            "A simple <tag>tag</tag> to strip.",
            " att=\"value\">Broken tag</tag> at start.",
            "Broken <mark>tag at end</mark",
            "An include <mark>tag <i>with in another</i></mark>.",
        };
        String[] expected = {
            "A simple tag to strip.",
            "Broken tag at start.",
            "Broken <mark>tag at end",
            "An include <mark>tag with in another</mark>.",
        };
        for (int i = 0; i < xml.length; i++) {
            String result = ML.detag(xml[i], include);
            assertEquals(expected[i], result);
        }
    }
}

