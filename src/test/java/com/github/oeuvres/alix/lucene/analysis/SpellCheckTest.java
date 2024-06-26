package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.spell.Dictionary;
import org.apache.lucene.search.spell.LuceneDictionary;
import org.apache.lucene.search.spell.SpellChecker;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;

public class SpellCheckTest
{
    public static void main(String[] args) throws IOException {
        Directory suggDir = MMapDirectory.open(Paths.get("D:/code/piaget_labo/lucene/sugg"));
        SpellChecker spell= new SpellChecker(suggDir);
        
        
        MMapDirectory.open(Paths.get("D:/code/piaget_labo/lucene/piaget"));
        
        IndexReader reader = DirectoryReader.open(MMapDirectory.open(Paths.get("D:/code/piaget_labo/lucene/piaget")));
        Dictionary spellWords = new LuceneDictionary(reader, "text_cloud");
        spell.indexDictionary(spellWords, new IndexWriterConfig(new FindAnalyzer()), true);
        
        String[] suggs = spell.suggestSimilar("piaget", 10);
        System.out.println(Arrays.toString(suggs));
        
        spell.close();
        reader.close();
    }

}
