package com.github.oeuvres.alix.lucene.analysis;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

public class opennlp
{
    static void pos() throws IOException
    {
        try (InputStream modelIn = new FileInputStream("models/opennlp-fr-ud-gsd-pos-1.2-2.5.0.bin")) {
            final POSModel posModel = new POSModel(modelIn);
            POSTaggerME tagger = new POSTaggerME(posModel);
            String sentence = "Personne ne veut admettre que la personne a été pensé comme le problème du siècle , et les personnes ont la cote . Le problème technique de la technique .";
            String[] toks = sentence.split(" ");
            String[] tags = tagger.tag(toks);
            for (int i = 0; i < toks.length; i++) {
                System.out.println(toks[i]+"_"+tags[i]);
            }
            String[] allTags = tagger.getAllPosTags();
            Arrays.sort(allTags);
            System.out.println(Arrays.toString(allTags));
        }
    }
    
    public static void main(String[] args) throws IOException
    {
        pos();
    }
}
