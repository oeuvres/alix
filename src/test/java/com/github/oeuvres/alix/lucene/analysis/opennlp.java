package com.github.oeuvres.alix.lucene.analysis;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import opennlp.tools.ml.BeamSearch;
import opennlp.tools.ml.model.SequenceClassificationModel;
import opennlp.tools.postag.POSContextGenerator;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTagFormat;
import opennlp.tools.postag.POSTaggerFactory;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.util.Sequence;
import opennlp.tools.util.SequenceValidator;

public class opennlp
{
    static void pos() throws IOException
    {
        try (InputStream modelIn = new FileInputStream("models/opennlp-fr-ud-gsd-pos-1.2-2.5.0.bin")) {
            final POSModel posModel = new POSModel(modelIn);
            POSTaggerME tagger = new POSTaggerME(posModel);
            String sentence = "Personne ne veut admettre que la personne a été pensé comme le problème du siècle , les personnes ont la cote . Le problème technique de la technique .";
            String[] toks = sentence.split(" ");
            String[] tags = tagger.tag(toks);
            for (int i = 0; i < toks.length; i++) {
                System.out.println(toks[i]+"_"+tags[i]);
            }
            
            
            System.out.println();
            // try to override the String array[] for efficiency
            POSTaggerFactory factory = posModel.getFactory();
            int beamSize = POSTaggerME.DEFAULT_BEAM_SIZE;
            String beamSizeString = posModel.getManifestProperty(BeamSearch.BEAM_SIZE_PARAMETER);
            if (beamSizeString != null) {
              beamSize = Integer.parseInt(beamSizeString);
            }
            POSContextGenerator cg = factory.getPOSContextGenerator(beamSize);
            SequenceValidator<String> sequenceValidator = factory.getSequenceValidator();
            SequenceClassificationModel model = posModel.getPosSequenceModel();
            String[] charToks = sentence.split(" ");
            Sequence bestSequence = model.bestSequence(charToks, null, cg, sequenceValidator);
            System.out.println(bestSequence);
        }
    }
    
    public static void main(String[] args) throws IOException
    {
        pos();
    }
}
