package alix.frdo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.util.Occ;
/**
 * Extraction de noms dans une correspondance au format TEI
 * @author user
 *
 */
public class Paulhan
{
  /** XML parser */
  XMLInputFactory factory = XMLInputFactory.newInstance();
  /** Fichier de sortie pour écrire les tables */
  final Writer out;
  static final int UNKNOWN = 0;
  static final int SENDING = 1;
  static final int RECEIVING = 2;
  static final int NOTE = 3;
  static final int LETTER = 4;
  static final int P = 5;
  

  /**
   * Constructeur 
   * @throws IOException 
   */
  public Paulhan( Writer out ) throws IOException
  {
    this.out = out;
    out.write( "fichier\tlettre\tdate\tSource\tdest\tTarget\tclass\n" );
  }
  
  /**
   * Parcourir un fichier
   * @throws XMLStreamException 
   * @throws IOException 
   */
  public void parse( File src ) throws XMLStreamException, IOException
  {
    StringBuilder sb = new StringBuilder();
    String filename = src.getName();
    filename = filename.substring( 0, filename.lastIndexOf( '.' ) );
    
    XMLEventReader events = factory.createXMLEventReader( new FileReader( src ) );
    // noms d’attributs
    QName qn = new QName( "n" );
    QName qtype = new QName( "type" );
    QName qiname = new QName( "indexName" );
    QName qcorresp = new QName( "corresp" );
    QName qkey = new QName( "key" );
    QName qwhen = new QName( "when" );
    QName qid = new QName( "http://www.w3.org/XML/1998/namespace", "id" );
    // états
    int state = UNKNOWN;
    boolean note = false;
    boolean div = false;
    // lettre en cours, soit dans le bloc de meta, soit dans le corps de texte
    Letter letter = null;
    
    while( events.hasNext() ) {
      XMLEvent e = events.nextEvent();
      // <EL
      if ( e.isStartElement() ) {
        StartElement startElement = e.asStartElement();
        String name = startElement.getName().getLocalPart();
        if ( name.equals( "note" ) ) {
          note = true;
        }
        else if ( name.equals( "p" ) ) {
          if ( div && !note ) state = P;
        }
        else if ( name.equals( "index" ) ) {
          Attribute att = startElement.getAttributeByName( qiname );
          if ( att == null ) continue;
          String type = att.getValue();
          String n = startElement.getAttributeByName( qn ).getValue();
          if ( type.equals( "date" ) ) letter.date = n;
          else if ( type.equals( "sender" ) ) {
            letter.sender = n;
          }
          else if ( type.equals( "addressee" ) ) {
            letter.addressee = n;
          }
        }
        // début de lettre, charger les métas, remettre le texte à 0
        else if ( name.equals( "div" ) ) {
          Attribute att = startElement.getAttributeByName( qtype );
          if ( att == null ) continue;
          String type = att.getValue();
          if ( !type.equals( "letter" ) ) continue;
          div = true ;
          letter = new Letter();
          letter.id = startElement.getAttributeByName( qid ).getValue();
          sb.setLength( 0 );
        }
      }
      // TEXT 
      else if ( e.isCharacters() ) {
        if ( state == P && !note ) sb.append( e.asCharacters().getData() );
      }
      // </EL>
      else if ( e.isEndElement() ) {
        EndElement endElement = e.asEndElement();
        String name = endElement.getName().getLocalPart();
        
        if ( name.equals( "teiHeader" ) ) {
        }
        else if ( name.equals( "note" ) ) {
          note = false;
        }
        else if ( name.equals( "p" ) ) {
          if ( state == P ) sb.append( " ¶\n" );
          state = UNKNOWN;
        }
        // record 
        else if ( name.equals( "div" ) ) {
          if ( !div ) continue;
          // out.write( "\n\n——"+sb.toString()+"——\n\n" );
          if ( sb.length() < 10 ) continue;
          HashSet<String> done = new HashSet<String>();
          Tokenizer toks = new Tokenizer( sb.toString() );
          Occ occ = new Occ();
          try {
            while ( toks.word( occ ) ) {
              if ( !occ.tag().isName() ) continue;
              if ( occ.orth().toString().trim().isEmpty() ) {
                System.out.println( occ );
                continue;
              }
              if ( done.contains( occ.orth() )) {
                continue;
              }
              if ( letter.date.trim().isEmpty() ) {
                System.out.println( letter );
              }
              String date = "";
              if ( letter.date.length() >= 4 ) date = letter.date.substring( 0, 4 );
              out.write( filename+"\t"+letter.id+"\t"+date+"\t"+letter.sender+"\t"+letter.addressee+"\t"+occ.orth()+"\t"+occ.tag()+"\n" );
              done.add( occ.orth().toString() );
            }
          }
          finally {
            out.flush();
          }
          div = false;
        }
        
      }
    }
  }
  
  private class Letter
  {
    String id;
    String date;
    String sender;
    String addressee;
    @Override
    public String toString() {
      return id+" "+date+" de "+sender+" à "+addressee;
    }
  }
  
  /**
   * Test the Class
   * @param args
   * @throws IOException 
   * @throws XMLStreamException 
   */
  public static void main( String args[] ) throws IOException, XMLStreamException 
  {
    // boucler sur les fichiers Paulhan
    String dir = "../paulhan/xml/";
    String dest = "../paulhan/paulhan_names.tsv";
    Writer out = new BufferedWriter( new OutputStreamWriter( new FileOutputStream( dest ), "UTF-8") );
    Paulhan parser = new Paulhan( out );
    for ( final File src : new File( dir ).listFiles() ) {
      if ( src.isDirectory() ) continue;
      if ( src.getName().startsWith( "." ) ) continue;
      if ( src.getName().startsWith( "_" ) ) continue;
      if ( !src.getName().endsWith( ".xml" ) ) continue;
      System.out.println( src );
      parser.parse( src );
    }
    out.close();
  }

}
