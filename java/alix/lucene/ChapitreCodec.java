package alix.lucene;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene50.Lucene50StoredFieldsFormat.Mode;
import org.apache.lucene.codecs.lucene54.Lucene54Codec;
import org.apache.lucene.codecs.memory.MemoryPostingsFormat;
import org.apache.lucene.codecs.perfield.PerFieldPostingsFormat;

public class ChapitreCodec extends FilterCodec  {
  // protected final MemoryPostingsFormat memoryFormat = new MemoryPostingsFormat(); 
  private final PostingsFormat pf = new PerFieldPostingsFormat() {
    @Override
    public PostingsFormat getPostingsFormatForField(String field) {
      if(field.endsWith("-mem")) return PostingsFormat.forName("Memory");
      return PostingsFormat.forName("Lucene50");
    }
  }; 
  public ChapitreCodec() {
    super("ChapitreCodec", new Lucene54Codec(Mode.BEST_SPEED));
    
  }
  @Override
  public PostingsFormat postingsFormat() {
    return pf;
  } 
}
