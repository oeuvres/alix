package com.github.oeuvres.alix.vec;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import org.deeplearning4j.models.embeddings.loader.WordVectorSerializer;
import org.deeplearning4j.models.word2vec.Word2Vec;

/**
 * https://arxiv.org/pdf/1609.08293 (full matrix)
 */

public class Vex
{
    static void distance()
    {
        final String word = "liberté";
        File gModel = new File("C:/code/word2vec/rougemont.bin");
        Word2Vec vec = WordVectorSerializer.readWord2VecModel(gModel);
        System.out.println(vec.wordsNearest(Arrays.asList("réel", "réalité"), Arrays.asList(), 20));
        gModel = new File("C:/code/word2vec/rougemont_nostop.bin");
        vec = WordVectorSerializer.readWord2VecModel(gModel);
        System.out.println(vec.wordsNearest(Arrays.asList("réel", "réalité"), Arrays.asList(), 20));
        System.out.println(vec.wordsNearest(Arrays.asList("Suisse"), Arrays.asList(), 20));
        System.out.println(vec.wordsNearest(Arrays.asList("France", "Europe"), Arrays.asList(), 20));
        // bad, only letter similarity
        // System.out.println(vec.similarWordsInVocabTo(word, 0.9));
    }
    
    public static void main(String[] args) throws IOException
    {
        distance();
    }

}
