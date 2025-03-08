package com.github.oeuvres.alix.vec;

import java.io.File;
import java.io.IOException;
import java.util.List;

import com.github.oeuvres.jword2vec.Searcher;
import com.github.oeuvres.jword2vec.Searcher.Match;
import com.github.oeuvres.jword2vec.Searcher.UnknownWordException;
import com.github.oeuvres.jword2vec.Word2VecModel;

public class Word2vecDistance
{
    
    static void distance() throws IOException, UnknownWordException
    {
        final String word = "intelligence";
        File modelFile = new File("../word2vec/piaget.bin");
        Word2VecModel model = Word2VecModel.fromBinFile(modelFile);
        Searcher searcher =  model.forSearch();
        List<Match> matches = searcher.getMatches(word, 20);
        for (final Match match: matches) {
            System.out.println(match.distance() + "\t" + match.match());
        }
    }

    public static void main(String[] args) throws Exception
    {
        distance();
    }
}
