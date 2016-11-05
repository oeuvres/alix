package site.oeuvres.fr;


import java.lang.reflect.Field;
import java.util.HashMap;

import site.oeuvres.util.Term;



/**
 * Tagset
 * @author glorieux
 *
 */
public final class Tag
{
  /** Category */
  private short code;
  
  public final static short UNKNOWN = -1; 
  public final static short NULL = 0; 
  public final static short VERB = 0x10;
  public final static short VERBaux = 0x11; 
  public final static short VERBppass = 0x12; 
  public final static short VERBppres = 0x13; 
  public final static short VERBsup = 0x15;
  public final static short SUB  = 0x20;
  public final static short ADJ  = 0x30;
  public final static short ADV  = 0x40;
  public final static short ADVneg = 0x41;
  public final static short ADVplace = 0x42;
  public final static short ADVtemp = 0x43;
  public final static short ADVquant = 0x44;
  public final static short ADVindef = 0x4A;
  public final static short ADVinter = 0x4B;
  public final static short PREP = 0x50;
  public final static short DET  = 0x60;
  public final static short DETart = 0x61;
  public final static short DETprep = 0x62;
  public final static short DETindef = 0x6A;
  public final static short DETinter = 0x6B;
  public final static short DETdem = 0x6C;
  public final static short DETposs = 0x6D;
  public final static short PRO  = 0x70;
  public final static short PROpers = 0x71;
  public final static short PROrel = 0x72;
  public final static short PROindef = 0x7A;
  public final static short PROint = 0x7B;
  public final static short PROdem = 0x7C;
  public final static short PROposs = 0x7D;
  public final static short CONJ = 0x80;
  public final static short CONJcoord = 0x81; 
  public final static short CONJsubord = 0x82;
  public final static short EXCL = 0x90;
  public final static short NUM  = 0xA0;
  public final static short NAME = 0xB0;
  public final static short NAMEpers = 0xB1;
  public final static short NAMEpersm = 0xB2;
  public final static short NAMEpersf = 0xB3;
  public final static short NAMEplace = 0xB5;
  public final static short NAMEevent = 0xB6;
  public final static short NAMEtitle = 0xB7;
  public final static short PUN  = 0xC0;
  public final static short PUNsent  = 0xC1;
  public final static short PUNcl  = 0xC2;
  public final static short ABBR  = 0xD0;
  static final HashMap<String, Short> CODE = new  HashMap<String, Short>(); 
  static final HashMap<Short, String> LABEL = new HashMap<Short, String>(); 
  // loop on the static fields declared to populate the HashMaps
  static {
    String name;
    short value = 0;
    Field[] declaredFields = Tag.class.getDeclaredFields();
    for (Field field : declaredFields) {
      if (! java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
      name = field.getName();
      if ( name.equals( "CODE" ) || name.equals("LABEL")) continue;
      try {
        value = field.getShort( null );
      } catch (IllegalArgumentException e) {
        e.printStackTrace();
      } catch (IllegalAccessException e) {
        e.printStackTrace();
      }
      CODE.put( name, value );
      LABEL.put( value, name );
    }
  }
  /**
   * Empty constructor, needed by some consumer
   */
  Tag( ) 
  {
  }
  /**
   * Build a category by code
   * @param code
   */
  Tag( int code ) 
  {
    this.code = (short)code;
  }
  /**
   * Build a category by label
   * @param code
   */
  Tag( String label ) 
  {
    this.code = Tag.code( label );
  }
  /**
   * Return the String value of the code.
   * @return
   */
  public String label()
  {
    return Tag.label( this.code );
  }
  /**
   * Return code value
   * @return
   */
  public short code()
  {
    return this.code;
  }
  /**
   * Set code value
   * @return
   */
  public Tag code( final int code )
  {
    this.code = (short)code;
    return this;
  }
  public Tag code( Tag tag )
  {
    this.code = tag.code;
    return this;
  }
  public static short code ( final Term label )
  {
    Short ret = CODE.get( label );
    if (ret == null) return UNKNOWN;
    return ret;
  }
  
  public static short code ( final String label )
  {
    Short ret = CODE.get( label );
    if (ret == null) return UNKNOWN;
    return ret;
  }
  public static String label ( final int code )
  {
    String ret = LABEL.get( (short)code );
    if ( ret == null ) return "UNKNOWN";
    return ret;
  }
  public String prefix()
  {
    return label( (short)(code >> 0x4 << 0x4) );
  }
  public static String prefix( final int code)
  {
    return label( (short)(code >> 0x4 << 0x4) );
  }
  public boolean isVerb( )
  {
    return (( code >> 0x4 ) == 0x1 );
  }
  public static boolean isVerb( final int code )
  {
    return (( code >> 0x4 ) == 0x1 );
  }
  public boolean isAdv( )
  {
    return (( code >> 0x4 ) == 0x4 );
  }
  public static boolean isAdv( final int code )
  {
    return (( code >> 0x4 ) == 0x4 );
  }
  public boolean isDet( )
  {
    return (( code >> 0x4 ) == 0x6 );
  }
  public static boolean isDet( final int code )
  {
    return (( code >> 0x4 ) == 0x6 );
  }
  public boolean isName( )
  {
    return (( (short)code >> 0x4 ) == 0xB );
  }
  public static boolean isName( final int code )
  {
    return (( (short)code >> 0x4 ) == 0xB );
  }
  public boolean isPun( )
  {
    return (( code >> 0x4 ) == 0xC );
  }
  public static boolean isPun( final int code )
  {
    return (( code >> 0x4 ) == 0xC );
  }
  @Override
  public boolean equals( Object o ){
    if ( o == this ) return true;
    if ( o instanceof Integer ) return o.equals( (int)code );
    if ( o instanceof Short ) return o.equals( code );
    if ( o instanceof String ) return ( code == Tag.code( (String) o ) );
    if ( o instanceof Term ) return ( code == Tag.code( (Term) o ) );
    return false;
  }
  
  /**
   * For testing
   * @throws IOException 
   */
  public static void main(String[] args)
  {
    System.out.println( new Tag(Tag.SUB).equals( Tag.SUB ) );
    System.out.println( new Tag(0).equals( (short)0 ) );
    System.out.println( isName( NAMEplace ) );
    System.out.println( code("TEST") );
    Term t = new Term("ADV");
    System.out.println( code( t ) );
    System.out.println( "prefix label by number category ADVint : "+ prefix( Tag.ADVinter ) );
    
  }
}
