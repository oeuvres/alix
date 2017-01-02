package alix.frwork;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;

import alix.fr.Occ;
import alix.fr.Tokenizer;
import alix.util.TermDic;

public class IndexNominum
{
  TermDic dic = new TermDic();
  /**
   * Traverser le texte, ramasser les infos, cracher à la fin
   * @param code
   * @param text
   * @throws IOException
   */
  public void parse( String xml ) throws IOException
  {
    Tokenizer toks = new Tokenizer( xml );
    // est-ce qu’on a besoin d’une fenêtre glissante ?
    Occ occ = new Occ();
    while ( toks.word( occ ) ) {
      if ( !occ.tag.NAME() ) {
        dic.inc();
        continue;
      }
      dic.inc( occ.graph );
    }
  }

  public static void main(String args[]) throws IOException 
  {
    // TODO boucler sur un dossier
    String dir="../mythographies/";
    String dest;
    IndexNominum parser = new IndexNominum();
    for (final File src : new File( dir ).listFiles()) {
      if ( src.isDirectory() ) continue;
      if ( src.getName().startsWith( "." )) continue;
      if ( !src.getName().endsWith( ".xml" ) ) continue;
      // if ( src.getName().startsWith( "name" )) continue;
      // dest = src.getParent()+"/"+"name_"+src.getName();
      parser.parse(
          new String(Files.readAllBytes( Paths.get( src.toString() ) ), StandardCharsets.UTF_8) 
      );
    }
    System.out.println( parser.dic.csv() );
  }

}
