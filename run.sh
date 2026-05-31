#!/bin/bash
set -e
mvn clean install -DskipTests -q && mvn javafx:run -pl app
