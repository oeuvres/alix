package com.github.oeuvres.alix.fr;

public enum French
{
    OPENNLP_POS("opennlp-fr-ud-gsd-pos-1.2-2.5.0.bin"),
    ;
    final public String resource;
    private French(final String resource) {
        this.resource = resource;
    }
}
