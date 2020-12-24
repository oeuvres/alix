/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package alix.lucene.search;

/**
 * Implemtation of the “Stella” scorer for a term like described by TXM
 * http://textometrie.ens-lyon.fr/html/doc/manual/0.7.9/fr/manual43.xhtml
 * 
 * <li>formPart  f : la fréquence de l’événement dans la partie ;
 * <li>formAll   F : la fréquence totale de l’événement dans le corpus ;
 * <li>wcPart    t : le nombre total d’événements ayant lieu dans la partie ;
 * <li>wcAll     T : le nombre total d’événements ayant lieu dans l’ensemble des parties.
 * 
 * 
 * @author fred
 *
 */
public class ScorerStella extends Specif
{
  public  ScorerStella()
  {
    
  }
  public  ScorerStella(long occsAll, int docsAll)
  {
    setAll(occsAll, docsAll);
  }

  @Override
  public void weight(final long wcPart, final int docsPart)
  {
  }

  @Override
  public double score(final long formPart, final long formAll, final long wcDoc)
  {
    return (double)1000000 * formPart / wcDoc;
  }

}
