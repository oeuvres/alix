package alix.sqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class Stats
{

  public static void conc(Connection connOccs, Connection connTexts) throws SQLException
  {
    // prendre le code du mot
    PreparedStatement lem = connOccs.prepareStatement("SELECT * FROM lem WHERE form = ?");
    String form = "connaître";
    lem.setString(1, form);
    ResultSet rs = lem.executeQuery();
    // rs.first();
    int lemid = rs.getInt(1);
    // boucler sur les occurrences
    PreparedStatement qoccs = connOccs.prepareStatement("SELECT * FROM occ WHERE lem = ?");
    PreparedStatement qtext = connTexts.prepareStatement("SELECT text FROM blob WHERE id = ?");
    qoccs.setInt(1, lemid);
    rs = qoccs.executeQuery();
    int docid = -1;
    int limit = 100;
    int left = 100;
    int right = 100;
    String text = "";
    while (rs.next()) {
      int newdocid = rs.getInt("doc");
      if (docid != newdocid) {
        docid = newdocid;
        qtext.setInt(1, docid);
        ResultSet res = qtext.executeQuery();
        text = res.getString(1);
      }
      int start = rs.getInt("start");
      int end = rs.getInt("end");
      String line = text.substring(Math.max(start - left, 0), Math.min(end + right, text.length()));
      line = line.replace('\n', ' ');
      System.out.println(line);
      if (--limit < 0) break;
    }

  }


  public static void main(String args[]) throws SQLException
  {
    String occBase = "/home/fred/code/presse/presse_occs.sqlite";
    Connection connOccs = DriverManager.getConnection("jdbc:sqlite:" + occBase);
    String textBase = "/home/fred/code/presse/presse_textes.sqlite";
    Connection connTexts = DriverManager.getConnection("jdbc:sqlite:" + textBase);
    conc(connOccs, connTexts);
  }

}
