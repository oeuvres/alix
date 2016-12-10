package alix.util;

public class Phrase 
{
  private int[] data;
  private short size;
  private int hash;
  public Phrase( )
  {
    data = new int[8];
  }
  public Phrase( Phrase phr)
  {
    short max = phr.size;
    this.size = max;
    data = new int[max];
    int[] newdata = phr.data;
    for ( int i=0; i < max; i++ ) data[i] = newdata[i];
  }
  public Phrase( int a )
  {
    data = new int[]{a};
    size = 0;
  }
  public Phrase( int a, int b )
  {
    data = new int[]{a, b};
    size = 2;
  }
  public Phrase( int a, int b, int c )
  {
    data = new int[]{a, b, c};
    size = 3;
  }
  public Phrase( int a, int b, int c, int d )
  {
    data = new int[]{a, b, c, d};
    size = 4;
  }
  public Phrase( int a, int b, int c, int d, int e )
  {
    data = new int[]{a, b, c, d, e};
    size = 5;
  }
  protected Phrase reset()
  {
    size = 0;
    hash = 0;
    return this;
  }
  public Phrase append( int a )
  {
    if ( size == data.length ) grow();
    data[size] = a;
    size++;
    hash = 0;
    return this;
  }
  private Phrase grow()
  {
    final int oldLength = data.length;
    final int[] oldData = data;
    int capacity = Calcul.nextSquare( oldLength );
    data = new int[capacity];
    System.arraycopy( oldData, 0, data, 0, oldLength );
    return this;
  }
  public Phrase set( int a )
  {
    data[0] = a;
    size = 1;
    hash = 0;
    return this;
  }
  public Phrase set( int a, int b )
  {
    data[0] = a;
    data[1] = b;
    size = 2;
    hash = 0;
    return this;
  }
  public Phrase set( int a, int b, int c )
  {
    data[0] = a;
    data[1] = b;
    data[2] = c;
    size = 3;
    hash = 0;
    return this;
  }
  public Phrase set( int a, int b, int c, int d )
  {
    data[0] = a;
    data[1] = b;
    data[2] = c;
    data[3] = d;
    size = 4;
    hash = 0;
    return this;
  }
  public Phrase set( int a, int b, int c, int d, int e )
  {
    data[0] = a;
    data[1] = b;
    data[2] = c;
    data[3] = d;
    data[4] = e;
    size = 5;
    hash = 0;
    return this;
  }

  @Override
  public boolean equals(Object o)
  {
    if (o == null) return false;
    if ( !(o instanceof Phrase) ) return false;
    Phrase phr = (Phrase)o;
    if ( phr.size != size ) return false;
    for (short i=0; i < size; i++ ) {
      if ( phr.data[i] != data[i] ) return false;
    }
    return true;
  }
  @Override 
  public int hashCode() 
  {
    if ( hash != 0 ) return hash;
    int res = 17;
    for ( int i=0; i < size; i++ ) {
      res = 31 * res + data[i];
    }
    return res;
  }
  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    for (int i=0; i < size; i++ ) {
      if ( i > 0 ) sb.append( ", " );
      sb.append( data[i] );
    }
    return sb.toString();
  }
  public String toString( TermDic dic)
  {
    StringBuffer sb = new StringBuffer();
    for (int i=0; i < size; i++ ) {
      if ( i > 0 ) sb.append( " " );
      sb.append( dic.term( data[i]) );
    }
    return sb.toString();
  }
}
