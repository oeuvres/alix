/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.lucene.analysis;

import java.util.Objects;

import opennlp.tools.ml.BeamSearch;
import opennlp.tools.ml.model.SequenceClassificationModel;
import opennlp.tools.postag.DefaultPOSSequenceValidator;
import opennlp.tools.postag.POSContextGenerator;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerFactory;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.postag.TagDictionary;
import opennlp.tools.util.Sequence;
import opennlp.tools.util.SequenceValidator;

/**
 * Shared OpenNLP POS decoder.
 *
 * <p>This class is thread-safe with OpenNLP 3.0.0-M6. The supplied
 * {@link TagDictionary} must not be mutated concurrently with tagging.</p>
 */
public final class PosTagger
{
    /** Statistical sequence model used for beam-search decoding. */
    private final SequenceClassificationModel model;

    /** Feature-context generator. */
    private final POSContextGenerator contextGenerator;

    /** Optional dictionary-aware sequence validator. */
    private final SequenceValidator<String> sequenceValidator;

    /**
     * Builds a decoder using the tag dictionary embedded in the model, if any.
     *
     * @param posModel OpenNLP POS model
     */
    public PosTagger(final POSModel posModel)
    {
        this(posModel, null);
    }

    /**
     * Builds a decoder constrained by an external tag dictionary.
     *
     * <p>For a token absent from the dictionary, all model outcomes remain
     * available. For a token present in the dictionary, only its listed tags
     * are accepted by beam search.</p>
     *
     * @param posModel OpenNLP POS model
     * @param tagDictionary external tag dictionary, or {@code null} to use the
     *        model factory's normal sequence validator
     */
    public PosTagger(final POSModel posModel, final TagDictionary tagDictionary)
    {
        Objects.requireNonNull(posModel, "posModel");

        final POSTaggerFactory factory = posModel.getFactory();

        int beamSize = POSTaggerME.DEFAULT_BEAM_SIZE;
        final String beamSizeString = posModel.getManifestProperty(BeamSearch.BEAM_SIZE_PARAMETER);
        if (beamSizeString != null) {
            beamSize = Integer.parseInt(beamSizeString);
        }

        this.model = Objects.requireNonNull(
            posModel.getPosSequenceModel(),
            "POS model has no sequence model"
        );
        this.contextGenerator = factory.getPOSContextGenerator(beamSize);
        this.sequenceValidator = (tagDictionary == null)
            ? factory.getSequenceValidator()
            : new DefaultPOSSequenceValidator(tagDictionary);
    }

    /**
     * Decodes the best POS sequence for a sentence.
     *
     * @param sentence token sequence
     * @return best sequence, or {@code null} if no valid sequence exists
     */
    public Sequence tag(final String[] sentence)
    {
        Objects.requireNonNull(sentence, "sentence");
        return model.bestSequence(sentence, null, contextGenerator, sequenceValidator);
    }
}
