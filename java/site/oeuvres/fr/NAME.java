package site.oeuvres.fr;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import site.oeuvres.util.TermDic;
import site.oeuvres.util.Term;

public class NAME
{
  /** Counter */
  int n = 0;
  /** Tokenizer */
  Tokenizer toks;
  /** File writer */
  PrintWriter htmlWriter;
  /** File writer */
  PrintWriter csvWriter;
  /** Size on the left */
  final int left = 10;
  /** Size on the right */
  final int right = 10;
  public NAME(Path src, final Path destName ) throws IOException
  {
    toks = new Tokenizer( new String(Files.readAllBytes( src ), StandardCharsets.UTF_8) );
    htmlWriter = new PrintWriter( destName.toString()+".html" );
    csvWriter = new PrintWriter( destName.toString()+".csv" );
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
    out.println( ".left b { padding: 0 1ex; margin-left: 1ex; }" );
    out.println( ".right b { padding: 0 1ex; margin-right: 1ex; }" );
    out.println( ".conc b, .conc th {  font-weight: normal; color: #000; background-color: #FFFFFF; }" );
    out.println( ".conc i { font-style: normal; color: red;  }" );
    out.println( "    </style>" );
    out.println( "  </head>" );
    out.println( "  <body>" );
    out.println( "    <table class=\"conc\">" );
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
    /*
    csvWriter.write( "ADJECTIF\tANTE\tPOST\tANTE+POST\t% ANTE\n" );
    dic.csv( csvWriter );
    csvWriter.close();
    */
  }
  public TermDic parse( Tokenizer toks )
  {
    TermDic dic = new TermDic();
    OccSlider win = new OccSlider(left, right);
    while ( toks.word( win.add() ) ) {
      if ( win.get( 0 ).cat() != Cat.NAME ) continue;
      html( win, 0, 0 );
    }
    return dic;
  }
  /**
   * Write the window
   */
  private void html( final OccSlider win, final int lpos, final int rpos) 
  {
    n++;
    htmlWriter.print( "<tr>" );
    htmlWriter.print( "<td class=\"num\">" );
    htmlWriter.print( n );
    htmlWriter.print( ".</td>" );
    htmlWriter.print( "<td class=\"left\">" );
    for ( int i=-left; i < 0; i++) {
      if ( i == lpos ) htmlWriter.print( "<b>" );
      short cat =  win.get( i ).cat();
      if ( cat == Cat.NAME ) htmlWriter.print( "<i>" );
      htmlWriter.print( win.get( i ).graph() );
      if ( cat == Cat.NAME ) htmlWriter.print( "</i>" );
      if (i<-1) htmlWriter.print( " " );
    }
    if ( lpos < 0) htmlWriter.print( "</b>" );
    htmlWriter.print( "</td>" );
    htmlWriter.print( "<th>" );
    htmlWriter.print( win.get( 0 ).graph() );
    htmlWriter.print( "</th>" );
    htmlWriter.print( "<td class=\"right\">" );
    if ( rpos > 0) htmlWriter.print( "<b>" );
    for ( int i=1; i <= right; i++) {
      short cat =  win.get( i ).cat();
      if ( cat == Cat.NAME ) htmlWriter.print( "<i>" );
      htmlWriter.print( win.get( i ).graph() );
      if ( cat == Cat.NAME ) htmlWriter.print( "</i>" );
      if ( i == rpos ) htmlWriter.print( "</b>" );
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
    File dir = new File("../proust");
    for (String srcname:dir.list() ) {
      if ( srcname.startsWith( "." )) continue;
      if ( !srcname.endsWith( ".xml" )) continue;
      String destname = srcname.substring( 0, srcname.length()-4 );
      Path srcpath = Paths.get( dir.toString(), srcname); 
      Path destpath = Paths.get( dir.toString(), "NAME_"+destname); 
      System.out.println( srcpath+" > "+destpath );
      NAME gn = new NAME( srcpath, destpath );
      gn.parse();
    }
  }

  /**
   * Bugs
   * — un ton modeste et vrai
   * — à peu près
   * — à peine
   * — rendre compte
   * — un tour [un peu particulier]
   * — par exemple
   * — à toute vitesse
   * — au premier instant
   * — son valet de chambre émerveillé
   */
}
