package site.oeuvres.fr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import site.oeuvres.util.Term;


/**
 * Tagset
 * @author glorieux
 *
 */
public final class Cat
{
  final static int VERB = 0x0001;
  final static String sVERB ="VERB";
  final static int SUB  = 0x0002;
  final static String sSUB="SUB";
  final static int ADJ  = 0x0004;
  final static String sADJ="ADJ";
  final static int ADV  = 0x0008;
  final static String sADV="ADV";
  final static int DET  = 0x0010;
  final static String sDET="DET";
  final static int PRO  = 0x0020;
  final static String sPRO="PRO";
  final static int PREP = 0x0040;
  final static String sPREP="PREP";
  final static int CONJ = 0x0080;
  final static String sCONJ="CONJ";
  final static int EXCL = 0x0100;
  final static String sEXCL="EXCL";
  final static int NUM  = 0x0200;
  final static String sNUM="NUM";
  final static int NAME = 0x0400;
  final static String sNAME="NAME";
  final static int PUN  = 0x0800;
  final static String sPUN="PUN";
  final static int UNKNOWN = 0x4000; 
  final static String sUNKNOWN="UNKNOWN";
  
  final static int indef  = 0x00010000;
  final static int dem    = 0x00020000;
  final static int poss   = 0x00040000;
  final static int inter  = 0x00080000;
  final static int pers   = 0x00100000;
  final static int rel    = 0x00200000;
  final static int art    = 0x00400000;
  final static int coord  = 0x00800000;
  final static int subord = 0x01000000;
  final static int loc    = 0x02000000;
  final static int temp   = 0x04000000;
  final static int quant  = 0x08000000;
  final static int neg    = 0x10000000;
  final static int aux    = 0x20000000;
  final static int sup    = 0x40000000;
    
  final static int VERBaux = VERB | aux; 
  final static String sVERBaux="VERB:aux";
  final static int VERBsup = VERB | sup; 
  final static String sVERBsup="VERB:sup";

  final static int CONJcoord = CONJ | coord; 
  final static String sCONJcoord="CONJ:coord";
  final static int CONJsubord = VERB | subord;
  final static String sCONJsubord="CONJ:subord";
  
  final static int DETart = DET | art;
  final static String sDETart="DET:art";
  final static int DETprep = DET | PREP;
  final static String sDETprep="DET:prep";
  final static int DETdem = DET | dem;
  final static String sDETdem="DET:dem";
  final static int DETindef = DET | indef;
  final static String sDETindef="DET:indef";
  final static int DETint = DET | inter;
  final static String sDETint="DET:int";
  final static int DETposs = DET | poss;
  final static String sDETposs="DET:poss";
  
  final static int PROdem = PRO | dem;
  final static String sPROdem="PRO:dem";
  final static int PROindef = PRO | indef;
  final static String sPROindef="PRO:indef";
  final static int PROint = PRO | inter;
  final static String sPROint="PRO:int";
  final static int PROposs = PRO | poss;
  final static String sPROposs="PRO:poss";
  final static int PROpers = PRO | pers;
  final static String sPROpers="PRO:pers";
  final static int PROrel = PRO | rel;
  final static String sPROrel="PRO:rel";

  final static int ADVint = ADV | inter;
  final static String sADVint="ADV:int";
  final static int ADVindef = ADV | indef;
  final static String sADVindef="ADV:indef";
  final static int ADVloc = ADV | loc;
  final static String sADVloc="ADV:loc";
  final static int ADVneg = ADV | neg;
  final static String sADVneg="ADV:neg";
  final static int ADVquant = ADV | quant;
  final static String sADVquant="ADV:quant";
  final static int ADVtemp = ADV | temp;
  final static String sADVtemp="ADV:temp";
  
  public static final HashMap<String, Integer> CODE = new  HashMap<String, Integer>(); 
  public static final HashMap<Integer, String> LABEL = new HashMap<Integer, String>(); 
  
  static
  {
    CODE.put( sVERB,  VERB );
    CODE.put( sSUB, SUB );
    CODE.put( sADJ, ADJ );
    CODE.put( sADV, ADV );
    CODE.put( sDET, DET );
    CODE.put( sPRO, PRO );
    CODE.put( sPREP, PREP );
    CODE.put( sCONJ, CONJ );
    CODE.put( sEXCL, EXCL );
    CODE.put( sNUM, NUM );
    CODE.put( sNAME, NAME );
    CODE.put( "NAM", NAME );
    CODE.put( sPUN, PUN );
    CODE.put( sUNKNOWN, UNKNOWN );
    CODE.put( "UNKNOWN", UNKNOWN );
    CODE.put( sCONJcoord, CONJcoord );
    CODE.put( sCONJsubord, CONJsubord );
    CODE.put( sVERBaux, VERBaux );
    CODE.put( sVERBsup, VERBsup );
    CODE.put( sDETart, DETart );
    CODE.put( sDETprep, DETprep );
    CODE.put( sDETdem, DETdem );
    CODE.put( sDETindef, DETindef );
    CODE.put( sDETint, DETint );
    CODE.put( sDETposs, DETposs );
    CODE.put( sPROdem, PROdem );
    CODE.put( sPROindef, PROindef );
    CODE.put( sPROint, PROint );
    CODE.put( sPROposs, PROposs );
    CODE.put( sPROpers, PROpers );
    CODE.put( sPROrel, PROrel );
    CODE.put( sADVint, ADVint );
    CODE.put( sADVindef, ADVindef );
    CODE.put( sADVloc, ADVloc );
    CODE.put( sADVneg, ADVneg );
    CODE.put( sADVquant, ADVquant );
    CODE.put( sADVtemp, ADVtemp );

    LABEL.put( VERB,  sVERB );
    LABEL.put( SUB, sSUB );
    LABEL.put( ADJ, sADJ );
    LABEL.put( ADV, sADV );
    LABEL.put( DET, sDET );
    LABEL.put( PRO, sPRO );
    LABEL.put( PREP, sPREP );
    LABEL.put( CONJ, sCONJ );
    LABEL.put( EXCL, sEXCL );
    LABEL.put( NUM, sNUM );
    LABEL.put( NAME, sNAME );
    LABEL.put( PUN, sPUN );
    LABEL.put( UNKNOWN, sUNKNOWN );
    LABEL.put( CONJcoord, sCONJcoord );
    LABEL.put( CONJsubord, sCONJsubord );
    LABEL.put( VERBaux, sVERBaux );
    LABEL.put( VERBsup, sVERBsup );
    LABEL.put( DETart, sDETart );
    LABEL.put( DETdem, sDETdem );
    LABEL.put( DETindef, sDETindef );
    LABEL.put( DETint, sDETint );
    LABEL.put( DETposs, sDETposs );
    LABEL.put( PROdem, sPROdem );
    LABEL.put( PROindef, sPROindef );
    LABEL.put( PROint, sPROint );
    LABEL.put( PROposs, sPROposs );
    LABEL.put( PROpers, sPROpers );
    LABEL.put( PROrel, sPROrel );
    LABEL.put( ADVint, sADVint );
    LABEL.put( ADVindef, sADVindef );
    LABEL.put( ADVloc, sADVloc );
    LABEL.put( ADVneg, sADVneg );
    LABEL.put( ADVquant, sADVquant );
    LABEL.put( ADVtemp, sADVtemp );
  }
  
  public static int code ( String label )
  {
    if ( !CODE.containsKey( label ))
      return UNKNOWN;
    return CODE.get( label );
  }
  public static String label ( int code )
  {
    if ( !LABEL.containsKey( code ))
      return sUNKNOWN;
    return LABEL.get( code );
  }
  
  /**
   * For testing
   * @throws IOException 
   */
  public static void main(String[] args) throws IOException 
  {
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
    while ((l = buf.readLine()) != null) {
      cell = l.split( "\t" );
      if ( CODE.containsKey( cell[2] ) ) continue;
      System.out.println( cell[2] );
    }
    buf.close();    
  }
}
