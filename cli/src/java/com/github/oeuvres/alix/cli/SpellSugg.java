package com.github.oeuvres.alix.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.Hunspell;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.NativeFSLockFactory;

import com.github.oeuvres.alix.fr.French;

public class SpellSugg
{
    static void sugg() throws IOException, ParseException
    {
        InputStream aff = French.class.getResourceAsStream("fr.aff");
        
        InputStream dic = French.class.getResourceAsStream("fr.dic");
        Dictionary dictionary = new Dictionary(
            FSDirectory.open(Path.of("tmp")),
            "hun",
            aff,
            dic
        );
        Hunspell hunspell = new Hunspell(dictionary);
        Set<String> words = Set.of("Struétures");
        for (String word: words) {
            List<String> suggs = hunspell.suggest(word);
            System.out.print(word + ": ");
            boolean first = true;
            for (String sugg: suggs) {
                if (first) first = false;
                else System.out.print(", ");
                System.out.print(sugg);
            }
            System.out.println(".");
        }
    }
    
    static public void main(String[] args) throws IOException, ParseException {
        sugg();
    }
}
