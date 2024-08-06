package com.github.oeuvres.alix.lucene.search;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.text.Collator;

import com.github.oeuvres.alix.util.MI;

/**
 * A contract to loop on a list of forms, accessing to different properties.
 */
public interface FormIterator
{
    /**
     * Possible sort order. An implementation will have a default sort order.
     */
    public enum Order {
        /** Sort by lexical order with a {@link Collator}. */
        ALPHA, 
        /** Sort by total count of documents containing this form. */
        DOCS,
        /** Sort by count of occurrences in selected documents (partition, query…) containing this form. */
        FREQ,
        /** Sort by count of selected documents (partition, query…) containing this form. */
        HITS, 
        /** Sort in order of insertion, without the duplicates. */
        INSERTION,
        /** Sort by total count of all occurrences in all documents containing this form. */
        OCCS, 
        /** Sort by a calulated score according to a distribution algorithm {@link MI}. */
        SCORE,
    }

    /**
     * Current formId.
     * 
     * @return An int form identifier.
     */
    public int formId();

    /**
     * Get “freq”, amount of occurrences encountered for current form.
     * 
     * @return freq of current form.
     */
    public long freq();

    /**
     * Like {@link Iterator#hasNext()}, returns true if the iteration has more elements.
     * 
     * @return if true, next() can be called without surprise.
     */
    public boolean hasNext();

    /**
     * Get the amout of rows for this iterator.
     * Set a limit may be efficient when collecting top rows according to a sort).
     * Like SQL
     * 
     * @return amount of forms to iterate on.
     */
    public int limit();

    /**
     * Set a limit, may be efficient when collecting top rows according to a sort,
     * like SQL LIMIT.
     * 
     * @param limit max amount of rows to sort and to iterate on.
     * @return this.
     */
    public FormIterator limit(int limit);

    /**
     * Like {@link Iterator#next()}, returns the next element identifier in enumeration.
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
     * Total count of distinct forms in this cursor, but all may
     * be not available on iteration, if a {@link #limit(int)} have been set before
     * sorting.
     * 
     * @return total count of forms.
     */
    public int size();
    
    /**
     * Sort according to one order implemented. Sort before iterate.
     * 
     * @param order on which to sort.
     */
    public void sort(final Order order);

    /**
     * Partial sort to get a top of elements.
     * 
     * @param order on which to sort.
     * @param limit top element according to sort order.
     */
    public void sort(final Order order, final int limit);

    /**
     * Returns a safe copy of the sorted form ids.
     * 
     * @return Sorted formId according to last sort().
     */
    public int[] sorter();
}
