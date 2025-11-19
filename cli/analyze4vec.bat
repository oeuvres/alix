@echo off 
setlocal
SET DIR=%~dp0
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug -cp "%DIR%lib/*" com.github.oeuvres.alix.lucene.index.Analyze4vec %*

