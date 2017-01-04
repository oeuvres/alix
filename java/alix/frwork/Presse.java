package alix.frwork;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import alix.fr.Lexik;
import alix.fr.Occ;
import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.util.TermDic;

/**
 * http://data.theeuropeanlibrary.org/download/newspapers-by-country/FRA/
 * @author user
 *
 */
public class Presse
{
  TermDic dic;
  Tokenizer toks;
  int filecount;
  public Presse()
  {
    dic = new TermDic();
  }
  public void walk( File dir) throws IOException
  {
    for (final File src : dir.listFiles()) {
      if ( src.getName().startsWith( "." )) continue;
      if ( src.isDirectory() ) {
        walk( src );
        continue;
      }
      // if ( src.getName().startsWith( "name" )) continue;
      if ( !src.getName().endsWith( ".fulltext.json" ) ) continue;
      
      String text = new String(Files.readAllBytes( Paths.get( src.toString() ) ), StandardCharsets.UTF_8);
      int start = text.indexOf( "\"contentAsText\"" );
      if ( start < 0 ) continue;
      
      System.out.print( "." );
      start = start + 15;
      toks = new Tokenizer( text ).pointer( start );
      int end = text.indexOf( "\"rights\"" );
      if ( end > start) toks.end( end );
      TermDic dic = this.dic;
      Occ occ;
      while( ( occ=toks.word()) != null) {
        if ( false );
        else if ( occ.tag().equals( Tag.DETnum ) );
        // if ( occ.tag.isAdj() || occ.tag.isVerb() ) dic.add( occ.lem );
        /*
        else if ( occ.orth.isEmpty() ) {
          System.out.print( src.getName() );
          System.out.println( occ );
          dic.add( occ.graph ); // ? TODO
        }
        */
        else dic.add( occ.orth() );
      }
    }

  }
  /**
   * Test the Class
   * @param args
   * @throws IOException 
   */
  public static void main(String args[]) throws IOException 
  {
    String root = "/Local/presse";
    // String journal = "le_temps";
    // String journal = "le_petit_journal";
    // String journal = "l_humanite";
    // String journal = "le_figaro";
    // String journal = "la_croix";
    String journal = "l_action_francaise";
    String year = "1928";
    Presse walker = new Presse();
    walker.walk( new File( new File (root, journal), year) );
    System.out.println();
    walker.dic.csv( new PrintWriter(System.out), 200, Lexik.STOP);
  }


}
