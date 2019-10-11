/*
 * Copyright 2008 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents
 * Alix is a tool to index XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertise for French.
 * Project has been started in 2008 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
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
package alix.util;

import java.io.IOException;

import alix.fr.Tokenizer;

/**
 * A sliding window of tokens
 * 
 * @author glorieux-f
 *
 * @param <T>
 */
public class OccRoller extends Roller
{
  /** Data of the sliding window */
  private final Occ[] data;

  /**
   * Constructor, init data
   */
  public OccRoller(final int left, final int right) {
    super(left, right);
    data = new Occ[size];
    for (int i = 0; i < size; i++) {
      data[i] = new Occ();
      if (i == 0)
        continue;
      data[i - i].next(data[i]);
      data[i].prev(data[i - 1]);
      if (i != size - 1)
        continue;
      data[i].next(data[0]);
      data[0].prev(data[i]);
    }
  }

  /**
   * Move index to the next position and return a pointer on the new current
   * Object, clear the last left Object to not find it at extreme right. Because
   * the line is circular, there no limit, move to a position bigger than width
   * will clear data.
   * 
   * @return the new current chain
   */
  public Occ move(int count)
  {
    if (count == 0)
      return data[center];
    if (count > 0) {
      for (int i = 0; i < count; i++) {
        // if left = 0, center will become right and will be cleared
        data[pointer(left)].clear();
        center = pointer(1);
      }
    }
    else {
      for (int i = 0; i < -count; i++) {
        data[pointer(right)].clear();
        center = pointer(-1);
      }
    }
    return data[center];
  }

  /**
   * Move index to the next position and return a pointer on the new current
   * Object, clear the last left Object to not find it at extreme right
   * 
   * @return the new current chain
   */
  public Occ __next()
  {
    return move(1);
  }

  /**
   * Get a value by index, positive or negative, relative to center
   * 
   * @param ord
   * @return
   */
  public Occ get(final int pos)
  {
    return data[pointer(pos)];
  }

  /**
   * Get first value
   * 
   * @return
   */
  public Occ first()
  {
    return data[pointer(right)];
  }

  /**
   * Return last value
   * 
   * @return
   */
  public Occ last()
  {
    return data[pointer(left)];
  }

  /**
   * Give a pointer on the right Tok object that a Tokenizer can modify
   */
  public Occ add()
  {
    center = pointer(+1);
    return data[pointer(right)];
  }

  /**
   * Copy an occurrence object at right end
   * 
   * @return The left token
   */
  public Occ push(final Occ value)
  {
    // modulo in java produce negatives
    Occ ret = data[pointer(left)];
    center = pointer(+1);
    // copy content !!!, the source occ may run in another chain
    data[pointer(right)].set(value);
    return ret;
  }

  /**
   * Show window content
   */
  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    for (int i = left; i <= right; i++) {
      if (i == 0)
        sb.append("<");
      sb.append(get(i).graph());
      if (i == 0)
        sb.append(">");
      sb.append(" ");
    }
    return sb.toString();
  }

  /**
   * Test the Class
   * 
   * @param args
   * @throws IOException
   */
  public static void main(String args[]) throws IOException
  {
    String text = "<> On a exagéré bien probablement sur Cartouche, "
        + "mais il n’en reste pas moins un coquin assez complet, et si on a exagéré sur "
        + "M<hi rend=\"sup\">me</hi> Du Barry, nous croyons qu’elle restera aussi une coquine assez complète. "
        + "L’histoire de M. Capefigue changera peu de chose à cela, et comment le pourrait-elle ? "
        + "À part l’admiration pour la femme et l’attendrissement continu pour sa destinée, "
        + "il n’y a pas un fait ou une manière de regarder les faits qui puisse modifier d’un iota "
        + "l’opinion générale sur une femmelette dont M. Capefigue avait cru repétrir la statuette, "
        + "j’imagine, pour le seul plaisir d’y toucher. M. Capefigue qui, depuis longtemps,"
        + "n’appuie plus l’histoire, n’avait pas le pouce qu’il fallait pour laisser n’importe "
        + "quelle empreinte sur ce petit bronze libertin du <num>xviii<hi rend=\"sup\">e</hi></num> siècle, "
        + "dans lequel la Postérité continuera de voir — tout simplement — l’image à voiler de la Frétillon "
        + "d’un roi qui ne pétillait plus, de l’indécente Sunamite d’un Salomon qui ne fut jamais "
        + "Salomon que par la vieillesse !";
    OccRoller win = new OccRoller(-10, 10);
    Tokenizer toks = new Tokenizer(text);
    Occ occ;
    while ((occ = toks.word()) != null) {
      win.push(occ);
      System.out.println(win);
    }
  }
}
