package alix.fr;


import java.lang.reflect.Field;
import java.util.HashMap;

import alix.util.Term;



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
  public final static short SUBm  = 0x21;
  public final static short SUBf  = 0x22;
  public final static short SUBtit = 0x28;
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
  public final static short DETnum  = 0x63;
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
  public final static short NAME = 0xB0;
  public final static short NAMEpers = 0xB1;
  public final static short NAMEpersm = 0xB2;
  public final static short NAMEpersf = 0xB3;
  public final static short NAMEplace = 0xB4;
  public final static short NAMEorg = 0xB5;
  public final static short NAMEpeople = 0xB6;
  public final static short NAMEevent = 0xB7;
  public final static short NAMEauthor = 0xB8;
  public final static short NAMEfict = 0xB9;
  public final static short NAMEtitle = 0xBA;
  public final static short NAMEanimal = 0xBD;
  public final static short NAMEdemhum = 0xBE;
  public final static short NAMEgod = 0xBF;
  public final static short EXCL = 0x90;
  public final static short NUM = 0xA0;
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
  public Tag( ) 
  {
  }
  /**
   * Build a category by code
   * @param code
   */
  public Tag( int code ) 
  {
    set( code);
  }
  /**
   * Build a category by label
   * @param code
   */
  public Tag( String label ) 
  {
    set(Tag.code( label ));
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
  public Tag set( final int code )
  {
    this.code = (short)code;
    return this;
  }
  public Tag set( Tag tag )
  {
    if ( tag == null ) set( Tag.NULL);
    else set(tag.code);
    return this;
  }
  public Tag set( String label )
  {
    set( Tag.code( label ) );
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
    if ( ret == null ) return LABEL.get( UNKNOWN );
    return ret;
  }
  public boolean isEmpty()
  {
    return (code == NULL);
  }
  public boolean isPrefix()
  {
    if ( code == NULL || code == UNKNOWN ) return false;
    return (code & 0xF) == 0;
  }
  static public boolean isPrefix( final int code )
  {
    if ( code == NULL || code == UNKNOWN ) return false;
    return (code & 0xF) == 0;
  }
  public int prefix()
  {
    return prefix ( code );
  }
  public static int prefix( final int code)
  {
    return code >> 0x4 << 0x4 ;
  }
  public boolean isVerb( )
  {
    return isVerb( code );
  }
  public static boolean isVerb( final int code )
  {
    return (( code >> 0x4 ) == 0x1 );
  }
  public boolean isSub( )
  {
    return isSub( code );
  }
  public static boolean isSub( final int code )
  {
    return (( code >> 0x4 ) == 0x2 );
  }
  public boolean isAdj( )
  {
    return isAdj( code );
  }
  public static boolean isAdj( final int code )
  {
    return (( (short)code >> 0x4 ) == 0x3 );
  }
  public boolean isAdv( )
  {
    return isAdv( code );
  }
  public static boolean isAdv( final int code )
  {
    return (( code >> 0x4 ) == 0x4 );
  }
  public boolean isDet( )
  {
    return isDet( code );
  }
  public static boolean isDet( final int code )
  {
    return (( code >> 0x4 ) == 0x6 );
  }
  public boolean isPro( )
  {
    return isPro( code );
  }
  public static boolean isPro( final int code )
  {
    return (( code >> 0x4 ) == 0x7 );
  }
  public boolean isName( )
  {
    return isName( code );
  }
  public static boolean isName( final int code )
  {
    return (( (short)code >> 0x4 ) == 0xB );
  }
  public boolean isPun( )
  {
    return isPun( code );
  }
  public static boolean isPun( final int code )
  {
    return (( code >> 0x4 ) == 0xC );
  }
  public boolean isNum()
  {
    return isNum( code );
  }
  public static boolean isNum( final int code )
  {
    return (code == NUM || code == DETnum);
  }
  @Override
  public boolean equals( Object o ){
    if ( o == this ) return true;
    if ( o instanceof String ) return ( code == Tag.code( (String) o ) );
    if ( o instanceof Tag ) return (((Tag)o).code == code );
    if ( o instanceof Integer ) return o.equals( (int)code );
    if ( o instanceof Short ) return o.equals( code );
    if ( o instanceof Term ) return ( code == Tag.code( (Term) o ) );
    return false;
  }
  @Override
  public String toString() {
    return Tag.label( code );
  }
  
  /**
   * For testing
   * @throws IOException 
   */
  public static void main(String[] args)
  {
    System.out.println( "test equals "+new Tag(Tag.SUB).equals( Tag.SUB ) );
    System.out.println( isName( NAMEplace ) );
    System.out.println( "is NAMEplace a prefix ? "+isPrefix( NAMEplace ) );
    System.out.println( "is NAME a prefix ? "+isPrefix( NAME ) );
    System.out.println( "UNKNOW tag "+code("TEST") );
    System.out.println( "prefix label by number category ADVint : "+ prefix( Tag.ADVinter ) );
  }
}
