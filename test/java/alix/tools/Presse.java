/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
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
package alix.tools;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;

import org.json.JSONArray;
import org.json.JSONObject;

import alix.fr.Tokenizer;
import alix.util.Occ;

/**
 * Parse a specific json text collections.
 * http://data.theeuropeanlibrary.org/download/newspapers-by-country/FRA/
 * http://gallica.bnf.fr/ark:/12148/cb34355551z/date
 */
public class Presse
{
  static String dir;
  static String journal;
  int filecount;
  static PreparedStatement doc;
  static PreparedStatement blob;
  static Calendar cal = Calendar.getInstance();
  static SimpleDateFormat dateIso = new SimpleDateFormat("yyyy-MM-dd");
  static int[] days = { 0, 7, 1, 2, 3, 4, 5, 6 };
  static Connection conn = null;

  static public void walk(File dir) throws IOException, SQLException, ParseException
  {
    File[] ls = dir.listFiles();
    Arrays.sort(ls);
    for (final File src : ls) {
      if (src.getName().startsWith("."))
        continue;
      if (src.isDirectory()) {
        walk(src);
        continue;
      }
      if (!src.getName().endsWith(".fulltext.json"))
        continue;
      String source = new String(Files.readAllBytes(Paths.get(src.toString())), StandardCharsets.UTF_8);
      json(source);
    }
  }

  static public void json(String source) throws SQLException, ParseException
  {
    JSONObject json = new JSONObject(source);
    // [date, contentAsText, identifier, creator, format, description, language,
    // source, type, title, relation, contributor, rights, publisher]
    String identifier = json.getJSONArray("source").getString(1);
    if (!identifier.startsWith("http"))
      identifier = json.getJSONArray("identifier").getString(1);
    if (!identifier.startsWith("http"))
      System.out.println(json.get("identifier"));
    String date = json.getJSONArray("date").getString(0);
    cal.setTime(dateIso.parse(date));
    String name = dir + "_" + date; // name
    doc.setString(3, dir); // group
    doc.setString(4, journal + ", " + date); // title
    doc.setString(6, date); // date
    doc.setInt(7, cal.get(Calendar.YEAR));
    doc.setInt(8, 1 + cal.get(Calendar.MONTH));
    doc.setInt(9, cal.get(Calendar.DAY_OF_MONTH));
    doc.setInt(10, days[cal.get(Calendar.DAY_OF_WEEK)]);

    if (!json.has("contentAsText")) {
      // OCR raté, on enregistre quelque chose ?
    }
    else {
      JSONArray pages = json.getJSONArray("contentAsText");
      for (int p = 0; p < pages.length(); p++) {
        doc.setString(1, name + "_f" + (p + 1));
        doc.setString(2, identifier + "/f" + (p + 1)); // url
        doc.setInt(5, (p + 1)); // page
        String text = pages.getString(p);
        text.replaceAll("\n", "\n¶\n");
        doc.setInt(11, text.length());
        blob.setString(2, text); // text
        try {
          doc.executeUpdate();
          ResultSet keys = doc.getGeneratedKeys();
          keys.next();
          blob.setInt(1, keys.getInt(1));
          blob.executeUpdate();
          keys.close();
        }
        catch (Exception e) {
          System.out.println(dir + " " + date);
          e.printStackTrace(System.out);
        }
      }
    }
  }

  /**
   * 
   * @param folder
   * @throws IOException
   * @throws SQLException
   * @throws ParseException
   */
  public static void load(String folder) throws IOException, SQLException, ParseException
  {
    String sql = "INSERT INTO doc(" + "name" // 1
        + ", url" // 2
        + ", collection" // 3
        + ", title" // 4
        + ", page" // 5
        + ", date" // 6
        + ", year" // 7
        + ", month"// 8
        + ", daymonth" // 9
        + ", dayweek" // 10
        + ", chars" // 11
        + ") VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    doc = conn.prepareStatement(sql);
    blob = conn.prepareStatement("INSERT INTO blob(id, text) VALUES (?, ?);");
    String[][] list = { 
        {"l_action_francaise", "L’Action Française"}, 
        {"la_croix", "La Croix"},
        {"le_figaro", "Le Figaro"}, 
        {"l_humanite", "L’Humanité"}, 
        {"le_petit_journal", "Le Petit Journal"},
        {"le_temps", "Le Temps"} 
    };
    for (String[] row : list) {
      dir = row[0];
      journal = row[1];
      System.out.println(journal);
      doc.getConnection().setAutoCommit(false);
      walk(new File(folder, dir));
      doc.getConnection().commit();
    }
    System.out.println("FINI");
  }
  
  /**
   * Test tokenisation of a file
   * @throws IOException
   * @throws ParseException
   */
  public static void test() throws IOException, ParseException
  {
    String src = "/home/fred/code/presse/19340101.metadata.fulltext.json";
    String cont = new String(Files.readAllBytes(Paths.get(src)), StandardCharsets.UTF_8);
    JSONObject json = new JSONObject(cont);
    JSONArray pages = json.getJSONArray("contentAsText");
    String dest = "/home/fred/code/presse/test.txt";
    PrintWriter out = new PrintWriter(dest);
    for (int p = 0; p < pages.length(); p++) {
      String text = pages.getString(p);
      text.replaceAll("\n", "\n¶\n");
      Tokenizer toks = new Tokenizer(text);
      Occ occ;
      while ((occ = toks.word()) != null) {
        out.println(occ);
      }
    }
    out.close();
  }

  /**
   * Create a base to load the texts
   * @throws IOException
   * @throws SQLException
   * @throws ParseException
   */
  public static void base(String srcDir, String destFile) throws IOException, SQLException, ParseException
  {
    InputStream in = Presse.class.getResourceAsStream("alix.sqlite");
    Path dest = Paths.get(destFile);
    Files.copy(in, dest);
    System.out.println(dest);
    conn = DriverManager.getConnection("jdbc:sqlite:" + dest);
    Statement stmt = conn.createStatement();

    stmt.execute("PRAGMA locking_mode = EXCLUSIVE;");
    load(srcDir);
    stmt.execute("PRAGMA locking_mode = NORMAL;");
    stmt.execute("UPDATE doc SET julianday = CAST(julianday(date) AS INTEGER)");
    stmt.execute("DROP TABLE lem;");
    stmt.execute("DROP TABLE orth;");
    stmt.execute("DROP TABLE occ;");
    stmt.close();
    conn.close();
  }
  
  public static void main(String args[]) throws IOException, SQLException, ParseException
  {
    base(args[0], args[1]);
  }

}
