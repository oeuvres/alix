package site.oeuvres.fr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;

import site.oeuvres.util.Term;


/**
 * Tagset
 * @author glorieux
 *
 */
public final class Cat
{
  public static final HashMap<String, Short> CODE = new  HashMap<String, Short>(); 
  public static final HashMap<Short, String> LABEL = new HashMap<Short, String>(); 
  
  final static short UNKNOWN = -1; 
  final static String sUNKNOWN="UNKNOWN";
  {
    CODE.put( sUNKNOWN, UNKNOWN );
    CODE.put( "UNKNOWN", UNKNOWN );
    LABEL.put( UNKNOWN, sUNKNOWN );
  }
  final static short VERB = 0x10;
  final static String sVERB ="VERB";
  final static short VERBaux = 0x11; 
  final static String sVERBaux="VERB:aux";
  final static short VERBppass = 0x12; 
  final static String sVERBppass ="VERB:ppass";
  final static short VERBppres = 0x13; 
  final static String sVERBppres ="VERB:ppres";
  final static short VERBsup = 0x15;
  final static String sVERBsup="VERB:sup";
  static 
  {
    CODE.put( sVERB,  VERB );
    LABEL.put( VERB,  sVERB );
    CODE.put( sVERBaux, VERBaux );
    LABEL.put( VERBaux, sVERBaux );
    CODE.put( sVERBppass, VERBppass );
    LABEL.put( VERBppass, sVERBppass );
    CODE.put( sVERBppres, VERBppres );
    LABEL.put( VERBppres, sVERBppres );
    CODE.put( sVERBsup, VERBsup );
    LABEL.put( VERBsup, sVERBsup );
  }
  final static short SUB  = 0x20;
  final static String sSUB="SUB";
  static
  {
    CODE.put( sSUB, SUB );
    LABEL.put( SUB, sSUB );    
  }
  final static short ADJ  = 0x30;
  final static String sADJ="ADJ";
  static
  {
    CODE.put( sADJ, ADJ );
    LABEL.put( ADJ, sADJ );    
  }
  final static short ADV  = 0x40;
  final static String sADV="ADV";
  final static short ADVint = 0x41;
  final static String sADVint="ADV:int";
  final static short ADVindef = 0x42;
  final static String sADVindef="ADV:indef";
  final static short ADVloc = 0x43;
  final static String sADVloc="ADV:loc";
  final static short ADVneg = 0x44;
  final static String sADVneg="ADV:neg";
  final static short ADVquant = 0x45;
  final static String sADVquant="ADV:quant";
  final static short ADVtemp = 0x46;
  final static String sADVtemp="ADV:temp";
  static
  {
    CODE.put( sADV, ADV );
    LABEL.put( ADV, sADV );
    CODE.put( sADVint, ADVint );
    LABEL.put( ADVint, sADVint );
    CODE.put( sADVindef, ADVindef );
    LABEL.put( ADVindef, sADVindef );
    CODE.put( sADVloc, ADVloc );
    LABEL.put( ADVloc, sADVloc );
    CODE.put( sADVneg, ADVneg );
    LABEL.put( ADVneg, sADVneg );
    CODE.put( sADVquant, ADVquant );
    LABEL.put( ADVquant, sADVquant );
    CODE.put( sADVtemp, ADVtemp );
    LABEL.put( ADVtemp, sADVtemp );
  }
  final static short PREP = 0x50;
  final static String sPREP="PREP";
  static
  {
    CODE.put( sPREP, PREP );
    LABEL.put( PREP, sPREP );
  }
  final static short DET  = 0x60;
  final static String sDET="DET";
  final static short DETart = 0x61;
  final static String sDETart="DET:art";
  final static short DETprep = 0x62;
  final static String sDETprep="DET:prep";
  final static short DETdem = 0x63;
  final static String sDETdem="DET:dem";
  final static short DETindef = 0x64;
  final static String sDETindef="DET:indef";
  final static short DETint = 0x65;
  final static String sDETint="DET:int";
  final static short DETposs = 0x66;
  final static String sDETposs="DET:poss";
  static
  {
    CODE.put( sDET, DET );
    LABEL.put( DET, sDET );
    CODE.put( sDETart, DETart );
    LABEL.put( DETart, sDETart );
    CODE.put( sDETprep, DETprep );
    CODE.put( sDETdem, DETdem );
    LABEL.put( DETdem, sDETdem );
    CODE.put( sDETindef, DETindef );
    LABEL.put( DETindef, sDETindef );
    CODE.put( sDETint, DETint );
    LABEL.put( DETint, sDETint );
    CODE.put( sDETposs, DETposs );
    LABEL.put( DETposs, sDETposs );
  }
  final static short PRO  = 0x70;
  final static String sPRO="PRO";
  final static short PROdem = 0x71;
  final static String sPROdem="PRO:dem";
  final static short PROindef = 0x72;
  final static String sPROindef="PRO:indef";
  final static short PROint = 0x73;
  final static String sPROint="PRO:int";
  final static short PROposs = 0x74;
  final static String sPROposs="PRO:poss";
  final static short PROpers = 0x75;
  final static String sPROpers="PRO:pers";
  final static short PROrel = 0x76;
  final static String sPROrel="PRO:rel";
  static
  {
    CODE.put( sPRO, PRO );
    LABEL.put( PRO, sPRO );
    CODE.put( sPROdem, PROdem );
    LABEL.put( PROdem, sPROdem );
    CODE.put( sPROindef, PROindef );
    LABEL.put( PROindef, sPROindef );
    CODE.put( sPROint, PROint );
    LABEL.put( PROint, sPROint );
    CODE.put( sPROposs, PROposs );
    LABEL.put( PROposs, sPROposs );
    CODE.put( sPROpers, PROpers );
    LABEL.put( PROpers, sPROpers );
    CODE.put( sPROrel, PROrel );
    LABEL.put( PROrel, sPROrel );
  }
  final static short CONJ = 0x80;
  final static String sCONJ="CONJ";
  final static short CONJcoord = 0x81; 
  final static String sCONJcoord="CONJ:coord";
  final static short CONJsubord = 0x82;
  final static String sCONJsubord="CONJ:subord";
  static
  {
    CODE.put( sCONJ, CONJ );
    LABEL.put( CONJ, sCONJ );
    CODE.put( sCONJcoord, CONJcoord );
    LABEL.put( CONJcoord, sCONJcoord );
    CODE.put( sCONJsubord, CONJsubord );
    LABEL.put( CONJsubord, sCONJsubord );    
  }
  final static short EXCL = 0x90;
  final static String sEXCL="EXCL";
  static
  {
    CODE.put( sEXCL, EXCL );
    LABEL.put( EXCL, sEXCL );
  }  
  final static short NUM  = 0xA0;
  final static String sNUM="NUM";
  static
  {
    CODE.put( sNUM, NUM );
    LABEL.put( NUM, sNUM );
  }
  final static short NAME = 0xB0;
  final static String sNAME="NAME";
  static
  {
    CODE.put( sNAME, NAME );
    CODE.put( "NAM", NAME );
    LABEL.put( NAME, sNAME );
  }
  final static short PUN  = 0xC0;
  final static String sPUN="PUN";
  final static short PUNsent  = 0xC1;
  final static String sPUNsent="PUNsent";
  final static short PUNcl  = 0xC2;
  final static String sPUNcl ="PUNcl";
  static
  {
    CODE.put( sPUN, PUN );
    LABEL.put( PUN, sPUN );
    CODE.put( sPUNsent, PUNsent );
    LABEL.put( PUNsent, sPUNsent );
    CODE.put( sPUNcl, PUNcl );
    LABEL.put( PUNcl, sPUNcl );
  }
  
  public static short code ( final String label )
  {
    if ( !CODE.containsKey( label ))
      return UNKNOWN;
    return CODE.get( label );
  }
  public static String label ( final short code )
  {
    if ( !LABEL.containsKey( code ))
      return sUNKNOWN;
    return LABEL.get( code );
  }
  public static boolean isDet( final short code )
  {
    return (( code >> 0x4 ) == 0x6 );
  }
  public static boolean isAdv( final short code )
  {
    return (( code >> 0x4 ) == 0x4 );
  }
  public static boolean isVerb( final short code )
  {
    return (( code >> 0x4 ) == 0x1 );
  }
  
  /**
   * For testing
   * @throws IOException 
   */
  public static void main(String[] args) throws IOException 
  {
    System.out.println( isVerb( ADV ) );
    String l;
    BufferedReader buf;
    buf = new BufferedReader( 
      new InputStreamReader(
        Tokenizer.class.getResourceAsStream( "word.csv" ), 
        StandardCharsets.UTF_8
      )
    );
    String[] cell;
    buf.readLine(); // first line
    int n = 0;
    
    while ((l = buf.readLine()) != null) {
      cell = l.split( "\t" );
      n++;
      if ( CODE.containsKey( cell[2] ) ) continue;
      System.out.println( l );
    }
    buf.close();    
    System.out.println( n+" words" );
  }
}
