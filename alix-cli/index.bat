@echo off 
setlocal
SET DIR=%~dp0
java -cp "%DIR%lib/*" com.github.oeuvres.alix.lucene.index.Load %*

