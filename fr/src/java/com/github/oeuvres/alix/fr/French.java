package com.github.oeuvres.alix.fr;

public enum French
{
    OPENNLP_POS("opennlp-fr-ud-gsd-pos-1.3-2.5.4.bin"),
    ;
    final public String resource;
    private French(final String resource) {
        this.resource = resource;
    }
}
