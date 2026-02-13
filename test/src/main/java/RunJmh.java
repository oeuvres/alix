import org.openjdk.jmh.Main;

public class RunJmh {
    public static void main(String[] args) throws Exception {
        Main.main(new String[]{
                "CSVReaderBenchmark",      // include regex
                "-wi", "3", "-i", "5",     // warmup/measurement iters
                "-f", "1",                 // forks
                "-tu", "s"                 // time unit
            });
    }
}
