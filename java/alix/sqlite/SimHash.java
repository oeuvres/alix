package alix.sqlite;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public class SimHash<T>
{
  private List<BitSet> hashingDim;

  public static interface Hashing<T>
  {
    public BitSet hashing(T t);
  }

  private SimHash() {
  }

  public static <T> SimHash<T> build(List<T> dimensions)
  {
    Hashing<T> hashing = new Hashing<T>() {
      @Override
      public BitSet hashing(T t)
      {
        String key = t.toString();
        byte[] bytes = null;
        try {
          MessageDigest md = MessageDigest.getInstance("MD5");
          bytes = md.digest(key.getBytes());
        }
        catch (NoSuchAlgorithmException e) {
          e.printStackTrace();
        }

        BitSet ret = new BitSet(bytes.length * 8);
        for (int k = 0; k < bytes.length * 8; k++) {
          if (bitTest(bytes, k))
            ret.set(k);
        }

        return ret;
      }
    };

    return build(dimensions, hashing);
  }

  public static <T> SimHash<T> build(List<T> dimensions, Hashing<T> hashing)
  {
    SimHash<T> ret = new SimHash<T>();
    ret.hashingDim = new ArrayList<BitSet>(dimensions.size());
    for (T dim : dimensions) {
      BitSet bits = hashing.hashing(dim);
      ret.hashingDim.add(bits);
    }

    return ret;
  }

  private static boolean bitTest(byte[] data, int k)
  {
    int i = k / 8;
    int j = k % 8;
    byte v = data[i];
    int v2 = v & 0xFF;
    return ((v2 >>> (7 - j)) & 0x01) > 0;
  }

  public BitSet simHash(double[] vector)
  {
    double[] feature = new double[hashingDim.get(0).size()];

    for (int d = 0; d < hashingDim.size(); d++) {
      BitSet bits = hashingDim.get(d);

      for (int k = 0; k < bits.size(); k++) {
        if (bits.get(k)) {
          feature[k] += vector[d];
        }
        else {
          feature[k] -= vector[d];
        }
      }
    }

    // convert feature to bits
    BitSet ret = new BitSet(feature.length);
    for (int k = 0; k < feature.length; k++) {
      if (feature[k] > 0)
        ret.set(k);
    }

    return ret;
  }

  public double similarity(double[] v1, double[] v2)
  {
    BitSet s1 = simHash(v1);
    BitSet s2 = simHash(v2);

    s1.xor(s2);
    return 1.0 - (double) s1.cardinality() / s1.size();
  }

  public double distance(double[] v1, double[] v2)
  {
    double sim = similarity(v1, v2);
    double dis = -Math.log(sim);
    return dis;
  }
}