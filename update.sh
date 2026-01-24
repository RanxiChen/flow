#!/usr/bin/bash
echo "Clean previous build"
rm -rf generated/
rm -rf design/build
echo "rebuild"
cd design && sbt 'runMain top.GenerateFlowTop'
cp -r build/ ../generated
