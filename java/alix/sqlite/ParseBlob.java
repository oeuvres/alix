package alix.sqlite;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import alix.fr.Lexik;
import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.util.DicFreq;
import alix.util.Occ;
import alix.util.Term;

public class ParseBlob
{
  /** Sqlite connexion */
  Connection conn;
  /** Dictionary of orthographic form with an index */
  DicFreq orthDic = new DicFreq();
  /** Dictionary of orthographic form with an index */
  DicFreq lemDic = new DicFreq();
  /** Max index for stop words */
  int stopoffset;
  
  /**
   * Constructor
   * 
   * @throws SQLException
   */
  public ParseBlob(File sqlite) throws SQLException {
    String url = "jdbc:sqlite:" + sqlite.toString();
    conn = DriverManager.getConnection(url);
    orthDic.put(""); // rien=1
    for (String word : Tag.CODE.keySet())
      orthDic.put(word);
    for (String word : Lexik.STOP)
      orthDic.put(word, Lexik.cat(word));
    this.stopoffset = orthDic.put("STOPOFFSET");
  }

  /**
   * Parse record, populate undic
   * 
   * @throws SQLException
   */
  public void walk() throws IOException, SQLException
  {
    PreparedStatement blob = conn.prepareStatement("SELECT id, text FROM blob LIMIT 10");
    ResultSet rs = blob.executeQuery();
    PreparedStatement insOcc = conn.prepareStatement("INSERT INTO occ(doc, orth, tag, lem, start, end) "
        + "VALUES (?, ?, ?, ?, ?, ?)");
    while (rs.next()) {
      int doc = rs.getInt(1);
      if (doc % 1000 == 0) System.out.println(doc);
      insOcc.getConnection().setAutoCommit(false);
      insOcc.setInt(1, doc);
      String text = rs.getString(2);
      Tokenizer toks = new Tokenizer(text, false);
      Occ occ;
      while ((occ = toks.word()) != null) {
        if (occ.tag().isPun())
          continue;
        if (occ.tag().equals(Tag.NULL))
          continue; // inconnu
        int lem = lemDic.put(occ.lem(), occ.tag().code());
        int orth = orthDic.put(occ.orth(), occ.tag().code(), lem);
        insOcc.setInt(2, orth);
        insOcc.setInt(3, occ.tag().code());
        insOcc.setInt(4, lem);
        insOcc.setInt(5, occ.start());
        insOcc.setInt(6, occ.end());
        insOcc.executeUpdate();
      }
      insOcc.getConnection().commit();
    }
    PreparedStatement insOrth = conn.prepareStatement("INSERT INTO orth(id, form, tag, lem) VALUES (?, ?, ?, ?)");
    insOrth.getConnection().setAutoCommit(false);
    for (DicFreq.Entry entry: orthDic.entries()) {
      if (entry == null ) continue; // temp hack
      insOrth.setInt(1, entry.code());
      insOrth.setString(2, entry.label());
      insOrth.setInt(3, entry.tag());
      insOrth.setInt(4, entry.count());
      insOrth.executeUpdate();
    }
    insOrth.getConnection().commit();
    
    PreparedStatement insLem = conn.prepareStatement("INSERT INTO lem(id, form, tag) VALUES (?, ?, ?)");
    insLem.getConnection().setAutoCommit(false);
    for (DicFreq.Entry entry: lemDic.entries()) {
      if (entry == null ) continue; // temp hack
      insLem.setInt(1, entry.code());
      insLem.setString(2, entry.label());
      insLem.setInt(3, entry.tag());
    }
    insLem.getConnection().commit();
  }
  

  public static void main(String[] args) throws IOException, SQLException
  {
    /*
     * if ( args == null || args.length < 1 ) { System.out.println(
     * "Usage : java -cp \"alix.jar\" alix.sqlite.ParseBlob base.sqlite" );
     * System.exit( 0 ); }
     */
    ParseBlob base = new ParseBlob(new File("/Local/presse/presse.sqlite"));
    base.walk();
    // base.unDic.csv(System.out, 300);
  }
}
