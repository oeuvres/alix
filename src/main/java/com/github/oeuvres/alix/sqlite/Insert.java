/*
 * Alix, A Lucene Indexer for XML documents.
 * 
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
package com.github.oeuvres.alix.sqlite;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.logging.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import com.github.oeuvres.alix.lucene.analysis.FilterLemmatize;
import com.github.oeuvres.alix.lucene.analysis.FilterFrPersname;
import com.github.oeuvres.alix.lucene.analysis.TokenizerFr;
import com.github.oeuvres.alix.lucene.analysis.FilterLocution;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;
import com.github.oeuvres.alix.util.Dir;

/**
 * Index words and lemma a set of html files in an SQLite base
 */
public class Insert
{
    private static Logger logger = Logger.getLogger("alix.sqlite.insert");

    /**
     * A lucene analyzer to get lems
     */
    static class AnalyzerSite extends Analyzer
    {
        @Override
        protected TokenStreamComponents createComponents(String fieldName)
        {
            final Tokenizer source = new TokenizerFr();
            TokenStream result = new FilterLemmatize(source);
            result = new FilterLocution(result);
            result = new FilterFrPersname(result);
            return new TokenStreamComponents(source, result);
        }

    }

    /** Instantiation of lem */
    static Analyzer ana = new AnalyzerSite();

    private static HashMap<CharsAttImpl, Integer> lemDic = new HashMap<>();
    private static HashMap<CharsAttImpl, Integer> orthDic = new HashMap<>();

    /** Sqlite Connection */
    private static Connection con;
    /** Prepared query, insert a document */
    private static PreparedStatement qDoc;
    private static final int DOC_CODE = 1;
    private static final int DOC_FILEMTIME = 2;
    private static final int DOC_FILESIZE = 3;
    private static final int DOC_TITLE = 4;
    private static final int DOC_HTML = 5;
    /** Prepared query, insert a lemma */
    private static PreparedStatement qLem;
    /** Prepared query, insert an orthographic form */
    private static PreparedStatement qOrth;
    /** Prepared query, insert a token */
    private static PreparedStatement qTok;
    private static final int TOK_DOC = 1;

    /**
     * Connect to an sqlite database. Everything is broken if it can’t work, let it
     * cry
     * 
     * @throws IOException  Lucene errors.
     * @throws SQLException
     */
    public static void connect(final File sqlite) throws IOException, SQLException
    {
        if (sqlite.exists()) {
            logger.info("\"" + sqlite + "\" has been deleted. Incremental indexing is not yet supported.");
        }
        InputStream source = Insert.class.getResourceAsStream("alix.db");
        Files.createDirectories(sqlite.toPath().getParent());
        Files.copy(source, sqlite.toPath(), StandardCopyOption.REPLACE_EXISTING);
        String dburl = "jdbc:sqlite:" + sqlite.getCanonicalFile().toString().replace('\\', '/');
        con = DriverManager.getConnection(dburl);
        Statement stmt = con.createStatement();
        stmt.execute("PRAGMA foreign_keys = 0;");
        stmt.execute("PRAGMA journal_mode = OFF;");
        stmt.execute("PRAGMA synchronous = OFF;");
        qDoc = con.prepareStatement(
                "INSERT INTO doc" + " (code, filemtime, filesize, title, html)" + " VALUES" + " (?, ?, ?, ?, ?)",
                PreparedStatement.RETURN_GENERATED_KEYS);
        // For incremental indexation
        // qOrthSel = con.prepareStatement("SELECT * FROM orth WHERE form = ?");
        // qLemSel = con.prepareStatement("SELECT * FROM lem WHERE form = ?");
        qLem = con.prepareStatement("INSERT INTO lem (form, cat) VALUES (?, ?)",
                PreparedStatement.RETURN_GENERATED_KEYS);
        qOrth = con.prepareStatement("INSERT INTO orth (form, cat, lem) VALUES (?, ?, ?)",
                PreparedStatement.RETURN_GENERATED_KEYS);
        qTok = con.prepareStatement("INSERT INTO tok (doc, orth, offset, length, cat, lem) VALUES (?, ?, ?, ?, ?, ?)");

    }

    /**
     * 
     * @param dir
     * @throws SQLException
     * @throws IOException  Lucene errors.
     */
    public static void crawl(File dir) throws SQLException, IOException
    {
        logger.info(dir.getAbsolutePath());
        con.setAutoCommit(false);
        // file structure book/chapter.html
        List<Path> paths = Dir.ls(dir + "/*/*.html");
        for (Path p : paths) {
            File f = p.toFile();
            String code = f.getParentFile().getName() + "/" + f.getName().substring(0, f.getName().lastIndexOf('.'));
            qDoc.setString(DOC_CODE, code);
            qDoc.setLong(DOC_FILEMTIME, f.lastModified());
            qDoc.setLong(DOC_FILESIZE, f.length());
            qDoc.setString(DOC_TITLE, code);
            String html = Files.readString(f.toPath());
            qDoc.setString(DOC_HTML, html);
            qDoc.execute();
            ResultSet keys = qDoc.getGeneratedKeys();
            keys.next(); // should be true or let it cry
            final int docId = keys.getInt(1);
            qTok.setInt(TOK_DOC, docId); // same docid
            logger.fine(docId + " " + f.toString());
            parse(html);
        }
        con.commit();
    }

    public static void unzip(File file) throws SQLException, IOException
    {
        con.setAutoCommit(false);
        ZipInputStream zis = new ZipInputStream(new FileInputStream(file), StandardCharsets.UTF_8);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory())
                continue;
            final String path = entry.getName();
            // test if extension html
            qDoc.setString(DOC_CODE, path);
            qDoc.setLong(DOC_FILEMTIME, entry.getTime());
            qDoc.setLong(DOC_FILESIZE, entry.getSize());
            qDoc.setString(DOC_TITLE, path);
            final byte[] bytes = zis.readAllBytes();
            final String html = new String(bytes, StandardCharsets.UTF_8);
            // qDoc.setBytes(DOC_HTML, bytes); // correct but String maybe more robust
            qDoc.setString(DOC_HTML, html);
            qDoc.execute();
            ResultSet keys = qDoc.getGeneratedKeys();
            keys.next(); // should be true or let it cry
            final int docId = keys.getInt(1);
            qTok.setInt(TOK_DOC, docId); // same docid
            logger.fine(entry.toString());
            parse(html);
        }
        con.commit();
    }

    public static void parse(final String xml) throws IOException, SQLException
    {
        final int ORTH = 2;
        final int OFFSET = 3;
        final int LENGTH = 4;
        final int CAT = 5;
        final int LEM = 6;
        TokenStream stream = ana.tokenStream("stats", new StringReader(xml));
        final CharsAttImpl termAtt = (CharsAttImpl) stream.addAttribute(CharTermAttribute.class);
        final CharsAttImpl orthAtt = (CharsAttImpl) stream.addAttribute(OrthAtt.class);
        final CharsAttImpl lemAtt = (CharsAttImpl) stream.addAttribute(LemAtt.class);
        final OffsetAttribute attOff = stream.addAttribute(OffsetAttribute.class);
        final FlagsAttribute attFlags = stream.addAttribute(FlagsAttribute.class);
        stream.reset();
        // print all tokens until stream is exhausted
        while (stream.incrementToken()) {
            final int flag = attFlags.getFlags();
            qTok.setInt(OFFSET, attOff.startOffset());
            qTok.setInt(LENGTH, attOff.endOffset() - attOff.startOffset());
            qTok.setInt(CAT, flag);
            // empty position, record as a stop for locutions
            if (termAtt.isEmpty()) {
                qTok.setInt(ORTH, 0);
                qTok.setInt(LEM, 0);
                qTok.execute();
                continue;
            }
            // filter punctuation ?

            // get a lemma
            CharsAttImpl lem = null;
            if (!lemAtt.isEmpty())
                lem = lemAtt;
            else if (!orthAtt.isEmpty())
                lem = orthAtt;
            else if (!orthAtt.isEmpty())
                lem = termAtt;
            int lemId = 0;
            if (lem == null)
                ; // no lem, strange
            else if ((lemId = lemDic.getOrDefault(lem, 0)) != 0)
                ; // lem already seen
            else { // a lem to insert
                   // if incremental, should check if lemId not already recorded
                qLem.setString(1, lem.toString());
                qLem.setInt(2, flag);
                qLem.execute();
                ResultSet keys = qLem.getGeneratedKeys();
                keys.next(); // should be true or let it cry
                lemId = keys.getInt(1);
                lemDic.put(new CharsAttImpl(lem), lemId);
            }
            qTok.setInt(LEM, lemId);

            // get an orthographic form
            CharsAttImpl orth = null;
            if (!orthAtt.isEmpty())
                orth = orthAtt;
            else if (!termAtt.isEmpty())
                orth = termAtt;
            int orthId = 0;
            if (orth == null)
                ; // strange
            else if ((orthId = orthDic.getOrDefault(orth, 0)) != 0)
                ; // orth already seen
            else { // a form to insert
                   // if incremental, should check if lemId not already recorded
                qOrth.setString(1, orth.toString());
                qOrth.setInt(2, flag);
                qOrth.setInt(3, lemId);
                qOrth.execute();
                ResultSet keys = qOrth.getGeneratedKeys();
                keys.next(); // should be true or let it cry
                orthId = keys.getInt(1);
                orthDic.put(new CharsAttImpl(orth), orthId);
            }
            qTok.setInt(ORTH, orthId);
            qTok.execute();
        }
        stream.close();
    }

}
