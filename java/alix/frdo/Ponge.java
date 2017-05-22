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
public class Ponge
{
  XMLInputFactory factory = XMLInputFactory.newInstance();
  final Writer out;

  /**
   * Constructeur initialisant différent 
   * @throws IOException 
   */
  public Ponge( Writer out ) throws IOException
  {
    this.out = out;
    out.write( "LETTRE\tDATE\tSIGNATURE\tCORRESPONDANT\tNOM\tTYPE\n" );
  }
  
  /**
   * Parcourir un fichier
   * @throws XMLStreamException 
   * @throws IOException 
   */
  public void parse( File src ) throws XMLStreamException, IOException
  {
    StringBuilder sb = new StringBuilder();
    XMLEventReader events = factory.createXMLEventReader( new FileReader( src ) );
    QName qn = new QName( "n" );
    QName qtype = new QName( "type" );
    QName qiname = new QName( "indexName" );
    QName qid = new QName( "http://www.w3.org/XML/1998/namespace", "id" );
    String id="";
    String date="";
    String signed="";
    String corr="";
    boolean note = false;
    boolean p = false;
    boolean letter = false;
    
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
          p = true;
        }
        else if ( name.equals( "index" ) ) {
          String type = startElement.getAttributeByName( qiname ).getValue();
          String n = startElement.getAttributeByName( qn ).getValue();
          if ( type.equals( "date" ) ) date = n;
          else if ( type.equals( "sender" ) ) {
            signed = n;
            if ( !"Ponge, Francis".equals( n ) ) corr = n;
          }
          else if ( type.equals( "addressee" ) ) {
            if ( !"Ponge, Francis".equals( n ) ) corr = n;
          }
        }
        else if ( name.equals( "div" ) ) {
          Attribute att = startElement.getAttributeByName( qtype );
          if ( att == null ) continue;
          String type = att.getValue();
          if ( !type.equals( "letter" ) ) continue;
          letter = true;
          id = startElement.getAttributeByName( qid ).getValue();
          sb.setLength( 0 );
        }
      }
      // TEXT 
      else if ( e.isCharacters() ) {
        if ( note ) continue;
        if ( p && letter ) sb.append( e.asCharacters().getData() );
      }
      // </EL>
      else if ( e.isEndElement() ) {
        EndElement endElement = e.asEndElement();
        String name = endElement.getName().getLocalPart();
        if ( name.equals( "note" ) ) {
          note = false;
        }
        else if ( name.equals( "p" ) ) {
          if ( p && !note ) sb.append( "\n" );
          p = false;
        }
        // record 
        else if ( name.equals( "div" ) ) {
          if ( !letter ) continue;
          // System.out.println( id+"\t"+date+"\t"+signed+"\t"+corr+"\t"+sb+"\n" );
          if ( sb.length() < 10 ) continue;
          Tokenizer toks = new Tokenizer( sb.toString() );
          Occ occ = new Occ();
          while ( toks.word( occ ) ) {
            if ( !occ.tag().isName() ) continue;
            out.write( id+"\t"+date+"\t"+signed+"\t"+corr+"\t"+occ.orth()+"\t"+occ.tag()+"\n" );
          }
          letter = false;
        }
        
      }
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
    // boucler sur les fichiers Ponge
    String dir = "../ponge/corr/";
    String dest = "../ponge/corr/names.csv";
    Writer out = new BufferedWriter( new OutputStreamWriter( new FileOutputStream( dest ), "UTF-8") );
    Ponge ponge = new Ponge( out );
    for ( final File src : new File( dir ).listFiles() ) {
      if ( src.isDirectory() ) continue;
      if ( src.getName().startsWith( "." ) ) continue;
      if ( !src.getName().endsWith( ".xml" ) ) continue;
      System.out.println( src );
      ponge.parse( src );
    }
    out.close();
  }

}
