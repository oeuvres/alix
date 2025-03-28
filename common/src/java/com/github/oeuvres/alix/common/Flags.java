package com.github.oeuvres.alix.common;

/**
 * List of flags inferred by an analyzer without dictionaries or tagger,
 * on alphabetic scripts.
 */
public enum Flags implements Tag
{
    // 0x, possible flags 
    
    /** No information */
    NULL(0x00),
    /** Known as unknown from dicitonaries */
    UNKNOWN(0x01),
    /** Message send by a process */
    TEST(0x02),
    /** XML tag */
    XML(0x03),
    /** Stop word */
    STOP(0x04),
    /** Non stop word */
    NOSTOP(0x05),
    /** Locution  (maybe substantive, conjunction…) */
    LOC(0x06),
    /** [0-9\-] numbers */
    NUM(0x07),
    /** Punctuation, other than below */
    PUN(0x08),
    /** §, section punctuation, inferred from XML */
    PUNsection(0x09),
    /** ¶, paragraph punctuation, inferred from XML */
    PUNpara(0x0A),
    /** [.?!…] sentence punctuation */
    PUNsent(0x0B),
    /** [,;…] clause punctuation */
    PUNclause(0x0C),
    ;
    public final int code;
    static final Index index = new Index(0, 15);
    static
    {
        for (Flags tag : Flags.values()) index.add(tag.code, tag);
    }

    private Flags(final int code)
    {
        this.code = code;
    }
    public boolean isPun(final int code)
    {
        return (code == 0x08 || code == 0x09 || code == 0x0A || code == 0x0B || code == 0x0C);
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
