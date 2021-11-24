/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package alix.fr;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import alix.util.Chain;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Jeu d’étiquettes morphosyntaxique pour le français.
 */
public enum Tag
{
  // 0x, messages internes
  NULL(0, "—", "Défaut, aucune information.") { },
  UNKNOWN(0x01, "Inconnu", "Connu comme inconnu des dictionnaires.") { },
  TEST(0x02, "Test", "Message envoyé par une étape de traitement.") { },
  
  // 1x, verbes
  VERB(0x10, "Verbe", "Verbe sémantique (hors autres catégories verbales).") { },
  VERBaux(0x11, "Auxilliaire", "Être, avoir. (verbe auxilliaire du français)") { },
  VERBaux2(0x12, "Semi‑aux.", "« Je vais faire… », aller, faire, venir de. (verbes semi-auxilliaires, faiblement sémantiques).") { },
  VERBmod(0x13, "Modaux", "Devoir, pouvoir, falloir. (verbes modaux).") { },
  VERBexpr(0x13, "V. d’expression", "Dire, répondre, s’écrier… (verbes d’expression).") { },
  VERBppass(0x18, "Part. passé", "Participe passé (tous emplois : verbal, adjectif, substantif).") { },
  VERBppres(0x19, "Part. prés.", "Participe présent (tous emplois : verbal, adjectif, substantif).") { },
  
  // 2x, substantifs
  SUB(0x20, "Substantif", "Arbre, bonheur… (“Nom commun“, espèce).") { },
  ADJ(0x21, "Adjectif", "Adjectif, en emploi qualificatif ou attribut.") { },
  /*
  SUBm(0x21, "Substantif masculin", "(futur)") { },
  SUBf(0x22, "Substantif féminin", "(futur)") { },
  */
  SUBpers(0x28, "Titulature", "Monsieur, madame, prince… (introduit des noms de personnes).") { },
  SUBplace(0x29, "Adressage", "Faubourg, rue, hôtel… (introduit des noms de lieux).") { },
  
  // 3x, entités nommées
  NAME(0x30, "Nom propre", "Kala Matah, Taj Mah de Groüpt… (nom propre inféré de la typographie, inconnu des dictionnaires).") { },
  NAMEpers(0x31, "Personne", "Victor Hugo, monsieur A… (Nom de de personne reconnu par dictionnaire ou inféré d’une titulature).") { },
  NAMEpersm(0x32, "Prénom m.", "Charles, Jean… (prénom masculin non ambigu, dictionnaire).") { },
  NAMEpersf(0x33, "Prénom f.", "Marie, Jeanne… (prénom féminin non ambigu, dictionnaire).") { },
  NAMEplace(0x34, "Lieu", "Paris, Allemagne… (nom de lieu, dictionnaire).") { },
  NAMEorg(0x35, "Organisation", "l’Église, l’État, P.S.… (nom d’organisation, dictionnaire).") { },
  NAMEpeople(0x36, "Peuple", " (nom de peuple, dictionnaire).") { },
  NAMEevent(0x37, "Événement", "La Révolution, XIIe siècle… (nom d’événement, dictionnaire).") { },
  NAMEauthor(0x38, "Auteur", "Hugo, Racine, La Fontaine… (nom de persone auteur, dictionnaire).") { },
  NAMEfict(0x39, "Personnage", "Rodogune, Chicot… (nom de personnage fictif, dictionnaire).") { },
  // NAMEtitle(0x3A, "Titre", " Titre d’œuvre (dictionnaire)") { },
  NAMEgod(0x3F, "Divinité", "Dieu, Cupidon… (noms de divinité, dictionnaire).") { },
  
  // 5x, Adverbes
  ADV(0x50, "Adverbe", "Adverbe significatif, souvent en adjectif+ment.") { },
  ADVneg(0x51, "Adv. négation", "Ne, pas, point… (adverbe de négation).") { },
  ADVinter(0x52, "Adv. interr.", "Comment, est-ce que (adverbe interrogatif).") { },
  ADVscen(0x53, "Adv. scène", ":Ici, maintenant, derrière… (adverbe spacio-temporel).") { },
  ADVasp(0x54, "Adv. aspect", "Toujours, souvent… (adverbe de temps, d’aspect).") { },
  ADVdeg(0x55, "Adv. degré", "Plus, presque, très… (adverbe d’intensité)") { },
  ADVmod(0x56, "Adv. mode", "Probablement, peut-être… (adverbe de modalité)") { },

  // 6x, déterminants
  DET(0x60, "Déterminant", "Déterminant autre que dans les autres catégories.") { },
  DETart(0x61, "Article", "Le, la, un, des… (articles définis et indéfinis, singulier et pluriel).") { },
  DETprep(0x62, "Dét. prép.", "Du, au… (“de le”, “à les”, déterminant prépositionnel).") { },
  DETnum(0x63, "Dét. num.", "Deux, trois… (déterminant numéral).") { },
  DETindef(0x6A, "Dét. indéf.", "Tout, tous quelques… (déterminant indéfini).") { },
  DETinter(0x6B, "Dét. inter.", "Quel, quelles… (déterminant interrogatif).") { },
  DETdem(0x6C, "Dét. dém.", "Ce, cette, ces… (déterminant démonstratif).") { },
  DETposs(0x6D, "Dét. poss.", "Son, ma, leurs… (déterminant possessif).") { },
  
  // 7x, pronoms
  PRO(0x70, "Pronom", "Pronom hors catégories particulières.") { },
  PROpers(0x71, "Pron. pers.", "Il, se, je, moi, nous… (pronom personnel).") { },
  PROindef(0x7A, "Pron. indéf", "Y, rien, tout… (pronom indéfini).") { },
  PROdem(0x7C, "Pron. dém.", "C’, ça, cela… (pronom démonstratif).") { },
  PROposs(0x7D, "Pron. poss.", "Le mien, la sienne… (pronom possessif).") { },
  
  // 8x, connecteurs
  CONN(0x80, "Connecteur", "Mot invariable de connection hors autres catégories.") { },
  CONJcoord(0x81, "Conj. coord.", "Et, mais, ou… (conjonction de coordination).") { },
  CONJsub(0x82, "Conj. subord.", "Comme, si, parce que… (conjonction de subordination).") { },
  ADVconj(0x83, "Adv. conj.", "Cependant, désormais… (adverbe de connexion).") { },
  PREP(0x88, "Préposition", "De, dans, par…") { },

  
  // Ax, Numéraux divers
  NUM(0xA0, "Numéral", "3, milliers, centième… (nombre quantifiant).") { },
  NUMno(0xA1, "Numéro", "1er, second… (nombre non quantifiant, code, “ordinal”…).") { },
  REF(0xA2, "Référence", "p. 50, f. 2… (un numéro utilisé comme référence, comme une page, une note…") { },
  MATH(0xA3, "Math", "+, -, /… (opérateur mathématique).") { },
  NUMunit(0xA4, "Unités", "Cm, mm, kg… (unités métriques).") { },
  
  // Cx, punctuations
  PUN(0xC0, "Ponctuation", "Ponctuations divers hors catégories spéciales.") { },
  PUNdiv(0xC1, "Structure", "§ = section, ¶ = paragraphe (structure interprétée d’un balisage).") { },
  PUNsent(0xC2, "Phrase", ". ? ! (ponctuation de phrase).") { },
  PUNcl(0xC3, "Clause", ", ; (Séparateur de syntagme).") { },
  PUNxml(0xCF, "Balise", "<nom attribut=\"valeur\">, balise XML.") { },
  
  // Fx, divers
  MISC(0xF0, "Divers", "") { },
  ABBR(0xF1, "Abréviation", "") { },
  EXCL(0xF2, "Exclamation", "Ho, Ô, haha… (interjections)") { },
  PARTdem(0xFC, "Part. dém.", "-ci, -là (particule démonstrative)") { },
  
  ;
  /** Logger */
  static Logger LOGGER = Logger.getLogger(Tag.class.getName());
  /** A structured bit flag between 0-255  */
  final public int flag;
  /** The first hexa digit, used as a type grouping */
  final public int parent;
  /** A french label for humans */
  final public String label;
  /** A line of explanation */
  final public String desc;
  /** Constructor */
  Tag(final int flag, final String label, final String desc) {
    this.flag = flag;
    this.label = label;
    this.desc = desc;
    this.parent = flag & 0xF0;
  }
  private static final Tag[] byFlag = new Tag[256];
  private static final Map<String, Integer> byName = new HashMap<String, Integer>();
  static {
    for (Tag tag : Tag.values()) {
      byFlag[tag.flag] = tag;
      byName.put(tag.toString(), tag.flag);
    }
  }
  
  public boolean sameParent(final int flag)
  {
    return ((flag & 0xF0) == parent);
  }

  static public Tag parent(final int flag)
  {
    Tag ret = byFlag[flag & 0xF0];
    if (ret == null) return UNKNOWN;
    return ret;
  }
  
  
  
  /**
   * Return the String value of the tag.
   * 
   * @return
   */
  public String label()
  {
    return label;
  }
  
  /**
   * Return the String description of the tag.
   * 
   * @return
   */
  public String desc()
  {
    return desc;
  }

  /**
   * Return the String description of the tag.
   * 
   * @return
   */
  public int flag()
  {
    return flag;
  }

  
  public static int code(final Chain label)
  {
    @SuppressWarnings("unlikely-arg-type")
    Integer ret = byName.get(label);
    if (ret == null) {
      LOGGER.log(Level.FINEST, "[Alix] unknown tag:" + label);
      return UNKNOWN.flag;
    }
    return ret;
  }

  public static int code(final String label)
  {
    Integer ret = byName.get(label);
    if (ret == null)
      return UNKNOWN.flag;
    return ret;
  }

  public static Tag tag(int flag)
  {
    // the int may be used as a more complex bit flag
    flag = flag & 0xFF;
    return byFlag[flag];
  }
  
  
  public static String name(int flag)
  {
    // the int may be used as a more complex bit flag
    flag = flag & 0xFF;
    Tag tag = byFlag[flag];
    if (tag == null) return null;
    return tag.toString();
  }

  public static String label(int flag)
  {
    flag = flag & 0xFF;
    Tag tag = byFlag[flag];
    if (tag == null) return null;
    return tag.label;
  }





  /**
   * A filter for different pos code, implemented as a boolean vector.
   */
  public static class TagFilter
  {
    /** A boolean vector is a bit more efficient than a bitSet, and is not heavy here. */
    boolean[] rule = new boolean[256];
    boolean noStop;
    boolean locutions;
    
    
    
    public boolean accept(int flag) {
      return rule[flag];
    }
    
    public TagFilter clear(final Tag tag) {
      return clear(tag.flag);
    }

    
    public TagFilter clear(final int flag) {
      rule[flag] = false;
      return this;
    }
    
    public TagFilter clearAll() {
      rule = new boolean[256];
      noStop = false;
      return this;
    }
    public TagFilter clearGroup(final Tag tag) {
      return clearGroup(tag.flag);
    }
    
    public TagFilter clearGroup(int flag) {
      flag = flag & 0xF0;
      int lim = flag +16;
      for (; flag < lim; flag++) rule[flag] = false;
      return this;
    }
    public boolean noStop() {
      return noStop;
    }

    public TagFilter noStop(boolean value) {
      noStop = value;
      return this;
    }

    public boolean locutions() {
      return locutions;
    }

    public TagFilter locutions(boolean value) {
      locutions = value;
      return this;
    }

    public TagFilter set(Tag tag) {
      return set(tag.flag);
    }

    public TagFilter set(final int flag) {
      rule[flag] = true;
      return this;
    }

    public TagFilter setAll() {
      Arrays.fill(rule, true);
      noStop = false;
      return this;
    }
    public TagFilter setGroup(Tag tag) {
      return setGroup(tag.flag);
    }
    
    public TagFilter setGroup(int flag) {
      flag = flag & 0xF0;
      int lim = flag +16;
      // System.out.println(String.format("0x%02X", tag));
      for (; flag < lim; flag++) rule[flag] = true;
      return this;
    }

    @Override
    public String toString()
    {
      StringBuilder sb = new StringBuilder();
      for (int tag = 0; tag < 256; tag++) {
        if ((tag % 16) == 0) sb.append(Tag.label(tag)).append("\t");
        if (rule[tag]) sb.append(1);
        else sb.append('·');
        if ((tag % 16) == 15) sb.append("\n");
      }
      return sb.toString();
    }
  }
}
