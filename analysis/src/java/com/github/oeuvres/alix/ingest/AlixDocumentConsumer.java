package com.github.oeuvres.alix.ingest;

import org.xml.sax.SAXException;

public interface AlixDocumentConsumer
{
    void accept(AlixDocument doc) throws SAXException;
}
