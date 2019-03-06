package org.apache.lucene.search.vectorhighlight;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.vectorhighlight.FastVectorHighlighter;
import org.apache.lucene.search.vectorhighlight.FieldQuery;

/**
 * Send a concordance from a lucene doc and
 * @author fred
 *
 */
public class Conc
{
    FieldQuery fieldQuery;
    public Conc(IndexReader reader, Query query, String fieldName) throws IOException
    {
        // transform a simple query object to go through termvector 
        fieldQuery = new FieldQuery(query, reader, true, true);

        Set<String> termSet = fieldQuery.getTermSet(fieldName);
        // just return to make null snippet if un-matched fieldName specified when fieldMatch == true
        if( termSet == null ) return;
    }
    
    public void print(int docid, PrintWriter out) 
    {
        
    }
}
