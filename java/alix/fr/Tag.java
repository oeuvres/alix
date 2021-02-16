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

import alix.lucene.search.FieldMatrix;
import alix.util.Chain;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Jeu d’étiquettes morphosyntaxique pour le français.
 */
public enum Tag
{
  NULL(0, "—", "Défaut, aucune information") {
  },
  UNKNOWN(0x01, "Inconnu", "Connu comme inconnu des dictionnaires") {
  },
  TEST(0x02, "Test", "Message envoyé par une étape de traitement") {
  },
  VERB(0x10, "Verbe", "Verbe sémantique (hors autres catégories verbales)") {
  },
  VERBaux(0x11, "Auxilliaire", "Être, avoir.") {
  },
  VERBaux2(0x12, "Semi-auxiliaire", "Aller, faire, venir de. « Je vais faire… »") {
  },
  VERBmod(0x13, "Modaux", "Devoir, pouvoir, falloir.") {
  },
  VERBppass(0x18, "Participe passé", "Un participe passé peut avoir un emploi adjectif ou substantif") {
  },
  VERBppres(0x19, "Participe présent", "Un participe présent peut avoir un emploi adjectif ou substantif") {
  },
  SUB(0x20, "Substantif", "“Nom commun“, espèce") {
  },
  /*
  SUBm(0x21, "Substantif masculin", "(futur)") {
  },
  SUBf(0x22, "Substantif féminin", "(futur)") {
  },
  */
  SUBtit(0x28, "Titulature", "Monsieur, madame, prince… (pour les romans, en cours)") {
  },
  NAME(0x30, "Nom propre", "Nom propre inféré de la typographie mais pas connu d’un dictionnaire") {
  },
  NAMEpers(0x31, "Personne", "Nom de de personne reconnu par dictionnaire ou inféré") {
  },
  NAMEpersm(0x32, "Prénom masculin", "Prénom masculin non ambigu") {
  },
  NAMEpersf(0x33, "Prénom féminin", "Prénom féminin non ambigu") {
  },
  NAMEplace(0x34, "Lieu", "Nom de lieu") {
  },
  NAMEorg(0x35, "Organisation", "Nom d’organisation") {
  },
  NAMEpeople(0x36, "Peuple", "Nom de peuple") {
  },
  NAMEevent(0x37, "Événement", "Nom d’événement : la Révolution, XIIe siècle…") {
  },
  NAMEauthor(0x38, "Auteur", "Nom de persone auteur") {
  },
  NAMEfict(0x39, "Personnage", "Nom de personnage fictif") {
  },
  NAMEtitle(0x3A, "Titre", "Titre d’œuvre") {
  },
  NAMEgod(0x3F, "Divinité", "Noms de divinité") {
  },
  ADJ(0x40, "Adjectif", "Qualificatif ou attribut") {
  },
  ADV(0x41, "Adverbe", "Adverbe significatif, souvent en adjectif+ment") {
  },
  DET(0x50, "Déterminant", "Déterminant ") {
  },
  DETart(0x51, "Article", "Ex : le, la, un, des…") {
  },
  DETprep(0x52, "Déterminant prépositionnel", "Ex : du, au") {
  },
  DETnum(0x53, "Déterminant numéral", "Ex : deux, trois") {
  },
  DETindef(0x5A, "Déterminant indéfini", "tout, tous quelques…") {
  },
  DETinter(0x5B, "Déterminant interrogatif", "Ex : quel, quelles…") {
  },
  DETdem(0x5C, "Déterminant démonstratif", "Ex : ce, cette, ces…") {
  },
  DETposs(0x5D, "Déterminant possessif", "Ex : son, ma, leurs…") {
  },
  PRO(0x60, "Pronom", "Pronom hors catégories particulières") {
  },
  PROpers(0x61, "Pronom personnel", "Ex : il, se, je, me, nous…") {
  },
  PROindef(0x6A, "Pronom indéfini", "Ex : y, rien, tout…") {
  },
  PROdem(0x6C, "Pronom démonstratif", "Ex : c', ça, cela… ") {
  },
  PROposs(0x6D, "Pronom possessif", "Ex : le mien, la sienne…") {
  },
  // connecteurs
  CONN(0x70, "Connecteur", "Mot invariable de connection") {
  },
  CONJcoord(0x71, "Conjonction de coordination", "Ex : et, mais, ou…") {
  },
  CONJsub(0x72, "Conjonction de subordination", "Ex : comme, si, parce que…") {
  },
  ADVconj(0x73, "Adverbe conjonctif", "Adverbe de connexion : cependant, désormais…") {
  },
  PREP(0x78, "Préposition", "Ex : De, dans, par…") {
  },

  ADVneg(0x80, "Négation", "Ne, pas, point…") {
  },
  ADVasp(0x81, "Aspect", "Adverbe de temps, d’aspect : toujours, souvent…") {
  },
  ADVdeg(0x82, "Degré", "Adverbe d’intensité : plus, presque, très…") {
  },
  ADVmod(0x83, "Modalité", "Adverbe de modalité : probablement…") {
  },
  ADVscen(0x84, "Scénique", "Adverbe de lieu ou de temps : ici, maintenant, derrière…") {
  },
  ADVinter(0x85, "Adverbe interrogatif", "Ex : comment, est-ce que") {
  },
  
  

  
  NUM(0x90, "Numéral", "Nombres : 3, milliers, centième…") {
  },
  NUMno(0x91, "Numéro", "Ex : 1er, second…") {
  },
  REF(0x92, "Référence", "Un numéro utilisé comme référence, comme une page, une note…") {
  },
  MATH(0x93, "Math", "Opérateur mathématique") {
  },
  NUMunit(0x94, "Unités", "Ex : cm, mm, kg…") {
  },
  PUN(0xC0, "Ponctuation", "Ponctuations divers hors catégories spéciales") {
  },
  PUNdiv(0xC1, "Ponctuation structurante", "Interprétation d’un balisage : ¶ §") {
  },
  PUNsent(0xC2, "Ponctuation de phrase", ". ? !") {
  },
  PUNcl(0xC3, "Ponctuation de clause", "Séparateur de syntagme : , ; (") {
  },
  MISC(0xF0, "Divers", "") {
  },
  ABBR(0xF1, "Abréviation", "") {
  },
  EXCL(0xF2, "Exclamation", "") {
  },
  PARTdem(0xFC, "Particules démonstratives", "Ex : -ci, -là") {
  },
  
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
  
  public static int code(final Chain label)
  {
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
    
    
    
    public TagFilter clearAll() {
      rule = new boolean[256];
      noStop = false;
      return this;
    }
    public TagFilter setAll() {
      Arrays.fill(rule, true);
      noStop = false;
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

    public TagFilter set(final int tag) {
      rule[tag] = true;
      return this;
    }

    public TagFilter clear(final int tag) {
      rule[tag] = false;
      return this;
    }

    public TagFilter setGroup(Tag tag) {
      return setGroup(tag.flag);
    }
    
    public TagFilter setGroup(int tag) {
      tag = tag & 0xF0;
      int lim = tag +16;
      // System.out.println(String.format("0x%02X", tag));
      for (; tag < lim; tag++) rule[tag] = true;
      return this;
    }

    public TagFilter clearGroup(final Tag tag) {
      return clearGroup(tag.flag);
    }
    
    public TagFilter clearGroup(int tag) {
      tag = tag & 0xF0;
      int lim = tag +16;
      for (; tag < lim; tag++) rule[tag] = false;
      return this;
    }
    
    public boolean accept(int tag) {
      return rule[tag];
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
