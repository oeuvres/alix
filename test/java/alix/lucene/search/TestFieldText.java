package alix.lucene.search;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.util.FixedBitSet;

import alix.lucene.Alix;
import alix.lucene.TestAlix;
import alix.lucene.search.FieldText;
import alix.web.OptionOrder;

public class TestFieldText
{

    static public void stops() throws IOException
    {
        Alix alix = TestAlix.miniBase(new WhitespaceAnalyzer());
        TestAlix.write(alix, new String[] { "Le petit chat est mort ce matin.",
                "La vies est un long fleuve tranquille.", "Les mots s’ajoutent peu à et se perdent.",
                "À partir de quand ne trouve-t-on plus de mots vides ?", "Les chats de la comtesse sont aphones.", });
        String fieldName = TestAlix.fieldName;
        FieldText fstats = alix.fieldText(fieldName);
        System.out.println("V=" + fstats.maxForm);
        for (int formId = 0; formId < fstats.maxForm; formId++) {
            System.out.println(formId + "=" + fstats.form(formId));
        }
    }

    static public void mini() throws IOException
    {
        Alix alix = TestAlix.miniBase(new WhitespaceAnalyzer());
        TestAlix.write(alix, new String[] { "C C C A", "A C C B", "B A A", "C B B F", "A C C A C E ", "B B B", });
        String fieldName = TestAlix.fieldName;
        FieldText ftext = alix.fieldText(fieldName);
        FormEnum terms = ftext.forms();
        System.out.println("forms, in formId order");
        System.out.println(terms);
        FixedBitSet bits = new FixedBitSet(alix.maxDoc());
        bits.set(2);
        bits.set(4);
        for (int docId = 0; docId < bits.length(); docId++) {
            if (bits.get(docId))
                System.out.print('1');
            else
                System.out.print('·');
        }
        System.out.println();
        // check if forms available in filter
        String[] forms = new String[]{"blah", "A", "D", "B", "E"};
        System.out.println("Forms by a doc filter, test:");
        System.out.println(Arrays.toString(forms));
        int[] formIds = ftext.formIds(forms, bits);
        System.out.println("found:");
        for (int formId: formIds) {
            System.out.print("    " + formId + ":" + ftext.form(formId));
        }
        System.out.println();
        // get stats about the filter
        FormEnum results = new FormEnum(ftext);
        results.filter = bits;
        ftext.filter(results);
        System.out.println("ftext.filter(results)");
        results.sort(OptionOrder.freq.order());
        System.out.println(results);
        /*
        System.out.println("Filtered forms, occurrences");
        // terms = ftext.results(-1, null, bits, null);
        System.out.println(terms);

        System.out.println("Filtered forms, reverse chi2");
        // terms = ftext.results(3, new SpecifChi2(), bits, null, true);
        System.out.println(terms);
        while (terms.hasNext()) {
            terms.next();
            System.out.println(terms.form() + " " + terms.score());
        }
        */
    }

    static public void perfs() throws IOException
    {
        /*
         * long time; Alix alix = Alix.instance("", "web/WEB-INF/obvil/test", new
         * FrAnalyzer()); String field = "bibl"; time = System.nanoTime(); FieldText
         * fstats = alix.fieldText(field); System.out.println(fstats);
         * System.out.println("Terms in " + ((System.nanoTime() - time) / 1000000) +
         * " ms."); time = System.nanoTime(); TermIterator search search =
         * fstats.topTerms(); System.out.println("\n\nDic in " + ((System.nanoTime() -
         * time) / 1000000) + " ms."); time = System.nanoTime(); search.sortByOccs();
         * System.out.println("\n\nSort by score in " + ((System.nanoTime() - time) /
         * 1000000) + " ms."); print(search); time = System.nanoTime();
         * search.sortByScores(); System.out.println("\n\nSort by occ in " +
         * ((System.nanoTime() - time) / 1000000) + " ms."); print(search);
         */
    }

    public static void main(String[] args) throws IOException
    {
        mini();
        // formStop();
    }
}
