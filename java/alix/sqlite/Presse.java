package alix.sqlite;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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

import org.json.JSONObject;
import org.json.JSONArray;

/**
 * http://data.theeuropeanlibrary.org/download/newspapers-by-country/FRA/
 * http://gallica.bnf.fr/ark:/12148/cb34355551z/date
 * 
 * @author user
 *
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
   * Test the Class
   * 
   * @param args
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
        { "l_action_francaise", "L’Action Française" }, 
        { "la_croix", "La Croix" },
        { "le_figaro", "Le Figaro" }, 
        { "l_humanite", "L’Humanité" }, 
        { "le_petit_journal", "Le Petit Journal" },
        { "le_temps", "Le Temps" } 
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

  public static void main(String args[]) throws IOException, SQLException, ParseException
  {
    String root = ".";
    InputStream in = String.class.getResourceAsStream("/res/alix.sqlite");
    Path dest = Paths.get(root, "presse_blobs.sqlite");
    Files.copy(in, dest);
    System.out.println(dest);
    conn = DriverManager.getConnection("jdbc:sqlite:" + dest);
    in = String.class.getResourceAsStream("/res/alix.sqlite");
    dest = Paths.get(root, "presse.sqlite");
    Files.copy(in, dest);
    Statement stmt = conn.createStatement();

    stmt.execute("PRAGMA locking_mode = EXCLUSIVE;");
    load(root + "/json/");
    stmt.execute("PRAGMA locking_mode = NORMAL;");
    stmt.execute("UPDATE doc SET julianday = CAST(julianday(date) AS INTEGER)");
    stmt.execute("DROP TABLE lem;");
    stmt.execute("DROP TABLE orth;");
    stmt.execute("DROP TABLE occ;");
    System.out.println(dest);
    stmt.execute("ATTACH DATABASE '"+dest+"' AS presse");
    stmt.execute("INSERT INTO presse.doc SELECT * FROM main.doc");
    stmt.close();
    conn.close();
  }

}
