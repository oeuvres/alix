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
    out.write( "FICHIER\tLETTRE\tDATE\tSOURCE\tDESTINATION\tNOM\tTYPE\n" );
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
    // liste de lettres
    HashMap<String, Letter> corr = new HashMap<String, Letter>();
    
    while( events.hasNext() ) {
      XMLEvent e = events.nextEvent();
      // <EL
      if ( e.isStartElement() ) {
        StartElement startElement = e.asStartElement();
        String name = startElement.getName().getLocalPart();
        // récupérer les métadonnées de lettres
        if ( name.equals( "correspDesc" ) ) {
          letter = new Letter();
          String corresp = startElement.getAttributeByName( qcorresp ).getValue();
          if ( corresp.charAt( 0 ) == '#' ) letter.id = corresp.substring( 1 );
          else letter.id = corresp.substring( 1 );
        }
        else if ( name.equals( "correspAction" ) ) {
          String type = startElement.getAttributeByName( qtype ).getValue();
          if ( "sending".equals( type ) ) state = SENDING;
          else if ( "receiving".equals( type ) ) state = RECEIVING;
          else state = UNKNOWN;
        }
        else if ( name.equals( "persName" ) ) {
          String key = startElement.getAttributeByName( qkey ).getValue();
          if ( state == SENDING ) letter.sender = key;
          else if ( state == RECEIVING ) letter.adressee = key;
        }
        else if ( name.equals( "date" ) ) {
          if ( state == SENDING || state == RECEIVING ) {
            if (  startElement.getAttributeByName( qwhen ) == null ) continue;
            String date = startElement.getAttributeByName( qwhen ).getValue();
            letter.date = date;
          }
        }
        else if ( name.equals( "note" ) ) {
          note = true;
        }
        else if ( name.equals( "p" ) ) {
          if ( div && !note ) state = P;
        }
        // début de lettre, charger les métas, remettre le texte à 0
        else if ( name.equals( "div" ) ) {
          Attribute att = startElement.getAttributeByName( qtype );
          if ( att == null ) continue;
          String type = att.getValue();
          if ( !type.equals( "letter" ) ) continue;
          div = true ;
          String id = startElement.getAttributeByName( qid ).getValue();
          letter = corr.get( id );
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
        
        if ( name.equals( "correspDesc" ) ) {
          corr.put( letter.id, letter );
        }
        else if ( name.equals( "teiHeader" ) ) {
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
          Tokenizer toks = new Tokenizer( sb.toString() );
          Occ occ = new Occ();
          try {
            while ( toks.word( occ ) ) {
              if ( !occ.tag().isName() ) continue;
              out.write( filename+"\t"+letter.id+"\t"+letter.date+"\t"+letter.sender+"\t"+letter.adressee+"\t"+occ.orth()+"\t"+occ.tag()+"\n" );
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
    String adressee;
    @Override
    public String toString() {
      return id+" "+date+" de "+sender+" à "+adressee;
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
    String dest = "../paulhan/names.csv";
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
