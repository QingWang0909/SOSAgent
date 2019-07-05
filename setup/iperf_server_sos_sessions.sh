#!/bin/sh


for i in `seq 1 1`
do
    > port500$i.log
    iperf -s -p 500$i > port500$i.log &
done

exit 0
