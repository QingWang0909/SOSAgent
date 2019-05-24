#!/bin/sh

# start SOS agent script for container entry point

#> /etc/hosts
cd SOSAgent/
java -jar target/sosagent.jar
