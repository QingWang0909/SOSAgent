#!/bin/sh

iperf -c 10.0.0.4 -t 30 -i 1 -p 5001 > port5001.log &
iperf -c 10.0.0.4 -t 30 -i 1 -p 5002 > port5002.log &
iperf -c 10.0.0.4 -t 30 -i 1 -p 5003 > port5003.log &

exit 0