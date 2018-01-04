package alix.sqlite;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.util.DicFreq;
import alix.util.DicFreq.Entry;
import alix.util.Occ;

public class ParseBlob implements Runnable
{
  /** Sqlite connexion */
  private static Connection blobs;
  /** Sqlite connexion */
  private static Connection occs;
  /** Dictionary of orthographic form with an index */
  private static DicFreq orthDic; 
  /** Dictionary of orthographic form with an index */
  private static DicFreq lemDic;
  /** Number of pages */
  private static int pageCount;
  /** Local cursor on blob to process */
  private ResultSet pages;
  /** Start index in document list */
  private final int start;
  private final int end;
  
  /**
   * Open database before ops, and load dics.
   * 
   * @throws SQLException
   */
  public static int open(String occsSqlite, String blobsSqlite) throws SQLException 
  {
    occs = DriverManager.getConnection("jdbc:sqlite:" + occsSqlite);
    occs.createStatement().execute("PRAGMA locking_mode = EXCLUSIVE;");

    if (blobsSqlite == null) blobs = occs;
    else blobs = DriverManager.getConnection("jdbc:sqlite:" + blobsSqlite);
    ResultSet res = blobs.createStatement().executeQuery("SELECT MAX(id)+1 FROM blob");
    pageCount = res.getInt(1);
    res.close();

    
    Statement stmt  = occs.createStatement();
    // load orth dic
    orthDic = new DicFreq();
    res = stmt.executeQuery("SELECT * FROM orth ORDER BY id");
    while(res.next()) {
      orthDic.put(res.getString("form"), res.getInt("tag"), res.getInt("lem"));
    }
    res.close();
    lemDic = new DicFreq();
    res = stmt.executeQuery("SELECT * FROM lem ORDER BY id");
    while(res.next()) {
      lemDic.put(res.getString("form"), res.getInt("tag"));
    }
    
    res.close();
    return pageCount;
  }
  
  public static int pageCount()
  {
    return pageCount;
  }
  
  /**
   * Close database and update dics.
   * @throws SQLException
   */
  public static void close() throws SQLException
  {
    blobs.close();
    PreparedStatement stmt;
    occs.setAutoCommit(false);
    stmt = occs.prepareStatement("DELETE FROM orth");
    stmt.execute();
    stmt = occs.prepareStatement("INSERT INTO orth(id, form, tag, lem) VALUES (?, ?, ?, ?)");
    for (Entry entry: orthDic.entries()) {
      if (entry == null ) break; // last one
      stmt.setInt(1, entry.code());
      stmt.setString(2, entry.label());
      stmt.setInt(3, entry.tag());
      stmt.setInt(4, entry.count());
      stmt.execute();
    }
    stmt.close();
    stmt = occs.prepareStatement("DELETE FROM lem");
    stmt.execute();
    stmt = occs.prepareStatement("INSERT INTO lem(id, form, tag) VALUES (?, ?, ?)");
    for (Entry entry: lemDic.entries()) {
      if (entry == null ) break; // last one
      stmt.setInt(1, entry.code());
      stmt.setString(2, entry.label());
      stmt.setInt(3, entry.tag());
      stmt.execute();
    }
    occs.commit();
    stmt.execute("PRAGMA locking_mode = NORMAL;");
    stmt.close();
    occs.close();
  }

  /**
   * Constructor of the thread
   * @param offset
   * @param limit
   * @throws SQLException 
   */
  public ParseBlob(final int start, final int end) throws SQLException
  {
    this.start = start;
    this.end = end;
    PreparedStatement q = blobs.prepareStatement("SELECT id, text FROM blob WHERE id >= ? AND id < ?");
    q.setInt(1, start);
    q.setInt(2, end);
    pages = q.executeQuery();
  }
  
  /**
   * Parse record
   * 
   * @throws SQLException
   */
  public void run()
  {
    // String table = Thread.currentThread().getName().replaceAll("-", "");
    String table = "table"+start;
    try {
      Statement stmt = occs.createStatement();
      stmt.execute("CREATE TEMP TABLE '"+table+"' (doc, orth, tag, lem, start, end)");
      stmt.close();
      PreparedStatement ins = occs.prepareStatement(
        "INSERT INTO '"+table+"'"
        +" (doc, orth, tag, lem, start, end)"
        +" VALUES (?, ?, ?, ?, ?, ?)"
      );
      Tokenizer toks = new Tokenizer(false);
      int occs = 0;
      int page = 0;
      while (pages.next()) {
        int doc = pages.getInt(1);
        ins.setInt(1, doc);
        toks.text(pages.getString(2));
        Occ occ;
        while ((occ = toks.word()) != null) {
          if (occ.tag().isPun())
            continue;
          if (occ.tag().equals(Tag.NULL))
            continue; // inconnu
          int lem = lemDic.put(occ.lem(), occ.tag().code());
          int orth = orthDic.put(occ.orth(), occ.tag().code(), lem);
          ins.setInt(2, orth);
          ins.setInt(3, occ.tag().code());
          ins.setInt(4, lem);
          ins.setInt(5, occ.start());
          ins.setInt(6, occ.end());
          ins.executeUpdate();
          occs++;
        }
        page++;
      }
      stmt.execute(
          "INSERT INTO occ"
        + " (doc, orth, tag, lem, start, end)"
        + " SELECT doc, orth, tag, lem, start, end"
        + " FROM '"+table+"'"
      );
      stmt.execute("DROP TABLE "+table);
      stmt.close();
    }
    catch (SQLException e) {
      System.out.println(Thread.currentThread().getName());
      e.printStackTrace();
    }
    System.out.println(start);
  }
  

  public static void main(String[] args) throws IOException, SQLException, InterruptedException
  {
    String occs = "/Local/presse/presse.sqlite";
    if (args.length > 0) occs = args[0];
    String blobs = "/Local/presse/presse_blobs.sqlite";
    if (args.length > 1) blobs = args[1];
    int threads = 200;
    if (args.length > 2) threads = Integer.parseInt(args[2]);
    int limit = 1000;
    if (args.length > 3) limit = Integer.parseInt(args[3]);
    
    int pageCount = ParseBlob.open(occs, blobs);
    
    
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    
    long start = System.nanoTime();
    int max = pageCount;
    for (int offset = 0 ; offset < max; offset+=limit) {
      pool.execute(new ParseBlob(offset, offset+limit));
    }
    pool.shutdown();
    pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    System.out.println((System.nanoTime() - start)/1000000.0);
    ParseBlob.close();
    System.out.println((System.nanoTime() - start)/1000000.0);
    
    // base.unDic.csv(System.out, 300);
  }
}
