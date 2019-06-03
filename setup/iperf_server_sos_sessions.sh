#!/bin/sh

iperf -s -p 5001 > port5001.log &
iperf -s -p 5002 > port5002.log &
iperf -s -p 5003 > port5003.log &

exit 0

