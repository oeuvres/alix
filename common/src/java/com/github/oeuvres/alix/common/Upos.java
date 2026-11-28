package com.github.oeuvres.alix.common;

import java.util.HashMap;
import java.util.Map;

/**
 * Universal Dependencies parts of speech and Alix-specific refinements.
 *
 * <p>Standard UPOS categories are roots of the linguistic hierarchy. A
 * refinement declares its immediate parent and inherits its standard UPOS
 * base. Technical analysis categories have no standard UPOS base.</p>
 *
 * <p>Integer identifiers are enum ordinals, local to this version of the
 * vocabulary. {@link #TOKEN} is ordinal zero, representing an unassigned
 * annotation. Use {@link #name()} rather than {@link #ordinal()} for persistent
 * data and external interfaces. Neither the order nor the names of categories
 * imply a parent relationship.</p>
 *
 * <p>Annotation certainty and normalization policy are not properties of the
 * grammatical hierarchy.</p>
 *
 * @see <a href="https://universaldependencies.org/u/pos/">Universal POS tags</a>
 */
public enum Upos
{
    /** No annotation has been assigned. */
    TOKEN(null),
    /** Form explicitly identified as unknown. */
    UNKNOWN(null),
    /** XML markup, not a linguistic token. */
    XML(null),

    /** Adjective. */
    ADJ,
    /** Ordinal adjective, including a canonical form such as 3e. */
    ADJord(ADJ),

    /** Adposition. */
    ADP,
    /** Contracted adposition and determiner, e.g. du, aux. */
    ADP_DET(ADP),
    /** Contracted adposition and pronoun, e.g. duquel, auquel. */
    ADP_PRON(ADP),

    /** Adverb. */
    ADV,
    /** Aspectual adverb. */
    ADVasp(ADV),
    /** Degree adverb. */
    ADVdeg(ADV),
    /** Interrogative adverb. */
    ADVint(ADV),
    /** Negative adverb. */
    ADVneg(ADV),
    /** Spatial or temporal situation adverb. */
    ADVsit(ADV),

    /** Auxiliary. */
    AUX,
    /** Coordinating conjunction. */
    CCONJ,

    /** Determiner. */
    DET,
    /** Article. */
    DETart(DET),
    /** Demonstrative determiner. */
    DETdem(DET),
    /** Indefinite determiner. */
    DETind(DET),
    /** Interrogative determiner. */
    DETint(DET),
    /** Negative determiner. */
    DETneg(DET),
    /** Possessive determiner. */
    DETprs(DET),

    /** Interjection. */
    INTJ,

    /** Common noun. */
    NOUN,
    /** Aggregated age expression, e.g. 8ans. */
    NOUNage(NOUN),
    /** Aggregated quantity expression, e.g. 25kg or 25%. */
    NOUNqty(NOUN),
    /** Lexical or alphabetic measurement unit, e.g. kilogramme or kg. */
    NOUNunit(NOUN),
    /** Species */
    NOUNbio(NOUN),

    /** Numeral. */
    NUM,
    /** Cardinal number with a resolved canonical numerical value. */
    NUMcard(NUM),
    /** Numbered reference, e.g. chapitre IV. */
    NUMref(NUM),
    
    /** Particle. */
    PART,

    /** Pronoun. */
    PRON,
    /** Demonstrative pronoun. */
    PRONdem(PRON),
    /** Indefinite pronoun. */
    PRONind(PRON),
    /** Interrogative pronoun. */
    PRONint(PRON),
    /** Negative pronoun. */
    PRONneg(PRON),
    /** Personal pronoun. */
    PRONprs(PRON),
    /** Relative pronoun. */
    PRONrel(PRON),

    /** Proper noun. */
    PROPN,
    /** Author name. */
    PROPNauthor(PROPN),
    /** Named event. */
    PROPNevent(PROPN),
    /** Fictional character name. */
    PROPNfict(PROPN),
    /** Geographical name. */
    PROPNplace(PROPN),
    /** Feminine given name. */
    PROPNgivfem(PROPN),
    /** Masculine given name. */
    PROPNgivmasc(PROPN),
    /** Deity name. */
    PROPNgod(PROPN),
    /** Organization name. */
    PROPNorg(PROPN),
    /** People name. */
    PROPNpeople(PROPN),
    /** Personal name. */
    PROPNpers(PROPN),
    /** Natural species name. */
    PROPNspec(PROPN),
    /** Title of a work. */
    PROPNtitle(PROPN),

    /** Punctuation. */
    PUNCT,
    /** Clause punctuation. */
    PUNCTclause(PUNCT),
    /** Paragraph boundary. */
    PUNCTpara(PUNCT),
    /** Section boundary. */
    PUNCTsection(PUNCT),
    /** Sentence punctuation. */
    PUNCTsent(PUNCT),
    /** Structural punctuation without further interpretation. */
    PUNCTstruct(PUNCT),

    /** Subordinating conjunction. */
    SCONJ,

    /** Symbol. */
    SYM,
    /** Mathematical operator or relation, e.g. multiplication or equality. */
    SYMmath(SYM),
    /** Symbolic unit, e.g. %, degree or currency symbol. */
    SYMunit(SYM),

    /** Verb. */
    VERB,
    /** Infinitive. */
    VERBinf(VERB),
    /** Past participle. */
    VERBpartpast(VERB),
    /** Present participle. */
    VERBpartpres(VERB),

    /** Other linguistic category (standard UPOS X). */
    X;

    /** Cached ordinal lookup, avoiding repeated copies from values(). */
    private static final Upos[] VALUES = values();
    /** Exact, case-sensitive lookup by tag name. */
    private static final Map<String, Upos> BY_NAME;

    static {
        final Map<String, Upos> names = new HashMap<>();
        for (final Upos tag : VALUES) {
            names.put(tag.name(), tag);
        }
        BY_NAME = Map.copyOf(names);
    }

    /** Immediate parent, or null for a root or a technical category. */
    private final Upos parent;
    /** Standard UPOS projection, or null for a technical category. */
    private final Upos base;

    /** Constructs a standard UPOS root. */
    private Upos()
    {
        this.parent = null;
        this.base = this;
    }

    /**
     * Constructs a refinement or a technical category.
     *
     * @param parent immediate parent, or null for a technical category
     */
    private Upos(final Upos parent)
    {
        this.parent = parent;
        this.base = parent == null ? null : parent.base;
    }

    /**
     * Returns the standard UPOS projection. For aggregated expressions and
     * contractions this is a single-category convention, not a full UD analysis.
     *
     * @return standard UPOS, or null for a technical category
     */
    public Upos base()
    {
        return base;
    }

    /**
     * Resolves an internal ordinal without allocating.
     *
     * @param ordinal internal tag identifier
     * @return corresponding tag, or null if out of range
     */
    public static Upos get(final int ordinal)
    {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }

    /**
     * Resolves an exact, case-sensitive tag name without throwing on an unknown name.
     *
     * @param name tag name, possibly null
     * @return corresponding tag, or null if unknown
     */
    public static Upos get(final String name)
    {
        return name == null ? null : BY_NAME.get(name);
    }

    /**
     * Returns the immediate parent in the linguistic hierarchy.
     *
     * @return parent, or null for a root or a technical category
     */
    public Upos parent()
    {
        return parent;
    }
}
