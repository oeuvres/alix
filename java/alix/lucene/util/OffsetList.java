package alix.lucene.util;

import java.util.Arrays;

import org.apache.lucene.util.BytesRef;

import alix.util.Calcul;

/**
 * A growable list of couples of ints backed to a byte array with convenient
 * methods for conversion. Designed to get back information recorded from a
 * lucene Strored Field
 *
 * @author glorieux-f
 */
public class OffsetList
{
  /** Internal data */
  private byte[] bytes;
  /** Internal pointer in byte array for next() */
  private int pointer;
  /** Start index in the byte array (like in ) */
  private int offset;
  /** Internal length of used bytes */
  private int length;

  public OffsetList()
  {
    bytes = new byte[64];
  }

  public OffsetList(BytesRef bytesref)
  {
    this.bytes = bytesref.bytes;
    this.offset = bytesref.offset;
    this.length = bytesref.length;
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

  /**
   * Return data as a BytesRef, may be indexed in a lucene binary field.
   * 
   * @return
   */
  public BytesRef getBytesRef()
  {
    return new BytesRef(bytes, offset, length);
  }

  /**
   * Get end offset at position ord.
   * 
   * @param ord
   * @return
   */
  public int getEnd(final int pos)
  {
    return getInt(offset + pos << 3 + 4);
  }

  /**
   * Get int from an index in bytes.
   * 
   * @param index
   * @return
   */
  private int getInt(final int index)
  {
    return (((bytes[index]) << 24) | ((bytes[index + 1] & 0xff) << 16) | ((bytes[index + 2] & 0xff) << 8)
        | ((bytes[index + 3] & 0xff)));
  }

  /**
   * Get start offset at position ord.
   * 
   * @param ord
   * @return
   */
  public int getStart(final int pos)
  {
    return getInt(offset + pos << 3);
  }

  /**
   * Grow the data array to ensure capacity.
   * 
   * @param minCapacity
   */
  private void grow(final int minCapacity)
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

  /**
   * Length of list in bytes (= size * 8)
   * 
   * @return
   */
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
  private OffsetList put(int x)
  {
    length = length + 4;
    grow(offset + length);
    // Big Endian
    bytes[pointer++] = (byte) (x >> 24);
    bytes[pointer++] = (byte) (x >> 16);
    bytes[pointer++] = (byte) (x >> 8);
    bytes[pointer++] = (byte) x;
    return this;
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
   * Reset pointer, with no erase.
   * 
   * @return
   */
  public void reset()
  {
    pointer = offset;
  }

  /**
   * Size of list in couples of ints (= length / 8)
   * 
   * @return
   */
  public int size()
  {
    return length >> 3;
  }

}
