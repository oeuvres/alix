package alix.fr.dic;

import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.HashMap;
import alix.util.Chain;

/**
 * Jeu d’étiquettes morphosyntaxique pour le français.
 * Un short pèsera moins qu'un objet Enum.
 * 
 * @author glorieux
 */
public final class Tag
{
  /** Valeur par défaut, aucune information */
  public final static int NULL = 0;
  /** Connu comme inconnu (selon les dictionnaires) */
  public final static int UNKNOWN = 1;
  /** Verbe, qui n'est pas l'une des catégories ci-dessous */
  public final static int VERB = 0x10;
  /** Auxilliaire, conjugaison d’être et avoir */
  public final static int VERBaux = 0x11;
  /** Participe passé, peut avoir un emploi adjectif, voire substantif */
  public final static int VERBppass = 0x12;
  /** Participe présent, a souvent un emploi adjectif ou substantif */
  public final static int VERBppres = 0x13;
  /**
   * Verbe support, fréquent mais peut significatif, comme aller (je vais faire)
   */
  public final static int VERBsup = 0x15;
  /** Substantif */
  public final static int SUB = 0x20;
  /** Substantif masculin (pas encore renseigné) */
  public final static int SUBm = 0x21;
  /** Substantif féminin (pas encore renseigné) */
  public final static int SUBf = 0x22;
  /** Catégorie un peu adhoc pour monsieur, madame, prince… */
  public final static int SUBtit = 0x28;
  /** Adjectif */
  public final static int ADJ = 0x30;
  /** Adverbe */
  public final static int ADV = 0x40;
  /** Adverbe de négation : ne, pas, point… */
  public final static int ADVneg = 0x41;
  /** Adverbe de lieu */
  public final static int ADVplace = 0x42;
  /** Adverbe de temps */
  public final static int ADVtemp = 0x43;
  /** Adverbe de quantité */
  public final static int ADVquant = 0x44;
  /** Adverbe indéfini : aussi, même… */
  public final static int ADVindef = 0x4A;
  /** Adverbe interrogatif : est-ce que, comment… */
  public final static int ADVinter = 0x4B;
  /** Préposition */
  public final static int PREP = 0x50;
  /** Déterminant */
  public final static int DET = 0x60;
  /** Déterminant article : le, la, un, des… */
  public final static int DETart = 0x61;
  /** Déterminant prépositionnel : du, au (?? non comptable ?) */
  public final static int DETprep = 0x62;
  /** Déterminant numéral : deux, trois */
  public final static int DETnum = 0x63;
  /** Déterminant indéfini : tout, tous, quelques… */
  public final static int DETindef = 0x6A;
  /** Déterminant interrogatif : quel, quelles… */
  public final static int DETinter = 0x6B;
  /** Déterminant démonstratif : ce, cette, ces… */
  public final static int DETdem = 0x6C;
  /** Déterminant possessif : son, mas, leurs… */
  public final static int DETposs = 0x6D;
  /** Pronom */
  public final static int PRO = 0x70;
  /** Pronom personnel : il, je, se, me… */
  public final static int PROpers = 0x71;
  /** Pronom relatif : qui, que, où… */
  public final static int PROrel = 0x72;
  /** Pronom indéfini : y, rien, tout… */
  public final static int PROindef = 0x7A;
  /** Pronom interrogatif : y, rien, tout… */
  public final static int PROint = 0x7B;
  /** Pronom démonstratif : c', ça, cela… */
  public final static int PROdem = 0x7C;
  /** Pronom possessif : le mien, la sienne… */
  public final static int PROposs = 0x7D;
  /** Conjonction */
  public final static int CONJ = 0x80;
  /** Conjonction de coordination : et, mais, ou… */
  public final static int CONJcoord = 0x81;
  /** Conjonction de subordination : comme, si, parce que… */
  public final static int CONJsubord = 0x82;
  /** Nom propre */
  public final static int NAME = 0xB0;
  /** Nom de personne */
  public final static int NAMEpers = 0xB1;
  /** Prénom masculin */
  public final static int NAMEpersm = 0xB2;
  /** Prénom féminin */
  public final static int NAMEpersf = 0xB3;
  /** Nom de lieu */
  public final static int NAMEplace = 0xB4;
  /** Nom d’organisation */
  public final static int NAMEorg = 0xB5;
  /** Nom de peuple */
  public final static int NAMEpeople = 0xB6;
  /** Nom d’événement : la Révolution, la Seconde Guerre mondiale… */
  public final static int NAMEevent = 0xB7;
  /** Nom de personne auteur */
  public final static int NAMEauthor = 0xB8;
  /** Nom de personnage fictif */
  public final static int NAMEfict = 0xB9;
  /** Titre d’œuvre */
  public final static int NAMEtitle = 0xBA;
  /** Animal : Pégase… */
  public final static int NAMEanimal = 0xBD;
  /** Demi-humain : Hercule… */
  public final static int NAMEdemhum = 0xBE;
  /** Noms de dieux : Dieu, Vénus… */
  public final static int NAMEgod = 0xBF;
  /** Ponctuation */
  public final static int PUN = 0xC0;
  /** Ponctuation de phrase : . ? ! */
  public final static int PUNsent = 0xC1;
  /** Ponctuation de clause : , ; ( */
  public final static int PUNcl = 0xC2;
  /** Ponctuation structurante : ¶, § */
  public final static int PUNdiv = 0xC3;
  /** Catégories diverses */
  public final static int MISC = 0xF0;
  /** Abréviation (encore non résolue) */
  public final static int ABBR = 0xF1;
  /** Numéro */
  public final static int NUM = 0xF2;
  /** Exclamation */
  public final static int EXCL = 0xF3;
  /** Mathematical operator */
  public final static int MATH = 0xF4;
  /** Particules démonstratives -ci, -là */
  public final static int PARTdem = 0xFC;
  /** Category */
  private int code;
  /** Dictionnaire des codes par nom */
  public static final HashMap<String, Integer> CODE = new HashMap<String, Integer>();
  /** Dictionnaire des noms par code */
  public static final HashMap<Integer, String> LABEL = new HashMap<Integer, String>();
  // loop on the static fields declared to populate the HashMaps
  static {
    String name;
    int value = 0;
    Field[] declaredFields = Tag.class.getDeclaredFields();
    for (Field field : declaredFields) {
      if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()))
        continue;
      name = field.getName();
      if (name.equals("CODE") || name.contains("LABEL"))
        continue;
      try {
        value = field.getInt(null);
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
  public int code()
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
    this.code = code;
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

  public static int code(final Chain label)
  {
    Integer ret = CODE.get(label);
    if (ret == null)
      return UNKNOWN;
    return ret;
  }

  public static int code(final String label)
  {
    Integer ret = CODE.get(label);
    if (ret == null)
      return UNKNOWN;
    return ret;
  }

  public static String label(final int code)
  {
    if (code == 0) return "";
    String ret = LABEL.get(code);
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
    return ((code >> 0x4) == 0x3);
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
    return ((code >> 0x4) == 0xB);
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
    if (o instanceof Chain)
      return (code == Tag.code((Chain) o));
    return false;
  }

  @Override
  public String toString()
  {
    return Tag.label(code);
  }

  public static class TagFilter
  {
    final BitSet bits = new BitSet(256);
    public void clear() {
      bits.clear();
    }
    public TagFilter setName() {
      return setGroup(NAME);
    }
    public TagFilter setAdj() {
      return setGroup(ADJ);
    }
    public TagFilter set(final int tag) {
      bits.set(tag);
      return setGroup(ADJ);
    }

    public TagFilter setGroup(int tag) {
      tag = tag & 0xF0;
      bits.set(tag, tag + 16, true);
      return this;
    }
    public boolean accept(int tag) {
      return bits.get(tag);
    }
  }
}
