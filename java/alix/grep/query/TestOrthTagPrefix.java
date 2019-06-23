package alix.grep.query;

import alix.fr.Tag;
import alix.util.Occ;

public class TestOrthTagPrefix extends TestTerm
{
  int prefix;

  public TestOrthTagPrefix(final String term, final int prefix) {
    super(term);
    this.prefix = prefix;
  }

  @Override
  public boolean test(Occ occ)
  {
    if (occ.tag().group() != prefix)
      return false;
    return chain.glob(occ.orth());
  }

  @Override
  public String label()
  {
    return chain + ":" + Tag.label(prefix);
  }

}
