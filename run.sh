#!/bin/bash
set -e
JLSHELL_JVM_XMS="${JLSHELL_JVM_XMS:-64m}"
JLSHELL_JVM_XMX="${JLSHELL_JVM_XMX:-512m}"
mvn clean install -DskipTests -q
mvn javafx:run -pl app -Djlshell.jvm.xms="$JLSHELL_JVM_XMS" -Djlshell.jvm.xmx="$JLSHELL_JVM_XMX"
