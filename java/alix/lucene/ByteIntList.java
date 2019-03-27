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
 * A growable list of ints backed to a byte array with convenient methods for
 * conversion.
 *
 * @author glorieux-f
 */
public class ByteIntList
{
    /** Internal data */
    private byte[] bytes;
    /** Internal pointer in byte array, is not an int position */
    private int pointer;
    /** Start index */
    private int offset;
    /** Internal length of used bytes */
    private int length;

    public ByteIntList()
    {
        bytes = new byte[64];
    }

    public ByteIntList(int offset)
    {
        this(offset, 64);
    }

    public ByteIntList(int offset, int length)
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

    public ByteIntList(BytesRef bytesref)
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

    public int length()
    {
        return length;
    }

    /**
     * Add on more value at the end
     * 
     * @param value
     * @return
     */
    public ByteIntList put(int x)
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

    public int get(final int pos)
    {
        pointer = offset + pos * 4;
        return next();
    }

    public int next()
    {
        return (((bytes[pointer++]) << 24) | ((bytes[pointer++] & 0xff) << 16) | ((bytes[pointer++] & 0xff) << 8)
                | ((bytes[pointer++] & 0xff)));
    }

    public boolean hasMore()
    {
        if (pointer < 0 || length + offset - pointer - 4 < 0) return false;
        return true;
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
        Random rand = new Random(); 
        Analyzer analyzer = new AlixAnalyzer();
        Directory directory = new RAMDirectory();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        IndexWriter indexWriter = new IndexWriter(directory, config);
        Document doc = new Document();
        String text = "Lucene is an Information Retrieval library written in Java";
        doc.add(new TextField("text", text, Field.Store.NO));
        ByteIntList offsets = new ByteIntList();
        
        int size = 100000;
        for(int i=-0; i <= size; i++) {
            offsets.put(i);
        }
        doc.add(new StoredField("offsets", offsets.getBytesRef()));
        indexWriter.addDocument(doc);
        IndexReader ir=DirectoryReader.open(indexWriter);
        IndexSearcher is = new IndexSearcher(ir);
        doc = ir.document(0);
        offsets = new ByteIntList(doc.getField("offsets").binaryValue());
        
        int samples = 100;
        
        for(int loop = 0; loop < 5; loop++) {
            time = System.nanoTime();
            for(int i=0; i < samples; i++) {
                int pick = rand.nextInt(size);
                int v = offsets.get(pick);
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
    }
}
