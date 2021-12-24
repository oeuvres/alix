package alix.sqlite;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class TestInsert {

    public static void main(String[] args) throws IOException, SQLException
    {
        Logger rootLogger = LogManager.getLogManager().getLogger("");
        rootLogger.setLevel(Level.ALL);
        for (Handler h : rootLogger.getHandlers()) {
            h.setLevel(Level.ALL);
        }
        
        File sqlite = new File("test/out/test.db");
        Insert.connect(sqlite);
        Insert.unzip(new File("test/res/html.zip"));
    }
}
