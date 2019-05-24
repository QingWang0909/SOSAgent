#!/bin/sh

sudo apt-get update
sudo apt-get install -y git-core && sudo apt-get -y install maven && sudo apt-get -y install curl
sudo apt-get -y install software-properties-common
sudo add-apt-repository -y ppa:webupd8team/java
sudo apt-get update
sudo apt-get install -y openjdk-8-jdk
git clone https://github.com/QingWang0909/SOSAgent.git
cd SOSAgent
mvn -DskipTests=true compile
mvn -DskipTests=true package