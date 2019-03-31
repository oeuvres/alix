package alix.lucene;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRef;

import alix.util.Calcul;

/**
 * A growable list of couples of ints backed to a byte array with convenient methods for
 * conversion. Designed to get back information recorded from a lucene Strored Field
 * (pointing to a byte array without copy).
 *
 * @author glorieux-f
 */
public class OffsetList
{
    /** Internal data */
    private byte[] bytes;
    /** Internal pointer in byte array, is not an int position */
    private int pointer;
    /** Start index */
    private int offset;
    /** Internal length of used bytes */
    private int length;

    public OffsetList()
    {
        bytes = new byte[64];
    }

    public OffsetList(int offset)
    {
        this(offset, 64);
    }

    public OffsetList(int offset, int length)
    {
        int floor = offset + length;
        int capacity = Calcul.nextSquare(floor);
        if (capacity <= 0) { // too big
            capacity = Integer.MAX_VALUE;
            if (capacity - floor < 0) throw new OutOfMemoryError("Size bigger than Integer.MAX_VALUE");
        }
        bytes = new byte[capacity];
        this.offset = offset;
        pointer = offset;
    }

    public OffsetList(BytesRef bytesref)
    {
        this.bytes = bytesref.bytes;
        this.offset = bytesref.offset;
        this.length = bytesref.length;
    }

    /**
     * Reset pointer, with no erase.
     * 
     * @return
     */
    public void reset()
    {
        pointer = offset;
    }
    /**
     * Length of list in bytes (= size * 8)
     * @return
     */
    public int length()
    {
        return length;
    }
    /**
     * Size of list in couples of ints (= length / 8)
     * @return
     */
    public int size()
    {
        return length >> 3;
    }
    /**
     * Add a couple start-end index
     * 
     * @param value
     * @return
     */
    public void put(int start, int end)
    {
        this.put(start).put(end);
    }
    
    /**
     * Add on more value at the end
     * 
     * @param value
     * @return
     */
    private OffsetList put(int x)
    {
        length = length + 4;
        ensureCapacity(offset + length);
        // Big Endian
        bytes[pointer++] = (byte) (x >> 24);
        bytes[pointer++] = (byte) (x >> 16);
        bytes[pointer++] = (byte) (x >> 8);
        bytes[pointer++] = (byte) x;
        return this;
    }

    public int getStart(final int pos)
    {
        return get(offset + pos << 3);
    }
    public int getEnd(final int pos)
    {
        return get(offset + pos << 3 + 4);
    }

    private int get(final int index)
    {
        return (((bytes[index]) << 24) | ((bytes[index+1] & 0xff) << 16) | ((bytes[index+2] & 0xff) << 8)
                | ((bytes[index+3] & 0xff)));
    }


    private void ensureCapacity(final int minCapacity)
    {
        int oldCapacity = bytes.length;
        if (oldCapacity - minCapacity > 0) return;
        int newCapacity = Calcul.nextSquare(minCapacity);
        if (newCapacity <= 0) { // too big
            newCapacity = Integer.MAX_VALUE;
            if (newCapacity - minCapacity < 0) throw new OutOfMemoryError("Size bigger than Integer.MAX_VALUE");
        }
        bytes = Arrays.copyOf(bytes, newCapacity);
    }

    public BytesRef getBytesRef()
    {
        return new BytesRef(bytes, offset, length);
    }

    /**
     * Test performances
     */
    public static void main(String[] args) throws IOException
    {
        long time;
        OffsetList offsets = new OffsetList();
        
        int size = 3;
        for(int i=0; i < size; i++) {
            offsets.put(i*5, i*5+1);
        }
        System.out.println(Arrays.toString(offsets.bytes));
        System.out.println(offsets.size());

        /*
         
        // bitshift is faster than / power of two
        int ops = 100000000;
        int test;
        for(int loop = 0; loop < 5; loop++) {
            time = System.nanoTime();
            test = 0;
            for (int i=0; i < ops; i++) {
                test += i / 2;
            }
            System.out.println("/2: " + ((System.nanoTime() - time) / 1000000) + " ms. " + test);
            time = System.nanoTime();
            test = 0;
            for (int i=0; i < ops; i++) {
                test += i >> 1;
            }
            System.out.println(">>1: " + ((System.nanoTime() - time) / 1000000) + " ms. " + test);
        }
        */
        System.exit(0);
        
        Random rand = new Random(); 
        Analyzer analyzer = new AlixAnalyzer();
        Directory directory = new RAMDirectory();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        IndexWriter indexWriter = new IndexWriter(directory, config);
        Document doc = new Document();
        String text = "Lucene is an Information Retrieval library written in Java";
        doc.add(new TextField("text", text, Field.Store.NO));
        size = 100000;
        for(int i=-0; i <= size; i++) {
            offsets.put(-i, i);
        }
        doc.add(new StoredField("offsets", offsets.getBytesRef()));
        indexWriter.addDocument(doc);
        IndexReader ir=DirectoryReader.open(indexWriter);
        IndexSearcher is = new IndexSearcher(ir);
        doc = ir.document(0);

        /* 
         // Verify that picking directly in the byte array is more efficient than copy it in an int array
        int samples = 100;
        for(int loop = 0; loop < 5; loop++) {
            time = System.nanoTime();
            for(int i=0; i < samples; i++) {
                int pick = rand.nextInt(size);
                int v = offsets.getStart(pick);
            }
            System.out.println("Direct BytesRef " + ((System.nanoTime() - time) / 1000000) + " ms.");
            
            time = System.nanoTime();
            int[] ints = new int[size];
            for(int i=0; i < size; i++) {
                ints[i] = offsets.get(i);
            }
            for(int i=0; i < samples; i++) {
                int pick = rand.nextInt(size);
                int v = ints[pick];
            }
            System.out.println("Int[] copy " + ((System.nanoTime() - time) / 1000000) + " ms.");
        }
        */
    }
}
