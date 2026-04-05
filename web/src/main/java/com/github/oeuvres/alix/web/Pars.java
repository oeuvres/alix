package com.github.oeuvres.alix.web;

public class Pars
{
    private Pars() {}
    public static final String CTX             = "ctx";
    public static final int    CTX_DEFAULT     = 10;
    public static final int[]  CTX_RANGE       = {0, 30};
    public static final String ROWS            = "rows";
    public static final int    ROWS_DEFAULT    = 2000;
    public static final int[]  ROWS_RANGE      = {1, 2000};
    public static final String DOCS            = "docs";
    public static final int    DOCS_DEFAULT    = 100;
    public static final int[]  DOCS_RANGE      = {1, 500};
    public static final String DOCLINE         = "docline";
    public static final String END             = "end";
    public static final String F               = "f";
    public static final String FROM            = "from";
    public static final String IDF_EXP         = "idfexp";
    public static final Double IDF_EXP_DEFAULT = 1.0;
    public static final String Q               = "q";
    public static final String SLOP            = "slop";
    public static final int    SLOP_DEFAULT    = 20;
    public static final int[]  SLOP_RANGE      = {0, 100};
    public static final String SORTED          = "sorted";
    public static final String SPANS           = "spans";
    public static final int    SPANS_DEFAULT   = 10;
    public static final int[]  SPANS_RANGE     = {-1, 100};
    public static final String START           = "start";
    public static final String YEAR            = "year";
}
