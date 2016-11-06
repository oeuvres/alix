package alix.exos;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;

import alix.util.TermDic;
import alix.fr.Occ;
import alix.fr.OccSlider;
import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.util.Term;

public class NAME
{
  /** Un hack pour la collecte de tous les termes */
  static TermDic index = new TermDic();
  /** La liste des savants */
  static HashSet<String> SAVANTS = new HashSet<String>();
  static {
    String l;
    try {
      BufferedReader buf = new BufferedReader( 
         new InputStreamReader( new FileInputStream("savants.csv"), "UTF-8")
      );
      while ((l = buf.readLine()) != null) SAVANTS.add( l.trim() );
      buf.close();
    } 
    catch (IOException e) {
      e.printStackTrace();
    }
  }
  /** Dernière position en caractères */
  int charpos;
  /** Dernière position en mots */
  /** Counter */
  int n = 0;
  /** Tokenizer */
  Tokenizer toks;
  /** File path */
  String htmlpath;
  /** File writer */
  PrintWriter htmlWriter;
  /** File writer */
  String csvpath;
  /** Size on the left */
  final int left = 10;
  /** Size on the right */
  final int right = 10;
  public NAME(Path src, final Path destName ) throws IOException
  {
    toks = new Tokenizer( new String(Files.readAllBytes( src ), StandardCharsets.UTF_8) );
    htmlpath = destName.toString()+".html";
    htmlWriter = new PrintWriter( htmlpath );
    csvpath = destName.toString()+".txt";
  }
  public void htmlHead( PrintWriter out ) {
    out.println( "<!doctype html>" );
    out.println( "<html>" );
    out.println( "  <head>" );
    out.println( "    <meta charset=\"utf-8\">" );
    out.println( "    <style>" );
    out.println( "table.conc { font-family: sans-serif; color: #666; border-spacing : 2px; background-color: #EEEEEE; }" );
    out.println( ".conc td, .conc th { padding: 0; }" );
    out.println( "td.num { font-size: 70%; }" );
    out.println( "td.left { text-align:right; }" );
    out.println( ".conc th { color: #000; background: #FFFFFF}" );
    out.println( ".conc i { font-style: normal; color: #000; }" );
    out.println( ".conc th.NAMEplace { background: rgba(255, 0, 0, 0.2) ; }" );
    out.println( ".conc i.NAMEplace { color:  rgba(255, 0, 0, 0.6); }" );
    out.println( ".conc th.NAMEpers, .conc th.NAMEpersm, .conc th.NAMEpersf { background: rgba(0, 0, 255, 0.2) ; }" );
    out.println( ".conc i.NAMEpers, .conc i.NAMEpersm, .conc i.NAMEpersf { color: rgba(0, 0, 255, 0.6) ; }" );
    out.println( "    </style>" );
    out.println( "  </head>" );
    out.println( "  <body>" );
    out.println( "    <table class=\"conc\">" );
    out.println( "      <tr>");
    out.println( "       <th>Char</th>");
    out.println( "       <th>Diff</th>");
    out.println( "      </tr>");
  }
  public void htmlFoot( PrintWriter out )
  {
    out.println( "    </table>" );
    out.println( "  </body>" );
    out.println( "</html>" );
    out.println();
    out.close();
  }
  public void parse( ) throws IOException
  {
    htmlHead( htmlWriter );
    TermDic dic = parse( toks );
    htmlFoot( htmlWriter );
    System.out.println( dic.size() );
    if ( dic.size() == 0 ) {
      new File( htmlpath ).delete();
      return;
    }
    PrintWriter csvWriter = new PrintWriter( csvpath );
    csvWriter.write( "FORM\toccs\tppm\n" );
    dic.csv( csvWriter );
    csvWriter.close();
  }
  public TermDic parse( Tokenizer toks )
  {
    TermDic dic = new TermDic();
    OccSlider win = new OccSlider(left, right);
    Occ occ;
    while ( toks.word( win.add() ) ) {
      occ = win.get( 0 );
      if ( occ.tag.isName() && SAVANTS.contains( occ.orth ) ) {
        dic.add( occ.orth );
        index.add( occ.orth );
        html( win, 0, 0 );
      }
      else {
        if ( occ.tag.equals( Tag.SUB ) ) {
          index.add(occ.orth );
          continue;
        }
        else index.inc();
        continue;
      }
    }
    return dic;
  }
  /**
   * Write the window
   */
  private void html( final OccSlider win, final int lpos, final int rpos) 
  {
    Tag tag;
    n++;
    htmlWriter.print( "<tr>" );
    htmlWriter.print( "<td class=\"num\">" );
    htmlWriter.print( win.get( 0 ).start );
    htmlWriter.print( ".</td>" );
    htmlWriter.print( "<td class=\"num\">" );
    htmlWriter.print( win.get( 0 ).start - charpos );
    charpos = win.get( 0 ).start;
    htmlWriter.print( ".</td>" );
    htmlWriter.print( "<td class=\"left\">" );
    for ( int i=-left; i < 0; i++) {
      tag =  win.get( i ).tag;
      if ( tag.isName() ) htmlWriter.print( "<i class=\""+tag.label()+"\">" );
      htmlWriter.print( win.get( i ).graph );
      if ( tag.isName() ) htmlWriter.print( "</i>" );
      if (i<-1) htmlWriter.print( " " );
    }
    htmlWriter.print( "</td>" );
    htmlWriter.print( "<th class=\""+win.get( 0 ).tag.label()+"\">" );
    htmlWriter.print( win.get( 0 ).graph );
    htmlWriter.print( "</th>" );
    htmlWriter.print( "<td class=\"right\">" );
    for ( int i=1; i <= right; i++) {
      tag =  win.get( i ).tag;
      if ( tag.isName() ) htmlWriter.print( "<i class=\""+tag.label()+"\">" );
      htmlWriter.print( win.get( i ).graph );
      if ( tag.isName() ) htmlWriter.print( "</i>" );
      htmlWriter.print( " " );
    }
    htmlWriter.print( "</td>" );
    htmlWriter.print( "</tr>" );
    htmlWriter.println();
  }
  /**
   * Test the Class
   * @param args
   * @throws IOException 
   */
  public static void main(String args[]) throws IOException 
  {
    File root = new File("../critique/");
    for (String dirname:root.list() ) {
      if ( dirname.startsWith( "." )) continue;
      File dir = new File( root, dirname);
      if ( !dir.isDirectory() ) continue; 
      for (String srcname:dir.list() ) {
        if ( !srcname.endsWith( ".xml" )) continue;
        String destname = srcname.substring( 0, srcname.length()-4 );
        Path srcpath = Paths.get( dir.toString(), srcname); 
        Path destpath = Paths.get( "noms", destname); 
        System.out.println( srcpath+" > "+destpath );
        NAME parser = new NAME( srcpath, destpath );
        parser.parse();
      }
    }
    index.csv( Paths.get("noms", "_noms.txt") );
  }

}
