package alix.fr;

import java.lang.reflect.Field;
import java.util.HashMap;
import alix.util.Term;

/**
 * Jeu d’étiquettes morphosyntaxique pour le français.
 * 
 * @author glorieux
 */
public final class Tag
{
  /** Connu comme inconnu (selon les dictionnaires) */
  public final static short UNKNOWN = -1;
  /** Valeur par défaut, aucune information */
  public final static short NULL = 0;
  /** Verbe, qui n'est pas l'une des catégories ci-dessous */
  public final static short VERB = 0x10;
  /** Auxilliaire, conjugaison d’être et avoir */
  public final static short VERBaux = 0x11;
  /** Participe passé, peut avoir un emploi adjectif, voire substantif */
  public final static short VERBppass = 0x12;
  /** Participe présent, a souvent un emploi adjectif ou substantif */
  public final static short VERBppres = 0x13;
  /**
   * Verbe support, fréquent mais peut significatif, comme aller (je vais faire)
   */
  public final static short VERBsup = 0x15;
  /** Substantif */
  public final static short SUB = 0x20;
  /** Substantif masculin (pas encore renseigné) */
  public final static short SUBm = 0x21;
  /** Substantif féminin (pas encore renseigné) */
  public final static short SUBf = 0x22;
  /** Catégorie un peu adhoc pour monsieur, madame, prince… */
  public final static short SUBtit = 0x28;
  /** Adjectif */
  public final static short ADJ = 0x30;
  /** Adverbe */
  public final static short ADV = 0x40;
  /** Adverbe de négation : ne, pas, point… */
  public final static short ADVneg = 0x41;
  /** Adverbe de lieu */
  public final static short ADVplace = 0x42;
  /** Adverbe de temps */
  public final static short ADVtemp = 0x43;
  /** Adverbe de quantité */
  public final static short ADVquant = 0x44;
  /** Adverbe indéfini : aussi, même… */
  public final static short ADVindef = 0x4A;
  /** Adverbe interrogatif : est-ce que, comment… */
  public final static short ADVinter = 0x4B;
  /** Préposition */
  public final static short PREP = 0x50;
  /** Déterminant */
  public final static short DET = 0x60;
  /** Déterminant article : le, la, un, des… */
  public final static short DETart = 0x61;
  /** Déterminant prépositionnel : du, au (?? non comptable ?) */
  public final static short DETprep = 0x62;
  /** Déterminant numéral : deux, trois */
  public final static short DETnum = 0x63;
  /** Déterminant indéfini : tout, tous, quelques… */
  public final static short DETindef = 0x6A;
  /** Déterminant interrogatif : quel, quelles… */
  public final static short DETinter = 0x6B;
  /** Déterminant démonstratif : ce, cette, ces… */
  public final static short DETdem = 0x6C;
  /** Déterminant possessif : son, mas, leurs… */
  public final static short DETposs = 0x6D;
  /** Pronom */
  public final static short PRO = 0x70;
  /** Pronom personnel : il, je, se, me… */
  public final static short PROpers = 0x71;
  /** Pronom relatif : qui, que, où… */
  public final static short PROrel = 0x72;
  /** Pronom indéfini : y, rien, tout… */
  public final static short PROindef = 0x7A;
  /** Pronom interrogatif : y, rien, tout… */
  public final static short PROint = 0x7B;
  /** Pronom démonstratif : c', ça, cela… */
  public final static short PROdem = 0x7C;
  /** Pronom possessif : le mien, la sienne… */
  public final static short PROposs = 0x7D;
  /** Conjonction */
  public final static short CONJ = 0x80;
  /** Conjonction de coordination : et, mais, ou… */
  public final static short CONJcoord = 0x81;
  /** Conjonction de subordination : comme, si, parce que… */
  public final static short CONJsubord = 0x82;
  /** Nom propre */
  public final static short NAME = 0xB0;
  /** Nom de personne */
  public final static short NAMEpers = 0xB1;
  /** Prénom masculin */
  public final static short NAMEpersm = 0xB2;
  /** Prénom féminin */
  public final static short NAMEpersf = 0xB3;
  /** Nom de lieu */
  public final static short NAMEplace = 0xB4;
  /** Nom d’organisation */
  public final static short NAMEorg = 0xB5;
  /** Nom de peuple */
  public final static short NAMEpeople = 0xB6;
  /** Nom d’événement : la Révolution, la Seconde Guerre mondiale… */
  public final static short NAMEevent = 0xB7;
  /** Nom de personne auteur */
  public final static short NAMEauthor = 0xB8;
  /** Nom de personnage fictif */
  public final static short NAMEfict = 0xB9;
  /** Titre d’œuvre */
  public final static short NAMEtitle = 0xBA;
  /** Animal : Pégase… */
  public final static short NAMEanimal = 0xBD;
  /** Demi-humain : Hercule… */
  public final static short NAMEdemhum = 0xBE;
  /** Noms de dieux : Dieu, Vénus… */
  public final static short NAMEgod = 0xBF;
  /** Ponctuation */
  public final static short PUN = 0xC0;
  /** Ponctuation de phrase : . ? ! */
  public final static short PUNsent = 0xC1;
  /** Ponctuation de clause : , ; ( */
  public final static short PUNcl = 0xC2;
  /** Ponctuation structurante : ¶, § */
  public final static short PUNdiv = 0xC3;
  /** Catégories diverses */
  public final static short MISC = 0xF0;
  /** Abréviation (encore non résolue) */
  public final static short ABBR = 0xF1;
  /** Numéro */
  public final static short NUM = 0xF2;
  /** Exclamation */
  public final static short EXCL = 0xF3;
  /** Mathematical operator */
  public final static short MATH = 0xF4;
  /** Particules démonstratives -ci, -là */
  public final static short PARTdem = 0xFC;
  /** Category */
  private short code;
  /** Dictionnaire des codes par nom */
  public static final HashMap<String, Short> CODE = new HashMap<String, Short>();
  /** Dictionnaire des noms par code */
  public static final HashMap<Short, String> LABEL = new HashMap<Short, String>();
  // loop on the static fields declared to populate the HashMaps
  static {
    String name;
    short value = 0;
    Field[] declaredFields = Tag.class.getDeclaredFields();
    for (Field field : declaredFields) {
      if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()))
        continue;
      name = field.getName();
      if (name.equals("CODE") || name.contains("LABEL"))
        continue;
      try {
        value = field.getShort(null);
      }
      catch (IllegalArgumentException e) {
        e.printStackTrace();
      }
      catch (IllegalAccessException e) {
        e.printStackTrace();
      }
      CODE.put(name, value);
      LABEL.put(value, name);
    }
  }

  /**
   * Empty constructor, needed by some consumer
   */
  public Tag() {
  }

  /**
   * Build a category by code
   * 
   * @param code
   */
  public Tag(int code) {
    set(code);
  }

  /**
   * Build a category by label
   * 
   * @param code
   */
  public Tag(String label) {
    set(Tag.code(label));
  }

  /**
   * Return the String value of the code.
   * 
   * @return
   */
  public String label()
  {
    return Tag.label(this.code);
  }

  /**
   * Return code value
   * 
   * @return
   */
  public short code()
  {
    return this.code;
  }

  /**
   * Set code value
   * 
   * @return
   */
  public Tag set(final int code)
  {
    this.code = (short) code;
    return this;
  }

  public Tag set(Tag tag)
  {
    if (tag == null)
      set(Tag.NULL);
    else
      set(tag.code);
    return this;
  }

  public Tag set(String label)
  {
    set(Tag.code(label));
    return this;
  }

  public static short code(final Term label)
  {
    Short ret = CODE.get(label);
    if (ret == null)
      return UNKNOWN;
    return ret;
  }

  public static short code(final String label)
  {
    Short ret = CODE.get(label);
    if (ret == null)
      return UNKNOWN;
    return ret;
  }

  public static String label(final int code)
  {
    String ret = LABEL.get((short) code);
    if (ret == null)
      return LABEL.get(UNKNOWN);
    return ret;
  }

  public boolean isEmpty()
  {
    return (code == NULL);
  }

  public boolean isPrefix()
  {
    if (code == NULL || code == UNKNOWN)
      return false;
    return (code & 0xF) == 0;
  }

  static public boolean isPrefix(final int code)
  {
    if (code == NULL || code == UNKNOWN)
      return false;
    return (code & 0xF) == 0;
  }

  public int prefix()
  {
    return prefix(code);
  }

  public static int prefix(final int code)
  {
    return code >> 0x4 << 0x4;
  }

  public boolean isVerb()
  {
    return isVerb(code);
  }

  public static boolean isVerb(final int code)
  {
    return ((code >> 0x4) == 0x1);
  }

  public boolean isSub()
  {
    return isSub(code);
  }

  public static boolean isSub(final int code)
  {
    return ((code >> 0x4) == 0x2);
  }

  public boolean isAdj()
  {
    return isAdj(code);
  }

  public static boolean isAdj(final int code)
  {
    return (((short) code >> 0x4) == 0x3);
  }

  public boolean isAdv()
  {
    return isAdv(code);
  }

  public static boolean isAdv(final int code)
  {
    return ((code >> 0x4) == 0x4);
  }

  public boolean isDet()
  {
    return isDet(code);
  }

  public static boolean isPrep(final int code)
  {
    return ((code >> 0x4) == 0x5);
  }

  public boolean isPrep()
  {
    return isPrep(code);
  }

  public static boolean isDet(final int code)
  {
    return ((code >> 0x4) == 0x6);
  }

  public boolean isPro()
  {
    return isPro(code);
  }

  public static boolean isPro(final int code)
  {
    return ((code >> 0x4) == 0x7);
  }

  public boolean isName()
  {
    return isName(code);
  }

  public static boolean isName(final int code)
  {
    return (((short) code >> 0x4) == 0xB);
  }

  public boolean isPun()
  {
    return isPun(code);
  }

  public static boolean isPun(final int code)
  {
    return ((code >> 0x4) == 0xC);
  }

  public boolean isNum()
  {
    return isNum(code);
  }

  public static boolean isNum(final int code)
  {
    return (code == NUM || code == DETnum);
  }

  public boolean isMisc()
  {
    return isMisc(code);
  }

  public static boolean isMisc(final int code)
  {
    return ((code >> 0x4) == 0xF);
  }

  @Override
  public boolean equals(Object o)
  {
    if (o == this)
      return true;
    if (o instanceof String)
      return (code == Tag.code((String) o));
    if (o instanceof Tag)
      return (((Tag) o).code == code);
    if (o instanceof Integer)
      return o.equals((int) code);
    if (o instanceof Short)
      return o.equals(code);
    if (o instanceof Term)
      return (code == Tag.code((Term) o));
    return false;
  }

  @Override
  public String toString()
  {
    return Tag.label(code);
  }

  /**
   * For testing
   * 
   * @throws IOException
   */
  public static void main(String[] args)
  {
    System.out.println(CODE);
    System.out.println("test equals " + new Tag(Tag.SUB).equals(Tag.SUB));
    System.out.println(isName(NAMEplace));
    System.out.println("is NAMEplace a prefix ? " + isPrefix(NAMEplace));
    System.out.println("is NAME a prefix ? " + isPrefix(NAME));
    System.out.println("UNKNOW tag " + code("TEST"));
    System.out.println("prefix label by number category ADVint : " + prefix(Tag.ADVinter));
  }
}
