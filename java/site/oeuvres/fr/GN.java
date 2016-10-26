package site.oeuvres.fr;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import site.oeuvres.util.Term;

public class GN
{
  /** Counter */
  int n = 0;
  /** Tokenizer */
  Tokenizer toks;
  /** File writer */
  PrintWriter out;
  /** Size on the left */
  final int left = 10;
  /** Size on the right */
  final int right = 10;
  public GN(Path src, Path dest) throws IOException
  {
    toks = new Tokenizer( new String(Files.readAllBytes( src ), StandardCharsets.UTF_8) );
    out = new PrintWriter( dest.toString() );
  }
  public void parse( )
  {
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
    parse( toks );
    out.println( "    </table>" );
    out.println( "  </body>" );
    out.println( "</html>" );
    out.println();
    out.close();
  }
  public void parse( Tokenizer toks )
  {
    TokSlider win = new TokSlider(left, right);
    while ( toks.next() >= 0 ) {
      toks.tag( win.add() );
      if ( win.get( 0 ).cat() != Cat.SUB ) continue;
      int lpos = 0;
      boolean ladj = false;
      while (lpos > -left) {
        short cat =  win.get( lpos -1 ).cat();
        if ( cat == Cat.ADJ ) {
          lpos--;
          ladj = true;
          continue;
        }
        else if ( cat == Cat.VERBppass ) {
          lpos--;
          win.get( lpos ).cat( Cat.ADJ );
          ladj = true;
          continue;
        }
        else if ( ladj && cat == Cat.CONJcoord ) {
          lpos--;
          continue;
        }
        else if ( ladj && Cat.isAdv( cat ) ) {
          lpos--;
          continue;
        }
        else if ( Cat.isDet( cat ) ) {
          lpos--;
          break;
        }
        else if ( cat == Cat.NUM ) {
          lpos--;
          break;
        }
        else if ( cat == Cat.PREP ) {
          lpos--;
          break;
        }
        break;
      }
      int rpos = 0;
      boolean radj = false;
      // TODO
      // une chose sans cause , incompréhensible
      // un âge] à jamais révolu
      // Ou bien en dormant j’avais rejoint sans effort un âge à jamais révolu de … 
      // … [ma vie primitive, retrouvé] telle de mes terreurs enfantines comme celle que mon grand-oncle me tirât par mes boucles et qu’avait dissipée le jour — date pour moi d’une ère nouvelle — où on les avait coupées.
      while (rpos < right ) {
        final short cat =  win.get( rpos+1 ).cat();
        if ( cat == Cat.ADJ ) {
          radj = true;
          rpos++;
          continue;
        }
        else if ( cat == Cat.VERBppass ) {
          rpos++;
          win.get( rpos ).cat( Cat.ADJ );
          radj = true;
          continue;
        }
        // une femme belle mais redoutable
        else if ( radj && cat == Cat.CONJcoord ) {
          rpos++;
          continue;
        }
        // une chose vraiment obscure
        // si adverbe suivi d’un adjectif
        else if ( Cat.isAdv( cat ) && win.get( rpos+2 ).cat() == Cat.ADJ) {
          rpos++;
          continue;
        }
        // si déjà adj, puis virgule, voir après
        else if ( radj && win.get( rpos+1 ).orth().equals( "," ) ) {
          rpos++;
          continue;
        }
        // exclure la virgule finale
        if ( win.get( rpos ).orth().equals( "," ) ) rpos--;
        // exclure la conjonction finale
        if ( win.get( rpos ).cat() == Cat.CONJcoord ) rpos--;
        break;
      }
      if ( !ladj && !radj) continue;
      html( win, lpos, rpos );
    }
  }
  /**
   * Write the window
   */
  private void html( final TokSlider win, final int lpos, final int rpos) 
  {
    n++;
    out.print( "<tr>" );
    out.print( "<td class=\"num\">" );
    out.print( n );
    out.print( ".</td>" );
    out.print( "<td class=\"left\">" );
    for ( int i=-left; i < 0; i++) {
      if ( i == lpos ) out.print( "<b>" );
      short cat =  win.get( i ).cat();
      if ( cat == Cat.ADJ ) out.print( "<i>" );
      out.print( win.get( i ).graph() );
      if ( cat == Cat.ADJ ) out.print( "</i>" );
      if (i<-1) out.print( " " );
    }
    if ( lpos < 0) out.print( "</b>" );
    out.print( "</td>" );
    out.print( "<th>" );
    out.print( win.get( 0 ).graph() );
    out.print( "</th>" );
    out.print( "<td class=\"right\">" );
    if ( rpos > 0) out.print( "<b>" );
    for ( int i=1; i <= right; i++) {
      short cat =  win.get( i ).cat();
      if ( cat == Cat.ADJ ) out.print( "<i>" );
      out.print( win.get( i ).graph() );
      if ( cat == Cat.ADJ ) out.print( "</i>" );
      if ( i == rpos ) out.print( "</b>" );
      out.print( " " );
    }
    out.print( "</td>" );
    out.print( "</tr>" );
    out.println();
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
      String destname = srcname.substring( 0, srcname.length()-4 ) + ".html";
      Path srcpath = Paths.get( dir.toString(), srcname); 
      Path destpath = Paths.get( dir.toString(), destname); 
      System.out.println( srcpath+" > "+destpath );
      GN gn = new GN( srcpath, destpath );
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
