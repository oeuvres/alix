package alix.frdo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashSet;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import alix.fr.Tokenizer;
import alix.util.Occ;

/**
 * Extraction de noms dans une correspondance au format TEI
 * 
 * @author user
 *
 */
public class Ponge
{
  XMLInputFactory factory = XMLInputFactory.newInstance();
  final Writer out;

  /**
   * Constructeur initialisant différent
   * 
   * @throws IOException
   */
  public Ponge(Writer out) throws IOException {
    this.out = out;
    out.write("lettre\tdate\tdest\tSource\tTarget\tclass\n");
  }

  private class Letter
  {
    String id;
    String date;
    String sender;
    String addressee;

    @Override
    public String toString()
    {
      return id + " " + date + " de " + sender + " à " + addressee;
    }
  }

  /**
   * Parcourir un fichier
   * 
   * @throws XMLStreamException
   * @throws IOException
   */
  public void parse(File src) throws XMLStreamException, IOException
  {
    StringBuilder sb = new StringBuilder();
    XMLEventReader events = factory.createXMLEventReader(new FileReader(src));
    QName qn = new QName("n");
    QName qtype = new QName("type");
    QName qiname = new QName("indexName");
    QName qid = new QName("http://www.w3.org/XML/1998/namespace", "id");
    boolean note = false;
    boolean p = false;
    boolean divlet = false;
    Letter letter = null;

    while (events.hasNext()) {
      XMLEvent e = events.nextEvent();
      // <EL
      if (e.isStartElement()) {
        StartElement startElement = e.asStartElement();
        String name = startElement.getName().getLocalPart();
        if (name.equals("div")) {
          Attribute att = startElement.getAttributeByName(qtype);
          if (att == null)
            continue;
          String type = att.getValue();
          if (!type.equals("letter"))
            continue;
          letter = new Letter();
          letter.id = startElement.getAttributeByName(qid).getValue();
          sb.setLength(0);
          divlet = true;
        }
        else if (name.equals("index")) {
          String type = startElement.getAttributeByName(qiname).getValue();
          String n = startElement.getAttributeByName(qn).getValue();
          if (type.equals("date")) {
            if (letter == null) {
              System.out.println("date ? " + n);
            }
            else {
              letter.date = n;
            }
          }
          else if (type.equals("sender")) {
            letter.sender = n;
          }
          else if (type.equals("addressee")) {
            letter.addressee = n;
          }
        }
        // évite head, selute, dateline, signed…
        else if (name.equals("p")) {
          p = true;
        }
        else if (name.equals("note")) {
          note = true;
        }
      }
      // TEXT
      else if (e.isCharacters()) {
        if (note)
          continue;
        if (p && divlet)
          sb.append(e.asCharacters().getData());
      }
      // </EL>
      else if (e.isEndElement()) {
        EndElement endElement = e.asEndElement();
        String name = endElement.getName().getLocalPart();
        if (name.equals("note")) {
          note = false;
        }
        else if (name.equals("p")) {
          if (p && !note)
            sb.append("\n");
          p = false;
        }
        // record
        else if (name.equals("div")) {
          if (!divlet)
            continue;
          // System.out.println( id+"\t"+date+"\t"+signed+"\t"+corr+"\t"+sb+"\n" );
          if (sb.length() < 10)
            continue;
          HashSet<String> done = new HashSet<String>();
          Tokenizer toks = new Tokenizer(sb.toString());
          Occ occ = new Occ();
          while (toks.word(occ)) {
            if (!occ.tag().isName())
              continue;
            if (occ.orth().toString().trim().isEmpty()) {
              System.out.println(occ);
              continue;
            }
            if (done.contains(occ.orth())) {
              continue;
            }
            String date = "";
            if (letter.date == null)
              System.out.println("Date = null, " + letter.id);
            else if (letter.date.length() >= 4)
              date = letter.date.substring(0, 4);
            // if ( !"Ponge, Francis".equals( n ) ) corr = n;
            out.write(letter.id + "\t" + date + "\t" + letter.addressee + "\t" + letter.sender + "\t" + occ.orth()
                + "\t" + occ.tag() + "\n");
            done.add(occ.orth().toString());
          }
          divlet = false;
        }

      }
    }
  }

  /**
   * Test the Class
   * 
   * @param args
   * @throws IOException
   * @throws XMLStreamException
   */
  public static void main(String args[]) throws IOException, XMLStreamException
  {
    // boucler sur les fichiers Ponge
    String dir = "../ponge/corr/";
    String dest = "../ponge/ponge_names.tsv";
    Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(dest), "UTF-8"));
    Ponge ponge = new Ponge(out);
    for (final File src : new File(dir).listFiles()) {
      if (src.isDirectory())
        continue;
      if (src.getName().startsWith("."))
        continue;
      if (!src.getName().endsWith(".xml"))
        continue;
      System.out.println(src);
      ponge.parse(src);
    }
    out.flush();
    out.close();
  }

}
