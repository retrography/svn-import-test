@echo off
java -Dtriplea.ai=true -classpath ../lib/patch.jar;../classes;../lib/plastic-1.2.0.jar  games.strategy.engine.framework.GameRunner 
pause
