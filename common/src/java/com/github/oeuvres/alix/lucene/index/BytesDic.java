/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic tools for French,
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
package com.github.oeuvres.alix.lucene.index;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

/**
 * A word list as a dictionary, especially designed for stop words filtering 
 * from a {@link org.apache.lucene.index.TermsEnum#next()}
 */
public class BytesDic
{

    private BytesDic()
    {
    }
    
    /**
     * Load a word list from an {@link InputStream}
     * @param file
     * @throws IOException
     */
    static public void load(final BytesRefHash dic, final File file) throws IOException
    {
        Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
        load(dic, reader);
    }
    
    /**
     * Load a word list from an {@link InputStream}, from ServletContext.getResourceAsStream(String path),
     * or {@link Class#getResourceAsStream(String)}.
     * @param stream resource to load.
     * @throws IOException
     */
    static public void load(final BytesRefHash dic, final InputStream stream) throws IOException
    {
        Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        load(dic, reader);
    }
    
    /**
     * Load a word list from a char reader.
     * 
     * @param reader reader to load.
     * @throws IOException
     */
    static public void load(final BytesRefHash dic, final Reader reader) throws IOException
    {
        try (BufferedReader br = getBufferedReader(reader)) {
            String word = null;
            while ((word = br.readLine()) != null) {
                word = word.trim();
                // skip blank lines
                if (word.isEmpty()) continue;
                // skip comment
                if (word.charAt(0) == '#') continue;
                BytesRef bytes = new BytesRef(word);
                dic.add(bytes);
            }
        }
    }
    
    /**
     * Ensure buffer for the reader.
     * @param reader
     * @return
     */
    private static BufferedReader getBufferedReader(Reader reader)
    {
        return (reader instanceof BufferedReader)
            ? (BufferedReader) reader
            : new BufferedReader(reader);
    }

}
