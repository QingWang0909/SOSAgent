#!/bin/sh

# script that starting  SOS agent, used for SOS Dockerfile entry point
cd SOSAgent/
java -jar target/sosagent.jar
