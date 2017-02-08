package alix.viz;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidParameterException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import alix.fr.Lexik;
import alix.fr.Occ;
import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.fr.Lexentry;
import alix.util.TermDic;
import alix.util.TermDic.Terminfos;

/**
 * Word Cloud in HTML
 * from Pierre Lindenbaum
 * https://github.com/lindenb/jsandbox/blob/master/src/sandbox/MyWordle.java
 * Rewrited with no deps
 * 
 * @author glorieux-f
 *
 */

public class Cloud
{
  /** List of words to render */
  private List<Word> words = new ArrayList<Word>();
  /** Default font for size calculations */
  private String font = "Dialog";
  /** minimum font size */
  private int fontmin = 12;
  /** maximum font size */
  private int fontmax = 80;
  /** Optional, an url prefix for links */
  private String href;
  /** Default font Color in PNG export */
  private Color fill = Color.GRAY;
  /** Default font strokes in PNG export */
  private Color stroke = null;
  /** A locale for decimal formats */
  static DecimalFormatSymbols dfus = DecimalFormatSymbols.getInstance(Locale.US);
  /** Decimal format for CSS values */
  static DecimalFormat dfdec1 = new DecimalFormat("#.0", dfus);
  /** Radius steps to go outside */
  private double dRadius = 10.0;
  /** spirograph angle for tests */
  private int dDeg = 195;

  
  private Random rand = new Random();
  private Rectangle2D imageSize = null;
  private boolean useArea = false;

  private Integer outputWidth = null;
  private boolean allowRotate = false;


  public class CSS 
  {
    
  }
  /**
   * A word in the cloud, with all its fields
   * @author user
   *
   */
  static public class Word
  {
    /** Required, a word to display */
    private final String text;
    /** Required, a relative weight */
    private final int weight;
    /** Optional, a wordclass for endering properties of this word */
    private Wordclass wclass;
    /** The full path of the TTF word */
    private Shape shape;
    /** A bounding rectangle, not optimal https://www.jasondavies.com/wordcloud/about/ */
    private Rectangle2D bounds;
    /** Fontsize calculated, between fontmin and fontmax */
    private int fontsize;
    

    public Word(String text, int weight, Wordclass wclass)
    {
      this.text = text;
      this.weight = weight;
      if (this.weight <= 0)
        throw new IllegalArgumentException( "bad weight " + weight );
      this.wclass = wclass;
    }

  }
  /**
   * A class for a word with colors font etc
   * @author user
   *
   */
  static public class Wordclass
  {
    private final String name;
    private final String font;
    private final Color fill;
    private final Color stroke;
    public Wordclass ( String name, String font, Color fill, Color stroke ) {
      this.name = name;
      this.font = font;
      this.fill = fill;
      this.stroke = stroke;
    }
  }


  public Cloud()
  {
  }

  /**
   * Add a word to the cloud
   * @param word
   */
  public void add( Word word )
  {
    this.words.add( word );
  }

  
  /**
   * Here is the heart
   * @throws Exception
   */
  public void doLayout() throws InvalidParameterException
  {
    if (this.words.isEmpty())  throw new InvalidParameterException( "No words to display" );
    // ? size ?
    this.imageSize = new Rectangle2D.Double( 0, 0, 0, 0 );
    
    // get minimum and maximum weight
    Word first = this.words.get( 0 );
    double high = -Double.MAX_VALUE;
    double low = Double.MAX_VALUE;
    for (Word w : this.words) {
      high = Math.max( high, w.weight );
      low = Math.min( low, w.weight );
    }

    // create small image (?)
    BufferedImage img = new BufferedImage( 1, 1, BufferedImage.TYPE_INT_RGB );
    // get graphics from this image
    Graphics2D g = Graphics2D.class.cast( img.getGraphics() );
    FontRenderContext frc = g.getFontRenderContext();

    // loop on words to create the shapes and bounding rectangle. Is it the right place for rotations ?
    for ( Word w: this.words ) {
      String font = this.font;
      if ( w.wclass != null && w.wclass.font != null ) font = w.wclass.font;
      int fontsize = (int) (((w.weight - low) / (high - low)) * (this.fontmax - this.fontmin)) + this.fontmin;
      w.fontsize = fontsize;
      // TODO Bold ? Italic ? padding with spaces ?
      TextLayout textLayout = new TextLayout( w.text, new Font( font, 0, fontsize ), frc );
      Shape shape = textLayout.getOutline( null );
      /*
      if (this.allowRotate && this.rand.nextBoolean()) {
        AffineTransform rotate = AffineTransform.getRotateInstance( Math.PI / 2.0 );
        shape = rotate.createTransformedShape( shape );
      }
      */
      Rectangle2D bounds = shape.getBounds2D();
      AffineTransform centerTr = AffineTransform.getTranslateInstance( -bounds.getCenterX(), -bounds.getCenterY() );
      w.shape = centerTr.createTransformedShape( shape );
      w.bounds = w.shape.getBounds2D();
    }
    g.dispose();

    // first point
    Point2D.Double center = new Point2D.Double( 0, 0 );

    // loop on words
    for (int i = 1; i < this.words.size(); ++i) {
      Word current = this.words.get( i );

      // find a center for this word
      center.x = 0;
      center.y = 0;
      double totalWeight = 0.0;
      for (int prev = 0; prev < i; ++prev) {
        Word wPrev = this.words.get( prev );
        center.x += (wPrev.bounds.getCenterX()) * wPrev.weight;
        center.y += (wPrev.bounds.getCenterY()) * wPrev.weight;
        totalWeight += wPrev.weight;
      }
      center.x /= (totalWeight);
      center.y /= (totalWeight);

      // TODO
      Shape shaveH = current.shape;
      Rectangle2D bounds = current.bounds;

      boolean done = false;
      double radx = 0.7 * Math.min( first.bounds.getWidth(), first.bounds.getHeight() );
      double rady = 0.4 * Math.min( first.bounds.getWidth(), first.bounds.getHeight() );

      while (!done) {
        // System.err.println( "" + i + "/" + words.size() + " rad:" + radius );
        int startDeg = rand.nextInt( 360 );
        // loop over spiral
        int prev_x = -1;
        int prev_y = -1;
        for (int deg = startDeg; deg < startDeg + 360; deg += dDeg) {
          double rad = (deg / Math.PI) * 180.0;
          int cx = (int) (center.x + radx * Math.cos( rad ));
          int cy = (int) (center.y + rady * Math.sin( rad ));
          if (prev_x == cx && prev_y == cy)
            continue;
          prev_x = cx;
          prev_y = cy;

          AffineTransform moveTo = AffineTransform.getTranslateInstance( cx, cy );
          Shape candidate = moveTo.createTransformedShape( current.shape );
          Area area1 = null;
          Rectangle2D bound1 = null;
          if (useArea) {
            area1 = new Area( candidate );
          }
          else {
            bound1 = new Rectangle2D.Double( current.bounds.getX() + cx, current.bounds.getY() + cy,
                current.bounds.getWidth(), current.bounds.getHeight() );
          }
          // any collision ?
          int prev = 0;
          for (prev = 0; prev < i; ++prev) {
            if (useArea) {
              Area area2 = new Area( this.words.get( prev ).shape );
              area2.intersect( area1 );
              if (!area2.isEmpty())
                break;
            }
            else {
              if (bound1.intersects( this.words.get( prev ).bounds )) {
                break;
              }
            }
          }
          // no collision: we're done
          if (prev == i) {
            current.shape = candidate;
            current.bounds = candidate.getBounds2D();
            done = true;
            break;
          }
        }
        radx += this.dRadius * 0.7;
        rady += this.dRadius * 0.4;
      }
    }

    double minx = Integer.MAX_VALUE;
    double miny = Integer.MAX_VALUE;
    double maxx = -Integer.MAX_VALUE;
    double maxy = -Integer.MAX_VALUE;
    for (Word w : words) {
      minx = Math.min( minx, w.bounds.getMinX() + 1 );
      miny = Math.min( miny, w.bounds.getMinY() + 1 );
      maxx = Math.max( maxx, w.bounds.getMaxX() + 1 );
      maxy = Math.max( maxy, w.bounds.getMaxY() + 1 );
    }
    AffineTransform shiftTr = AffineTransform.getTranslateInstance( -minx, -miny );
    for (Word w : words) {
      w.shape = shiftTr.createTransformedShape( w.shape );
      w.bounds = w.shape.getBounds2D();
    }
    this.imageSize = new Rectangle2D.Double( 0, 0, maxx - minx, maxy - miny );
  }
  
  /**
   * Sorting words by weight in ascendant order
   */
  public void sortweight()
  {
    Collections.sort( this.words, new Comparator<Word>()
    {
      @Override
      public int compare( Word w1, Word w2 )
      {
        return w1.weight - w2.weight;
      }
    } );
    
  }
  /**
   * Reverse sort of words by weight, descendant order
   */
  public void rsortweight()
  {
    Collections.sort( this.words, new Comparator<Word>()
    {
      @Override
      public int compare( Word w1, Word w2 )
      {
        return w2.weight - w1.weight;
      }
    } );
  }

  /**
   * Alphabetic sort of words
   */
  public void sortalpha()
  {
    Collections.sort( this.words, new Comparator<Word>()
    {
      @Override
      public int compare( Word w1, Word w2 )
      {
        return w1.text.compareTo( w2.text );
      }
    } );

  }
  /**
   * Shuffle words
   */
  public void shuffle()
  {
    Collections.shuffle( this.words, this.rand );
  }
  
  /**
   * Output a PNG file
   * @param file
   * @throws IOException
   */
  public void png( File file ) throws IOException
  {
    AffineTransform scale = new AffineTransform();
    Dimension dim = new Dimension( (int) this.imageSize.getWidth(), (int) this.imageSize.getHeight() );

    if (this.outputWidth != null) {
      double ratio = this.outputWidth / dim.getWidth();
      dim.width = this.outputWidth;
      dim.height = (int) (dim.getHeight() * ratio);
      scale = AffineTransform.getScaleInstance( ratio, ratio );
    }

    BufferedImage img = new BufferedImage( dim.width, dim.height, BufferedImage.TYPE_INT_ARGB );

    Graphics2D g = (Graphics2D) img.getGraphics();
    g.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
    g.setTransform( scale );
    for (Word w : this.words) {
      Color c = this.fill;
      if ( w.wclass != null && w.wclass.fill != null ) c = w.wclass.fill;
      if (c != null) {
        g.setColor( c );
        g.fill( w.shape );
      }
      c = this.stroke;
      if ( w.wclass != null && w.wclass.stroke != null ) c = w.wclass.stroke;
      if (c != null) {
        Stroke old = g.getStroke();
        // TODO, lineHeight ?
        g.setStroke( new BasicStroke( 1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND ) );
        g.setColor( c );
        g.draw( w.shape );
        g.setStroke( old );
      }
    }

    g.dispose();
    ImageIO.write( img, "png", file );
  }

  /**
   * Output an html file
   * @param file
   * @throws IOException
   * @throws XMLStreamException
   */
  public void html( File file ) throws IOException, XMLStreamException
  {
    final String HTML = "http://www.w3.org/1999/xhtml";
    XMLOutputFactory xmlfactory = XMLOutputFactory.newInstance();
    FileOutputStream fout = new FileOutputStream( file );
    XMLStreamWriter xml = xmlfactory.createXMLStreamWriter( fout, "UTF-8" );
    xml.setPrefix( "", HTML );
    xml.writeStartElement( HTML, "div" );
    xml.writeAttribute( "style", "height:100%;" );
    xml.writeAttribute( "class", "wordcloud" );

    for (Word w : this.words) {
      xml.writeCharacters( "\n" );
      xml.writeStartElement( HTML, "a" );
      if ( this.href != null )
        xml.writeAttribute( "href", this.href+w.text );
      if ( w.wclass != null )
        xml.writeAttribute( "class", w.wclass.name );
      xml.writeAttribute( "style", "left:"+ dfdec1.format(w.bounds.getX() )+"px; top:"+ dfdec1.format( 1.0*w.bounds.getY() )+"px; font-size:"+ dfdec1.format( 1.0* w.fontsize / this.fontmin )+"em" );
      xml.writeCharacters( w.text );
      xml.writeEndElement();
    }
    xml.writeCharacters( "\n" );
    xml.writeEndDocument();
    xml.flush();
    xml.close();
    fout.flush();
    fout.close();
  }

  /**
   * For testing
   * @throws IOException 
   * @throws XMLStreamException 
   */
  public static void main( String[] args) throws IOException, XMLStreamException 
  {
    /* get list of available fonts on this system
    for ( String font : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames() ) {
      System.out.println( font );
    }
    */
    Cloud cloud=new Cloud();
    Wordclass sub = new Wordclass( "sub", "Arial", new Color(32, 32, 128, 144), null);
    Wordclass name = new Wordclass( "name", "Georgia", new Color(0, 0, 0, 255), null);
    Wordclass verb = new Wordclass( "verb", "Georgia", new Color(255, 0, 0, 255), null);
    Wordclass adj = new Wordclass( "adj", "Georgia",  new Color(64, 128, 64, 200), null);
    Wordclass adv = new Wordclass( "adv", "Georgia", new Color(32, 32, 32, 128), null);
    Wordclass word = new Wordclass( "word", "Georgia", new Color(32, 32, 32, 128), null);
    String file = "../alix-demo/WEB-INF/textes/zola_bonheur.xml";
    String xml = new String(Files.readAllBytes( Paths.get( file ) ), StandardCharsets.UTF_8);
    TermDic words = new TermDic();
    Tokenizer toks = new Tokenizer( xml );
    long time;
    time = System.nanoTime();
    System.out.print( file );
    Occ occ;
    while ( (occ = toks.word( )) != null ) {
      // ne pas incrémenter le compteur global pour la ponctuation
      if ( occ.tag().isPun() ) {
        words.add( occ.orth(), occ.tag().code(), 0 );
      }
      else if ( occ.tag().isVerb() || occ.tag().isAdj() ) {
        words.inc( occ.lem(), occ.tag().code() );
      }
      else words.inc( occ.orth(), occ.tag().code() );
    }
    System.out.println( " parsé en "+((System.nanoTime() - time) / 1000000)+" ms."  );
    time = System.nanoTime();
    int limit=300;
    // loop on dictionary
    int n=0;
    Wordclass wc;
    Lexentry lexentry;
    float franfreq;
    long occs = words.occs();
    for( Map.Entry<String,Terminfos> dicentry: words.entriesByCount() ) {
      int tag = dicentry.getValue().tag();
      if ( Tag.isNum( tag ) ) continue;
      if ( Tag.isName( tag ) ) continue;
      String term = dicentry.getKey();
      int count = dicentry.getValue().count();
      if ("devoir".equals( term )) lexentry = Lexik.entry( "doit" );
      else lexentry = Lexik.entry( term );
      if ( lexentry == null && Lexik.isStop( term ) ) continue;
      
      float ratio = 4F;
      if ( Lexik.isStop( term ) ) ratio = 10F;
      else if ( Tag.isSub( tag ) ) ratio = 12F;
      else if ( Tag.isVerb( tag ) ) ratio = 6F;
      if ( lexentry == null ) franfreq = 0;
      else if ( Tag.isSub( tag ) ) {
        franfreq = lexentry.orthfreq;
      }
      else {
        franfreq = lexentry.lemfreq;
      }
      double myfreq = 1.0*count*1000000/occs;
      if ( franfreq > 0 && myfreq/franfreq < ratio ) continue;

      // if ( Lexik.isStop( term ) ) continue;
      wc = word;
      if ( Tag.isSub( tag ) ) wc = sub;
      else if ( Tag.isVerb( tag ) ) wc = verb;
      else if ( Tag.isName( tag ) ) wc = name;
      else if ( Tag.isAdj( tag ) ) wc = adj;
      else if ( Tag.isAdv( tag ) ) wc = adv;
      
      cloud.add( new Word( dicentry.getKey(), count,  wc ) );
      if ( ++n >= limit ) break;
    }
    System.out.println( ((System.nanoTime() - time) / 1000000)+" ms. pour remplir le nuage"  );
    
    /*
    BufferedReader in = new BufferedReader(new FileReader("freqlist.csv"));
    while( (line = in.readLine() ) != null ) {
      cells = line.split( ";" );
    // cloud.add( new Word( cells[0], Integer.parseInt( cells[1] ), null ) );
    }
    */
    time = System.nanoTime();
    cloud.doLayout();
    System.out.println( ((System.nanoTime() - time) / 1000000)+" ms. pour le positionner"  );
    time = System.nanoTime();
    cloud.html( new File("test.html") );
    System.out.println( ((System.nanoTime() - time) / 1000000)+" ms. pour html"  );
    time = System.nanoTime();
    cloud.png( new File("test.png") );
    System.out.println( ((System.nanoTime() - time) / 1000000)+" ms. pour png"  );
  }


}
