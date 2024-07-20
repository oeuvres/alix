package com.github.oeuvres.alix.lucene.search;

import java.util.NoSuchElementException;

/**
 * A contract to loop on a list of forms, accessing to different properties,
 * with maximum memory reuse.
 */
public interface FormIterator
{
    /**
     * Possible sort order implemented.
     */
    public enum Order {
        ALPHA, DOCS, FREQ, HITS, INSERTION, OCCS, SCORE,
    }

    /**
     * Current formId
     * 
     * @return An int form identifier
     */
    public int formId();

    /**
     * Current “freq” ()
     * 
     * @return
     */
    public long freq();

    /**
     * Returns true if the iteration has more elements.
     * 
     * @return if true, next() can be called without surprise.
     */
    public boolean hasNext();

    /**
     * Inform about actual limit
     */
    public int limit();

    /**
     * Returns the next element identifier in enumeration.
     * 
     * @return A formId
     */
    public int next() throws NoSuchElementException;

    /**
     * Call reset() before enumerate elements.
     */
    public void reset();

    /**
     * Current score
     * 
     * @return current score
     */
    public double score();

    /**
     * Sort according to one order implemented. Sort before iterate.
     * 
     * @param order
     */
    public void sort(final Order order);

    /**
     * Partial sort to get a top of elements.
     * 
     * @param order An order as a possible keyword
     * @param limit
     */
    public void sort(final Order order, final int limit);

    /**
     * Returns a safe copy of the sorted ids
     * 
     * @return Sorted formId according to last sort()
     */
    public int[] sorter();
}
