#!/bin/bash

#https://github.com/uplusplus/mateclaw.git

sudo apt install openjdk-21-jdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn clean install -s mateclaw-server/settings.xml
cd mateclaw-server
mvn spring-boot:run -s settings.xml  &
npm install pnpm
pnpm install
pnpm dev
