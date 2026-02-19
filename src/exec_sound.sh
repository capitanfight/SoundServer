#!/bin/bash

$dir: "$HOME/.sound"

javac "$dir/SoundServer.java"
nohup java "$dir/SoundServer" > /dev/null 2>&1 &