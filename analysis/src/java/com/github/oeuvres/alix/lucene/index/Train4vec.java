package com.github.oeuvres.alix.lucene.index;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Load an XML/TEI corpus in a custom Lucene index for Alix.
 */
@Command(name = "com.github.oeuvres.alix.cli.Load", description = "Load an XML/TEI corpus in a custom Lucene index for Alix.")
public class Train4vec implements Callable<Integer>
{
    @Parameters(arity = "1..*", paramLabel = "base.xml", description = "1 or more Java/XML/properties describing a document base (src file…)")
    /** configuration files */
    File[] conflist;
    /** File globs to index, populated by parsing base properties */
    ArrayList<Path> paths = new ArrayList<>();
    @Override
    public Integer call() throws Exception
    {
    for (final File conf : conflist) {
        if (conf.getCanonicalPath().endsWith("WEB-INF/web.xml") || conf.getName().startsWith(".")
                || conf.getName().startsWith("_")) {
            continue;
        }
    }
    
}
