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
 * Shared OpenNLP POS decoder with optional per-token constraints.
 *
 * <p>Thread-safe with OpenNLP 3.0.0-M6, provided the supplied tag dictionary
 * is not mutated concurrently. Forced tags are local to each decoding call.</p>
 */
public final class PosTagger
{
    private final SequenceClassificationModel model;
    private final POSContextGenerator contextGenerator;
    private final TagDictionary tagDic;
    private final SequenceValidator<String> sequenceValidator;

    /**
     * Builds a decoder using the model's embedded tag dictionary, if any.
     * @param posModel OpenNLP POS model
     */
    public PosTagger(final POSModel posModel)
    {
        this(posModel, null);
    }

    /**
     * Builds a decoder with an optional external tag dictionary.
     * @param posModel OpenNLP POS model
     * @param tagDic external tag dictionary, or null for the model's dictionary
     */
    public PosTagger(final POSModel posModel, final TagDictionary tagDic)
    {
        Objects.requireNonNull(posModel, "posModel");
        final POSTaggerFactory factory = posModel.getFactory();
        int beamSize = POSTaggerME.DEFAULT_BEAM_SIZE;
        final String beamSizeString = posModel.getManifestProperty(BeamSearch.BEAM_SIZE_PARAMETER);
        if (beamSizeString != null) {
            beamSize = Integer.parseInt(beamSizeString);
        }
        this.model = Objects.requireNonNull(posModel.getPosSequenceModel(),
            "POS model has no sequence model");
        this.contextGenerator = factory.getPOSContextGenerator(beamSize);
        this.tagDic = tagDic == null ? factory.getTagDictionary() : tagDic;
        this.sequenceValidator = tagDic == null
            ? factory.getSequenceValidator()
            : new DefaultPOSSequenceValidator(tagDic);
    }

    /**
     * Tests whether a tag is listed for a word in the lexical dictionary.
     * @param word word to look up
     * @param tag model POS label
     * @return true if the dictionary lists the label
     */
    public boolean hasTag(final String word, final String tag)
    {
        Objects.requireNonNull(tag, "tag");
        final String[] tags = tags(word);
        if (tags == null) return false;
        for (String value : tags) {
            if (tag.equals(value)) return true;
        }
        return false;
    }

    /**
     * Decodes the best POS sequence subject to the lexical dictionary.
     * @param sentence token sequence
     * @return best sequence, or null if no valid sequence exists
     */
    public Sequence tag(final String[] sentence)
    {
        return tag(sentence, null);
    }

    /**
     * Decodes the best POS sequence with optional authoritative model labels.
     * Forced labels take precedence over dictionary restrictions. No constraint
     * is relaxed when decoding fails.
     * @param sentence token sequence
     * @param forcedTags parallel model labels; null entries are unconstrained,
     *        or null for the entire array to use the usual dictionary validator
     * @return best sequence, or null if no valid sequence exists
     * @throws IllegalArgumentException if the arrays differ in length
     */
    public Sequence tag(final String[] sentence, final String[] forcedTags)
    {
        Objects.requireNonNull(sentence, "sentence");
        if (forcedTags == null) {
            return model.bestSequence(sentence, null, contextGenerator, sequenceValidator);
        }
        if (forcedTags.length != sentence.length) {
            throw new IllegalArgumentException("One forced tag per sentence token is required");
        }
        final SequenceValidator<String> validator = (i, input, previous, outcome) ->
            forcedTags[i] == null
                ? sequenceValidator.validSequence(i, input, previous, outcome)
                : forcedTags[i].equals(outcome);
        return model.bestSequence(sentence, null, contextGenerator, validator);
    }

    /**
     * Returns the lexical dictionary's possible labels for a word.
     * @param word word to look up
     * @return dictionary labels, or null if no entry or dictionary exists
     */
    public String[] tags(final String word)
    {
        Objects.requireNonNull(word, "word");
        return tagDic == null ? null : tagDic.getTags(word);
    }
}
