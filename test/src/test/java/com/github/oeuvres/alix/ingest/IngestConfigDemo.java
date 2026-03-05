package com.github.oeuvres.alix.ingest;

import com.github.oeuvres.alix.util.Report;

import java.nio.file.Path;

/**
 * Minimal demo: load an ingest config and print what it resolved.
 * This is the right place for a main(), not in IngestConfig.
 */
public final class IngestConfigDemo
{
    private IngestConfigDemo() { }
    
    public static void main(String[] args) throws Exception
    {
        Report rep = new Report.ReportConsole();
        Path cfg = Path.of("D:\\code\\piaget_labo\\install\\alix-piaget.xml");
        IngestConfig ic = IngestConfig.load(cfg, rep);
        rep.info(ic.toString());
    }
}