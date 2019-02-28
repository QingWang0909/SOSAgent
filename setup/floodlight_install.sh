#!/bin/sh

# Download Floodlight
git clone https://github.com/khayamgondal/floodlight.git
cd floodlight/
git checkout shella1.0

# Install Java 8 & Maven
sudo apt-get update
sudo add-apt-repository -y ppa:webupd8team/java
sudo apt install -y openjdk-8-jdk
sudo apt-get -y install maven

# Compile Floodlight
mvn compile
mvn package -DskipTests