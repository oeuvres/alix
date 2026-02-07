package com.github.oeuvres.alix.lucene.analysis;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.common.Tag;
import com.github.oeuvres.alix.fr.TagFr;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.util.CSVReader;
import com.github.oeuvres.alix.util.Chain;
import com.github.oeuvres.alix.util.CharSlab;
import com.github.oeuvres.alix.util.CSVReader.Row;

public abstract class Lexicon
{
    /** Logger */
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    

    abstract public LexEntry name(CharTermAttribute att);
    abstract public boolean norm(CharTermAttribute att);
    abstract public LexEntry word(CharTermAttribute att);
    
    /**
     * Load a file as a dictionary.
     * 
     * @param file file path.
     * @throws IOException file errors.
     */
    public static void load(final String key, final File file) throws IOException
    {
        res = file.getAbsolutePath();
        Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
        // default is replace
        load(key, reader, true);
    }
    
    /**
     * An entry for a dictionary te get lemma from
     * an inflected form.
     */
    public static class LexEntry
    {
        /** Inflected form.  */
        final public char[] graph;
        /** A lexical word type. */
        final public int tag;
        /** lemma form. */
        final public char[] lem;

        /**
         * Full constructor with cells coming from a {@link CSVReader}
         * 
         * @param graph graphical form found in texts.
         * @param tag short name for a lexical type.
         * @param graph normalized orthographic form.
         * @param lem lemma form.
         */
        public LexEntry(Logger logger, final Chain graph, final Chain tag, final Chain lem) {
            if (graph.isEmpty()) {
                logger.debug(res + " graph=\"" + graph + "\"? Graph empty, tag=\"" + tag + "\", lem=\"" + lem +"\"");
            }
            graph.replace('’', '\'');
            Tag tagEnum = null;
            String tagKey = tag.toString();
            try {
               tagEnum = TagFr.valueOf(tagKey);
            }
            catch (Exception e) {
                try {
                    tagEnum = Upos.valueOf(tagKey);
                }
                catch (Exception ee) {
                    logger.debug(res + " graph=\"" + graph + "\" tag=\"" + tag + "\"? tag not found.");
                }
            }
            
            if (tagEnum != null) {
                this.tag = tagEnum.code();
            }
            else {
                this.tag = 0;
            }
            this.graph = graph.toCharArray();
            if (lem == null || lem.isEmpty()) {
                this.lem = null;
            }
            else {
                this.lem = lem.toCharArray();
            }
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(TagFr.name(this.tag));
            if (graph != null)
                sb.append(" graph=\"").append(graph).append("\"");
            if (lem != null)
                sb.append(" lem=\"").append(lem).append("\"");
            // if (branch) sb.append(" BRANCH");
            // if (leaf) sb.append(" LEAF");
            // sb.append("\n");
            return sb.toString();
        }
    }

}
