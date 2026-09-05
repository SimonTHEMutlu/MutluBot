#!/bin/bash
# Builds the engine and produces dist/chess-engine.jar
set -e
cd "$(dirname "$0")"
rm -rf out dist
mkdir -p out dist
javac --release 8 -d out src/engine/*.java
printf "Main-Class: engine.UCIEngine\n" > dist/MANIFEST.MF
jar cfm dist/chess-engine.jar dist/MANIFEST.MF -C out .
rm dist/MANIFEST.MF
echo "Built dist/chess-engine.jar"
echo "Test it with:  java -jar dist/chess-engine.jar"
