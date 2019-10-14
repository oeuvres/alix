package alix.lucene.util;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.apache.lucene.util.BytesRef;

import alix.util.Calcul;


abstract class BinaryValue
{
  /** Reusable buffer */
  protected ByteBuffer buf;
  /** Byte capacity of the buffer, updated on growing */
  protected int capacity;
  /** Byte length of the buffer, updated on put */
  protected int length;
  /** Internal pointer data */
  protected BytesRef ref;

  /**
   * Reset before writing
   * @param offset
   * @param length
   */
  public void reset()
  {
    if (buf == null) {
      capacity = 1024;
      buf =  ByteBuffer.allocate(capacity);
    }
    else {
      buf.clear(); // reset markers but do not erase
      // tested, 2x faster than System.arraycopy after 5 iterations
      Arrays.fill(buf.array(), (byte)0);
    }
    length = 0;
  }
  
  /**
   * Open offsets in a stored field.
   * @param ref
   */
  public void open(final BytesRef ref)
  {
    this.ref = ref;
    this.length = ref.length;
    buf = ByteBuffer.wrap(ref.bytes, ref.offset, ref.length);
  }

  /**
   * Return data as a BytesRef that can be indexed.
   * 
   * @return
   */
  public BytesRef getBytesRef()
  {
    return new BytesRef(buf.array(), buf.arrayOffset(), length);
  }

  /**
   * Grow the bytes buffer
   * 
   * @param minCapacity
   */
  protected void grow(final int cap)
  {
    final int newCap = Calcul.nextSquare(cap) +13;
    // no test, let cry
    ByteBuffer expanded = ByteBuffer.allocate(newCap);
    buf.position(0); // if not, data lost
    expanded.put(buf);
    buf = expanded;
    capacity = newCap;
  }

}
