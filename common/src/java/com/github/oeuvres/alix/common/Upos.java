package com.github.oeuvres.alix.common;

/**
 * Set of flags used during an analysis process (pos tagging, lemmatization).
 * Universal POS tags always used when possible
 * https://universaldependencies.org/u/pos/
 * Some other useful technical tags for semantic analysis
 */
public enum Upos implements Tag
{
    // 0x, possible flags 
    
    /** No information */
    TOKEN(0x00),
    /** Known as unknown from dictionaries */
    UNKNOWN(0x01),
    /** Message sent by a process */
    TEST(0x02),
    /** XML tag */
    XML(0x03),
    /** Stop word */
    STOP(0x04),
    /** Non stop word */
    NOSTOP(0x05),
    /** Locution (maybe substantive, conjunction…) */
    LOC(0x06),
    
    /** UDPOS, Punctuation */
    PUNCT(0x08),
    /** §, section punctuation, inferred from XML */
    PUNCTsection(0x09),
    /** ¶, paragraph punctuation, inferred from XML */
    PUNCTpara(0x0A),
    /** [.?!…] sentence punctuation */
    PUNCTsent(0x0B),
    /** [,;…] clause punctuation */
    PUNCTclause(0x0C),
    
    // 1x, verbs
    /** https://universaldependencies.org/u/pos/VERB.html */
    VERB(0x10),
    /** https://universaldependencies.org/u/pos/AUX_.html */
    AUX(0x11),
    /** https://universaldependencies.org/u/feat/VerbForm.html#inf-infinitive */
    VERBinf(0x12),
    /** https://universaldependencies.org/u/feat/VerbForm.html#part-participle-verbal-adjective */
    VERBpartpast(0x13),
    /** https://universaldependencies.org/u/feat/VerbForm.html#part-participle-verbal-adjective */
    VERBpartpres(0x14),

    // 2x, substantifs
    /** UDPOS, substantive */
    NOUN(0x20),
    /** Person title */
    // SUBpers(0x28, "Titulature", "Monsieur, madame, prince… (introduit des noms de personnes)."),
    /** Adress substantive */
    // SUBplace(0x29, "Adressage", "Faubourg, rue, hôtel… (introduit des noms de lieux)."),

    /** UDPOS, adjective */
    ADJ(0x30),

    /** UDPOS Proper name */
    PROPN(0x40),
    /** Personal name */
    PROPNprs(0x41),
    /** Masculine given name */
    PROPNgivmasc(0x42),
    /** Feminine given name */
    PROPNgivfem(0x43),
    /** Place name */
    PROPNgeo(0x44),
    // non UD feats
    /** Organisation name */
    PROPNorg(0x45),
    /** Event name */
    PROPNevent(0x47),
    /** Author name */
    PROPNauthor(0x48),
    /** Fiction character name */
    PROPNfict(0x49),
    /** Opus title name */
    PROPNtitle(0x4A),
    /** Natural species */
    PROPNspec(0x4B),
    /** People name */
    PROPNpeople(0x4E),
    /** God name */
    PROPNgod(0x4F),

    // 5x, Adverbes
    /** https://universaldependencies.org/u/pos/ADV.html */
    ADV(0x50),
    /** Interrogative adverb */
    ADVint(0x51),
    /** Negation adverb */
    ADVneg(0x52),
    /** https://universaldependencies.org/u/pos/PART.html fr: n’, ne */
    PART(0x53),
    /** Situation (space-time) adverb */
    ADVsit(0x58),
    /** Aspect adverb */
    ADVasp(0x59),
    /** Gradation adverb */
    ADVdeg(0x5A),

    /** https://universaldependencies.org/u/pos/DET.html */
    DET(0x60),
    /** Article */
    DETart(0x61),
    /** Demonstrative determiner */
    DETdem(0x62),
    /** Determiner indefinite, ex fr: tous, quelques… */
    DETind(0x63),
    /** Interrogative determiner */
    DETint(0x64),
    /** Negative determiner, ex fr: nul, aucun*/
    DETneg(0x65),
    /** Personal determiner, usually possessive */
    DETprs(0x66),
    /** Prepositional determiner, UD ADP+DET, fr: du, aux */
    ADP_DET(0x67),

    // 7x, pronouns
    /** https://universaldependencies.org/u/pos/PRON.html */
    PRON(0x70),
    /** Demonstrative pronoun */
    PRONdem(0x71),
    /** Indefinite pronoun */
    PRONind(0x72),
    /** Interrogative pronoun */
    PRONint(0x73),
    /** Negative pronoun */
    PRONneg(0x74),
    /** Personal pronoun */
    PRONprs(0x75),
    /** Relative pronoun */
    PRONrel(0x76),
    /** UD ADP+PRON, ex fr: duquel, auquel… */
    ADP_PRON(0x89),

    // 8x, connectors
    /** https://universaldependencies.org/u/pos/ADP.html “ad”position (pre or post in some languages) */
    ADP(0x80),
    /** https://universaldependencies.org/u/pos/CCONJ.html */
    CCONJ(0x81),
    /** https://universaldependencies.org/u/pos/SCONJ.html */
    SCONJ(0x82),
    
    /** Adverbial conjunction */
    // ADVconj(0x88, "Adv. conj.", "Cependant, désormais… (adverbe de connexion)."),
    
    // Ax, numerals
    /** https://universaldependencies.org/u/pos/NUM.html */
    NUM(0xA0),
    /** Ordinal */
    NUMord(0xA1),
    /** https://universaldependencies.org/u/pos/SYM.html  */
    SYM(0xA8),
    /** [0-9\-] numbers */
    DIGIT(0x07),
    /** Math operator */
    MATH(0xA3),
    /** Physical unit (cm, kg…) */
    UNIT(0xA4),
    /** A number reference, for example p. 50 */
    REF(0xA2),

    // misc
    /** https://universaldependencies.org/u/pos/X.html */
    X(0xF0),
    /** https://universaldependencies.org/u/pos/INTJ.html */
    INTJ(0xF1),
    /** Abbreviation before resolution to a pos */
    ABBR(0xF8),
    /** Grammatical word, before resolution */
    MG(0xF9)
    ;
    
    
    public final int code;
    static final Index index = new Index(0, 0xFF);
    static
    {
        for (Upos tag : Upos.values()) index.add(tag.code, tag);
    }

    private Upos(final int code)
    {
        this.code = code;
    }
    public boolean isPun(final int code)
    {
        return (code == PUNCT.code 
          || code == PUNCTsection.code 
          || code == PUNCTpara.code 
          || code == PUNCTsent.code 
          || code == PUNCTclause.code
        );
    }
    
    static public String name(final int code)
    {
        Tag tag = index.get(code);
        if (tag == null) return null;
        return tag.name();
    }

    @Override
    public int code()
    {
        return code;
    }
    
    @Override
    public int code(final String name)
    {
        return valueOf(name).code;
    }
}
