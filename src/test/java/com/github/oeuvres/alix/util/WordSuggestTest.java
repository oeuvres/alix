package com.github.oeuvres.alix.util;

import java.util.Arrays;


public class WordSuggestTest {
    static WordSuggest sugg;
    
    static void search(final String q, final int count) {
        String[][] found = sugg.search(q, count);
        System.out.print(q + " — ");
        for (String[] row: found) {
            System.out.print(" " + row[0] + ":" + row[1]);
        }
        System.out.println();
    }

    static public void main(String[] args) {
        String[] words = {
            "Maison",
            "maisonnée",
            "MAÎSTRE",
            "cabane",
            "cœlène",
            "Maïs",
            "maisonnette",
        };
        sugg = new WordSuggest(words);
        System.out.println(sugg.search);
        for (int i = 0; i < sugg.search.length(); i += 10) {
            System.out.print(" 123456789");
        }
        System.out.println("");

        for (String q: new String[] {
            "_m",
            "_c",
            "ne_",
            "t",
            "aïs",
            "_a",
        }) {
            search(q, 3);
        }
    }


}
