package alix.lucene;

import org.apache.lucene.index.FieldInvertState;
import org.apache.lucene.search.similarities.TFIDFSimilarity;
import org.apache.lucene.util.BytesRef;

public class ChapitreSimilariy extends TFIDFSimilarity {

  @Override
  public float lengthNorm(FieldInvertState state) {
    return state.getMaxTermFrequency();
  }

  @Override
  public long encodeNormValue(float f) {
    return (byte) f;
  }

  @Override
  public float decodeNormValue(long norm) {
    return norm;
  }

  @Override public float coord(int overlap, int maxOverlap) { return 0; }
  @Override public float queryNorm(float sumOfSquaredWeights) { return 0; }
  @Override public float tf(float freq) { return 0; }
  @Override public float idf(long docFreq, long docCount) { return 0; }
  @Override public float sloppyFreq(int distance) { return 0; }
  @Override public float scorePayload(int doc, int start, int end, BytesRef payload) { return 0; }
}
